package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransitionRule;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SqlTemporalRecurringRulesTest {
  @Test
  void reproducesUtcStandardWallAndMonthDayDefinitions() {
    ZoneOffsetTransitionRule utc = rule(
        Month.MARCH, 8, DayOfWeek.SUNDAY, LocalTime.of(1, 0), false,
        ZoneOffsetTransitionRule.TimeDefinition.UTC,
        ZoneOffset.ofHours(1), ZoneOffset.ofHours(1), ZoneOffset.ofHours(2));
    ZoneOffsetTransitionRule standard = rule(
        Month.JUNE, 15, null, LocalTime.of(2, 30), false,
        ZoneOffsetTransitionRule.TimeDefinition.STANDARD,
        ZoneOffset.ofHours(3), ZoneOffset.ofHours(4), ZoneOffset.ofHours(5));
    ZoneOffsetTransitionRule wall = rule(
        Month.OCTOBER, -1, DayOfWeek.SUNDAY, LocalTime.of(2, 0), false,
        ZoneOffsetTransitionRule.TimeDefinition.WALL,
        ZoneOffset.ofHours(-5), ZoneOffset.ofHours(-4), ZoneOffset.ofHours(-5));
    ZoneOffsetTransitionRule endOfDay = rule(
        Month.DECEMBER, 31, null, LocalTime.MIDNIGHT, true,
        ZoneOffsetTransitionRule.TimeDefinition.UTC,
        ZoneOffset.UTC, ZoneOffset.UTC, ZoneOffset.ofHours(1));
    List<ZoneOffsetTransitionRule> source =
        List.of(utc, standard, wall, endOfDay);
    SqlTemporalRecurringRules compact = new SqlTemporalRecurringRules();

    assertEquals(StatusCode.OK, compact.prepare(source));
    assertEquals(source.size(), compact.count());
    for (int index = 0; index < source.size(); index++) {
      assertEquals(
          source.get(index).createTransition(2024).toEpochSecond(),
          compact.transitionSecond(index, 2024));
    }
  }

  @Test
  void includesAdjacentRuleYearsAtBothUtcDomainEdges() {
    ZoneOffsetTransitionRule upper = rule(
        Month.JANUARY, 1, null, LocalTime.MIDNIGHT, false,
        ZoneOffsetTransitionRule.TimeDefinition.WALL,
        ZoneOffset.ofHours(14), ZoneOffset.ofHours(14), ZoneOffset.UTC);
    ZoneOffsetTransitionRule restorePositive = rule(
        Month.JULY, 1, null, LocalTime.MIDNIGHT, false,
        ZoneOffsetTransitionRule.TimeDefinition.WALL,
        ZoneOffset.ofHours(14), ZoneOffset.UTC, ZoneOffset.ofHours(14));
    SqlTemporalRecurringRules compact = new SqlTemporalRecurringRules();
    assertEquals(
        StatusCode.OK, compact.prepare(List.of(upper, restorePositive)));
    long upperTransition = upper.createTransition(10_000).toEpochSecond();
    assertEquals(upperTransition, compact.transitionSecond(0, 10_000));
    assertEquals(14 * 3_600,
        compact.offsetAt(upperTransition - 1, Long.MIN_VALUE, 14 * 3_600));
    assertEquals(0,
        compact.offsetAt(upperTransition, Long.MIN_VALUE, 14 * 3_600));
    long upperOverlapStart =
        upperTransition * LocalTemporal.MICROSECONDS_PER_SECOND;
    assertFalse(compact.containsDiscontinuity(
        upperOverlapStart - 1, Long.MIN_VALUE));
    assertTrue(compact.containsDiscontinuity(
        upperOverlapStart, Long.MIN_VALUE));
    assertTrue(compact.containsDiscontinuity(
        LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS, Long.MIN_VALUE));

    ZoneOffsetTransitionRule lower = rule(
        Month.DECEMBER, 31, null, LocalTime.MIDNIGHT, true,
        ZoneOffsetTransitionRule.TimeDefinition.WALL,
        ZoneOffset.ofHours(-14), ZoneOffset.ofHours(-14), ZoneOffset.UTC);
    ZoneOffsetTransitionRule restoreNegative = rule(
        Month.JULY, 1, null, LocalTime.MIDNIGHT, false,
        ZoneOffsetTransitionRule.TimeDefinition.WALL,
        ZoneOffset.ofHours(-14), ZoneOffset.UTC, ZoneOffset.ofHours(-14));
    assertEquals(
        StatusCode.OK, compact.prepare(List.of(lower, restoreNegative)));
    long lowerTransition = lower.createTransition(0).toEpochSecond();
    assertEquals(lowerTransition, compact.transitionSecond(0, 0));
    assertEquals(-14 * 3_600, compact.initialOffset(123));
    assertEquals(-14 * 3_600,
        compact.offsetAt(lowerTransition - 1, Long.MIN_VALUE, -14 * 3_600));
    assertEquals(0,
        compact.offsetAt(lowerTransition, Long.MIN_VALUE, -14 * 3_600));
    long lowerGapEnd =
        lowerTransition * LocalTemporal.MICROSECONDS_PER_SECOND;
    assertTrue(compact.containsDiscontinuity(
        LocalTemporal.MINIMUM_TIMESTAMP_MICROSECONDS, Long.MIN_VALUE));
    assertTrue(compact.containsDiscontinuity(lowerGapEnd - 1, Long.MIN_VALUE));
    assertFalse(compact.containsDiscontinuity(lowerGapEnd, Long.MIN_VALUE));

    ZoneOffsetTransitionRule aboveUtcDomain = rule(
        Month.DECEMBER, 31, null, LocalTime.NOON, false,
        ZoneOffsetTransitionRule.TimeDefinition.WALL,
        ZoneOffset.ofHours(-14), ZoneOffset.ofHours(-14), ZoneOffset.UTC);
    assertEquals(
        StatusCode.OK,
        compact.prepare(List.of(aboveUtcDomain, restoreNegative)));
    long aboveTransition = aboveUtcDomain.createTransition(9_999).toEpochSecond();
    assertTrue(aboveTransition > Math.floorDiv(
        LocalTemporal.MAXIMUM_INSTANT_MICROSECONDS,
        LocalTemporal.MICROSECONDS_PER_SECOND));
    long upperGapStart = LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS
        - 12 * 3_600L * LocalTemporal.MICROSECONDS_PER_SECOND + 1;
    assertFalse(compact.containsDiscontinuity(
        upperGapStart - 1, Long.MIN_VALUE));
    assertTrue(compact.containsDiscontinuity(
        upperGapStart, Long.MIN_VALUE));
    assertTrue(compact.containsDiscontinuity(
        LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS, Long.MIN_VALUE));

    ZoneOffsetTransitionRule belowUtcDomain = rule(
        Month.JANUARY, 1, null, LocalTime.NOON, false,
        ZoneOffsetTransitionRule.TimeDefinition.WALL,
        ZoneOffset.ofHours(14), ZoneOffset.ofHours(14), ZoneOffset.UTC);
    assertEquals(
        StatusCode.OK,
        compact.prepare(List.of(belowUtcDomain, restorePositive)));
    long belowTransition = belowUtcDomain.createTransition(1).toEpochSecond();
    assertTrue(belowTransition < Math.floorDiv(
        LocalTemporal.MINIMUM_INSTANT_MICROSECONDS,
        LocalTemporal.MICROSECONDS_PER_SECOND));
    long lowerOverlapEnd = LocalTemporal.MINIMUM_TIMESTAMP_MICROSECONDS
        + 12 * 3_600L * LocalTemporal.MICROSECONDS_PER_SECOND;
    assertTrue(compact.containsDiscontinuity(
        LocalTemporal.MINIMUM_TIMESTAMP_MICROSECONDS, Long.MIN_VALUE));
    assertTrue(compact.containsDiscontinuity(
        lowerOverlapEnd - 1, Long.MIN_VALUE));
    assertFalse(compact.containsDiscontinuity(
        lowerOverlapEnd, Long.MIN_VALUE));
  }

  private static ZoneOffsetTransitionRule rule(
      Month month,
      int monthDay,
      DayOfWeek weekDay,
      LocalTime time,
      boolean endOfDay,
      ZoneOffsetTransitionRule.TimeDefinition definition,
      ZoneOffset standard,
      ZoneOffset before,
      ZoneOffset after) {
    return ZoneOffsetTransitionRule.of(
        month, monthDay, weekDay, time, endOfDay,
        definition, standard, before, after);
  }
}
