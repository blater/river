package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.zone.ZoneOffsetTransitionRule;
import java.util.List;

/** Compact primitive form of a zone's bounded recurring transition rules. */
final class SqlTemporalRecurringRules {
  static final int MAXIMUM_RULES = 16;
  private static final int DEFINITION_UTC = 0;
  private static final int DEFINITION_WALL = 1;
  private static final int DEFINITION_STANDARD = 2;
  private static final long SECONDS_PER_DAY = 86_400L;
  private static final long MINIMUM_SECOND = Math.floorDiv(
      LocalTemporal.MINIMUM_INSTANT_MICROSECONDS,
      LocalTemporal.MICROSECONDS_PER_SECOND);
  private static final long MAXIMUM_SECOND = Math.floorDiv(
      LocalTemporal.MAXIMUM_INSTANT_MICROSECONDS,
      LocalTemporal.MICROSECONDS_PER_SECOND);

  private final byte[] months = new byte[MAXIMUM_RULES];
  private final byte[] monthDays = new byte[MAXIMUM_RULES];
  private final byte[] weekDays = new byte[MAXIMUM_RULES];
  private final byte[] definitions = new byte[MAXIMUM_RULES];
  private final boolean[] midnightEndOfDay = new boolean[MAXIMUM_RULES];
  private final int[] localSeconds = new int[MAXIMUM_RULES];
  private final int[] standardOffsets = new int[MAXIMUM_RULES];
  private final int[] offsetsBefore = new int[MAXIMUM_RULES];
  private final int[] offsetsAfter = new int[MAXIMUM_RULES];
  private int count;

