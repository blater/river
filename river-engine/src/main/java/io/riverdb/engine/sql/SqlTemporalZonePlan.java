package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;

/** Session-owned primitive historic transitions and compact recurring rules. */
final class SqlTemporalZonePlan {
  private static final int MAXIMUM_HISTORIC_TRANSITIONS = 4_096;
  private static final long[] NO_TRANSITION_SECONDS = new long[0];
  private static final int[] NO_OFFSETS = new int[0];
  private static final long MINIMUM_SECOND = Math.floorDiv(
      LocalTemporal.MINIMUM_INSTANT_MICROSECONDS,
      LocalTemporal.MICROSECONDS_PER_SECOND);
  private static final long MAXIMUM_SECOND = Math.floorDiv(
      LocalTemporal.MAXIMUM_INSTANT_MICROSECONDS,
      LocalTemporal.MICROSECONDS_PER_SECOND);
  private static final long MAXIMUM_OFFSET_MICROSECONDS =
      LocalTemporal.MAXIMUM_OFFSET_MINUTES * 60L
          * LocalTemporal.MICROSECONDS_PER_SECOND;

  private final SqlTemporalRecurringRules recurring =
      new SqlTemporalRecurringRules();
  private long[] transitionSeconds = NO_TRANSITION_SECONDS;
  private int[] offsetsBefore = NO_OFFSETS;
  private int[] offsetsAfter = NO_OFFSETS;
  private int transitionCount;
  private int initialOffsetSeconds;
  private long lastHistoricSecond = Long.MIN_VALUE;
  private boolean prepared;