  StatusCode prepare(List<ZoneOffsetTransitionRule> source) {
    count = 0;
    if (source.size() > MAXIMUM_RULES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (ZoneOffsetTransitionRule rule : source) {
      LocalTime time = rule.getLocalTime();
      if (time.getNano() != 0) {
        count = 0;
        return StatusCode.INVARIANT_BROKEN;
      }
      int index = count++;
      months[index] = (byte) rule.getMonth().getValue();
      monthDays[index] = (byte) rule.getDayOfMonthIndicator();
      DayOfWeek weekDay = rule.getDayOfWeek();
      weekDays[index] = (byte) (weekDay == null ? 0 : weekDay.getValue());
      definitions[index] = definition(rule.getTimeDefinition());
      midnightEndOfDay[index] = rule.isMidnightEndOfDay();
      localSeconds[index] = time.toSecondOfDay();
      standardOffsets[index] = rule.getStandardOffset().getTotalSeconds();
      offsetsBefore[index] = rule.getOffsetBefore().getTotalSeconds();
      offsetsAfter[index] = rule.getOffsetAfter().getTotalSeconds();
    }
    return StatusCode.OK;
  }

  void reset() {
    count = 0;
  }

  int count() {
    return count;
  }

  int initialOffset(int fallback) {
    long latestBeforeDomain = Long.MIN_VALUE;
    long earliestInDomain = Long.MAX_VALUE;
    int offset = fallback;
    for (int year = 0; year <= 1; year++) {
      for (int index = 0; index < count; index++) {
        long second = transitionSecond(index, year);
        if (second <= MINIMUM_SECOND && second > latestBeforeDomain) {
          latestBeforeDomain = second;
          offset = offsetsAfter[index];
        } else if (latestBeforeDomain == Long.MIN_VALUE
            && second < earliestInDomain) {
          earliestInDomain = second;
          offset = offsetsBefore[index];
        }
      }
    }
    return offset;
  }

  int offsetAt(long second, long lastHistoricSecond, int fallback) {
    int year = yearAtSecond(second);
    long latest = Long.MIN_VALUE;
    int offset = fallback;
    for (int candidateYear = Math.max(0, year - 1);
        candidateYear <= Math.min(10_000, year + 1);
        candidateYear++) {
      for (int index = 0; index < count; index++) {
        long candidate = transitionSecond(index, candidateYear);
        if (candidate > lastHistoricSecond
            && candidate <= second
            && candidate > latest
            && withinRange(candidate)) {
          latest = candidate;
          offset = offsetsAfter[index];
        }
      }
    }
    return offset;
  }

  boolean containsDiscontinuity(long localMicros, long lastHistoricSecond) {
    int year = LocalTemporal.year(Math.floorDiv(
        localMicros, LocalTemporal.MICROSECONDS_PER_DAY));
    for (int candidateYear = Math.max(0, year - 1);
        candidateYear <= Math.min(10_000, year + 1);
        candidateYear++) {
      for (int index = 0; index < count; index++) {
        long second = transitionSecond(index, candidateYear);
        if (second > lastHistoricSecond
            && withinDiscontinuity(localMicros, second, index)) {
          return true;
        }
      }
    }
    return false;
  }

  long transitionSecond(int index, int year) {
    int month = Byte.toUnsignedInt(months[index]);
    int indicator = monthDays[index];
    int day = indicator < 0
        ? daysInMonth(year, month) + 1 + indicator : indicator;
    long epochDay = prolepticEpochDay(year, month, day);
    int requestedWeekDay = weekDays[index];
    if (requestedWeekDay != 0) {
      int actualWeekDay = dayOfWeek(epochDay);
      int adjustment = indicator < 0
          ? -Math.floorMod(actualWeekDay - requestedWeekDay, 7)
          : Math.floorMod(requestedWeekDay - actualWeekDay, 7);
      epochDay += adjustment;
    }
    if (midnightEndOfDay[index]) {
      epochDay++;
    }
    int definitionOffset = switch (definitions[index]) {
      case DEFINITION_WALL -> offsetsBefore[index];
      case DEFINITION_STANDARD -> standardOffsets[index];
      default -> 0;
    };
    return epochDay * SECONDS_PER_DAY + localSeconds[index] - definitionOffset;
  }

  private boolean withinDiscontinuity(
      long localMicros, long transitionSecond, int index) {
    long transition = transitionSecond * LocalTemporal.MICROSECONDS_PER_SECOND;
    long before = transition
        + offsetsBefore[index] * LocalTemporal.MICROSECONDS_PER_SECOND;
    long after = transition
        + offsetsAfter[index] * LocalTemporal.MICROSECONDS_PER_SECOND;
    return before != after
        && localMicros >= Math.min(before, after)
        && localMicros < Math.max(before, after);
  }

  private static int yearAtSecond(long second) {
    long epochDay = Math.floorDiv(second, SECONDS_PER_DAY);
    return LocalTemporal.year(epochDay);
  }

  private static long prolepticEpochDay(int year, int month, int day) {
    int adjustedYear = year - (month <= 2 ? 1 : 0);
    int era = Math.floorDiv(adjustedYear, 400);
    int yearOfEra = adjustedYear - era * 400;
    int monthPrime = month + (month > 2 ? -3 : 9);
    int dayOfYear = (153 * monthPrime + 2) / 5 + day - 1;
    int dayOfEra = yearOfEra * 365 + yearOfEra / 4
        - yearOfEra / 100 + dayOfYear;
    return era * 146_097L + dayOfEra - 719_468L;
  }

  private static int daysInMonth(int year, int month) {
    return switch (month) {
      case 2 -> leapYear(year) ? 29 : 28;
      case 4, 6, 9, 11 -> 30;
      default -> 31;
    };
  }

  private static boolean leapYear(int year) {
    return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
  }

  private static int dayOfWeek(long epochDay) {
    return Math.floorMod(epochDay + 3, 7) + 1;
  }

  private static byte definition(
      ZoneOffsetTransitionRule.TimeDefinition definition) {
    return switch (definition) {
      case UTC -> DEFINITION_UTC;
      case WALL -> DEFINITION_WALL;
      case STANDARD -> DEFINITION_STANDARD;
    };
  }

  private static boolean withinRange(long second) {
    return second >= MINIMUM_SECOND && second <= MAXIMUM_SECOND;
  }
}