  StatusCode prepare(ZoneId zone) {
    clear();
    if (zone == null) {
      return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
    }
    ZoneRules rules = zone.getRules();
    List<ZoneOffsetTransition> historic = rules.getTransitions();
    int count = countHistoric(historic);
    if (count > MAXIMUM_HISTORIC_TRANSITIONS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = recurring.prepare(rules.getTransitionRules());
    if (!status.isOk()) {
      recurring.reset();
      return status;
    }
    long[] seconds = count == 0 ? NO_TRANSITION_SECONDS : new long[count];
    int[] before = count == 0 ? NO_OFFSETS : new int[count];
    int[] after = count == 0 ? NO_OFFSETS : new int[count];
    int position = fillHistoric(historic, seconds, before, after);
    if (position != count || !strictlyOrdered(seconds)) {
      recurring.reset();
      return StatusCode.INVARIANT_BROKEN;
    }
    transitionSeconds = seconds;
    offsetsBefore = before;
    offsetsAfter = after;
    transitionCount = count;
    lastHistoricSecond = count == 0 ? Long.MIN_VALUE : seconds[count - 1];
    int fallback = rules.getOffset(Instant.EPOCH).getTotalSeconds();
    initialOffsetSeconds = count == 0
        ? recurring.initialOffset(fallback) : before[0];
    prepared = true;
    return StatusCode.OK;
  }

  StatusCode localToInstant(
      long localMicros, SqlTemporalContext.LongResult result) {
    if (!prepared || result == null
        || !LocalTemporal.validTimestamp(localMicros, 6)) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    if (historicDiscontinuity(localMicros)
        || recurring.containsDiscontinuity(localMicros, lastHistoricSecond)) {
      return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
    }
    StatusCode status = offsetAtInstant(localMicros, result);
    if (!status.isOk()) {
      return status;
    }
    long candidate = subtractOffset(localMicros, result.value);
    if (candidate == Long.MIN_VALUE) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    long guessedOffset = result.value;
    status = offsetAtInstant(candidate, result);
    if (!status.isOk()) {
      return status;
    }
    if (result.value != guessedOffset) {
      candidate = correctedCandidate(localMicros, candidate, result);
      if (candidate == Long.MIN_VALUE) {
        return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
      }
    }
    result.value = candidate;
    return LocalTemporal.validInstant(candidate, 6)
        ? StatusCode.OK : StatusCode.DATETIME_FIELD_OVERFLOW;
  }

  StatusCode instantToLocal(
      long instantMicros, SqlTemporalContext.LongResult result) {
    if (!prepared || result == null
        || !LocalTemporal.validInstant(instantMicros, 6)) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    StatusCode status = offsetAtInstant(instantMicros, result);
    if (!status.isOk()) {
      return status;
    }
    result.value = instantMicros
        + result.value * LocalTemporal.MICROSECONDS_PER_SECOND;
    return LocalTemporal.validTimestamp(result.value, 6)
        ? StatusCode.OK : StatusCode.DATETIME_FIELD_OVERFLOW;
  }

  StatusCode offsetAtInstant(
      long instantMicros, SqlTemporalContext.LongResult result) {
    if (!prepared || result == null
        || !LocalTemporal.validInstant(instantMicros, 6)) {
      return StatusCode.DATETIME_FIELD_OVERFLOW;
    }
    long second = Math.floorDiv(
        instantMicros, LocalTemporal.MICROSECONDS_PER_SECOND);
    int offset = historicOffset(second);
    if (second > lastHistoricSecond && recurring.count() > 0) {
      offset = recurring.offsetAt(second, lastHistoricSecond, offset);
    }
    if (!validOffset(offset)) {
      return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
    }
    result.value = offset;
    return StatusCode.OK;
  }

  int transitionCount() {
    return transitionCount;
  }

  int recurringRuleCount() {
    return recurring.count();
  }

  private void clear() {
    transitionSeconds = NO_TRANSITION_SECONDS;
    offsetsBefore = NO_OFFSETS;
    offsetsAfter = NO_OFFSETS;
    transitionCount = 0;
    initialOffsetSeconds = 0;
    lastHistoricSecond = Long.MIN_VALUE;
    recurring.reset();
    prepared = false;
  }

  void reset() { clear(); }

  private long correctedCandidate(
      long localMicros,
      long candidate,
      SqlTemporalContext.LongResult result) {
    long correctedOffset = result.value;
    candidate = subtractOffset(localMicros, correctedOffset);
    if (candidate == Long.MIN_VALUE
        || !offsetAtInstant(candidate, result).isOk()
        || result.value != correctedOffset) {
      return Long.MIN_VALUE;
    }
    return candidate;
  }

  private int historicOffset(long second) {
    int low = 0;
    int high = transitionCount;
    while (low < high) {
      int middle = low + (high - low) / 2;
      if (transitionSeconds[middle] <= second) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low == 0 ? initialOffsetSeconds : offsetsAfter[low - 1];
  }

  private boolean historicDiscontinuity(long localMicros) {
    int nearby = firstHistoricAtOrAfter(Math.floorDiv(
        localMicros - MAXIMUM_OFFSET_MICROSECONDS,
        LocalTemporal.MICROSECONDS_PER_SECOND));
    long upperSecond = Math.floorDiv(
        localMicros + MAXIMUM_OFFSET_MICROSECONDS,
        LocalTemporal.MICROSECONDS_PER_SECOND);
    for (int index = nearby;
        index < transitionCount && transitionSeconds[index] <= upperSecond;
        index++) {
      long transition = transitionSeconds[index]
          * LocalTemporal.MICROSECONDS_PER_SECOND;
      long before = transition
          + offsetsBefore[index] * LocalTemporal.MICROSECONDS_PER_SECOND;
      long after = transition
          + offsetsAfter[index] * LocalTemporal.MICROSECONDS_PER_SECOND;
      if (before != after
          && localMicros >= Math.min(before, after)
          && localMicros < Math.max(before, after)) {
        return true;
      }
    }
    return false;
  }

  private int firstHistoricAtOrAfter(long second) {
    int low = 0;
    int high = transitionCount;
    while (low < high) {
      int middle = low + (high - low) / 2;
      if (transitionSeconds[middle] < second) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  private static long subtractOffset(long localMicros, long offsetSeconds) {
    return localMicros
        - offsetSeconds * LocalTemporal.MICROSECONDS_PER_SECOND;
  }

  private static int countHistoric(List<ZoneOffsetTransition> transitions) {
    int count = 0;
    for (ZoneOffsetTransition transition : transitions) {
      if (withinRange(transition.toEpochSecond())) {
        count++;
      }
    }
    return count;
  }

  private static int fillHistoric(
      List<ZoneOffsetTransition> transitions,
      long[] seconds,
      int[] before,
      int[] after) {
    int position = 0;
    for (ZoneOffsetTransition transition : transitions) {
      if (withinRange(transition.toEpochSecond())) {
        seconds[position] = transition.toEpochSecond();
        before[position] = transition.getOffsetBefore().getTotalSeconds();
        after[position] = transition.getOffsetAfter().getTotalSeconds();
        position++;
      }
    }
    return position;
  }

  private static boolean validOffset(int offset) {
    return offset % 60 == 0
        && Math.abs((long) offset)
            <= LocalTemporal.MAXIMUM_OFFSET_MINUTES * 60L;
  }

  private static boolean withinRange(long second) {
    return second >= MINIMUM_SECOND && second <= MAXIMUM_SECOND;
  }

  private static boolean strictlyOrdered(long[] values) {
    for (int index = 1; index < values.length; index++) {
      if (values[index - 1] >= values[index]) {
        return false;
      }
    }
    return true;
  }
}
