package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import java.lang.management.ManagementFactory;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class SqlTemporalZonePlanTest {
  private final SqlTemporalZonePlan plan = new SqlTemporalZonePlan();
  private final SqlTemporalContext.LongResult result =
      new SqlTemporalContext.LongResult();
  private final LocalTemporal.Value parsed = new LocalTemporal.Value();

  @Test
  void resolvesHistoricAndFirstRecurringLondonTransitionsCompactly() {
    assertEquals(StatusCode.OK, plan.prepare(ZoneId.of("Europe/London")));
    assertTrue(plan.transitionCount() < 1_000);
    assertEquals(2, plan.recurringRuleCount());

    assertLocalToInstant(
        "1998-01-15 12:00:00", "1998-01-15 12:00:00+00:00");
    assertLocalToInstant(
        "1998-07-15 12:00:00", "1998-07-15 11:00:00+00:00");
    assertInstantToLocal(
        "1998-07-15 11:00:00+00:00", "1998-07-15 12:00:00");

    assertGap("1998-03-29 01:00:00", "1998-03-29 01:59:59.999999");
    assertOverlap("1998-10-25 01:00:00", "1998-10-25 01:59:59.999999");
    assertLocalToInstant(
        "1998-03-29 00:59:59.999999",
        "1998-03-29 00:59:59.999999+00:00");
    assertLocalToInstant(
        "1998-03-29 02:00:00", "1998-03-29 01:00:00+00:00");
    assertInstantToLocal(
        "1998-03-29 00:59:59.999999+00:00",
        "1998-03-29 00:59:59.999999");
    assertInstantToLocal(
        "1998-03-29 01:00:00+00:00", "1998-03-29 02:00:00");
    assertLocalToInstant(
        "1998-10-25 00:59:59.999999",
        "1998-10-24 23:59:59.999999+00:00");
    assertLocalToInstant(
        "1998-10-25 02:00:00", "1998-10-25 02:00:00+00:00");
    assertInstantToLocal(
        "1998-10-25 00:59:59.999999+00:00",
        "1998-10-25 01:59:59.999999");
    assertInstantToLocal(
        "1998-10-25 01:00:00+00:00", "1998-10-25 01:00:00");
  }

  @Test
  void resolvesWallRulesAndSouthernTransitionOrder() {
    assertEquals(StatusCode.OK, plan.prepare(ZoneId.of("America/New_York")));
    assertLocalToInstant(
        "2024-01-15 12:00:00", "2024-01-15 17:00:00+00:00");
    assertLocalToInstant(
        "2024-07-15 12:00:00", "2024-07-15 16:00:00+00:00");
    assertGap("2024-03-10 02:00:00", "2024-03-10 02:59:59.999999");
    assertOverlap("2024-11-03 01:00:00", "2024-11-03 01:59:59.999999");

    assertEquals(StatusCode.OK, plan.prepare(ZoneId.of("Australia/Sydney")));
    assertLocalToInstant(
        "2024-01-15 12:00:00", "2024-01-15 01:00:00+00:00");
    assertLocalToInstant(
        "2024-07-15 12:00:00", "2024-07-15 02:00:00+00:00");
    assertOverlap("2024-04-07 02:00:00", "2024-04-07 02:59:59.999999");
    assertGap("2024-10-06 02:00:00", "2024-10-06 02:59:59.999999");
  }

  @Test
  void enforcesHistoricSecondsAndFullUtcDomain() {
    assertEquals(StatusCode.OK, plan.prepare(ZoneId.of("Europe/Paris")));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        plan.offsetAtInstant(instant("1900-01-01 00:00:00+00:00"), result));

    assertEquals(StatusCode.OK, plan.prepare(ZoneOffset.UTC));
    assertEquals(0, plan.transitionCount());
    assertEquals(0, plan.recurringRuleCount());
    assertEquals(StatusCode.OK,
        plan.localToInstant(LocalTemporal.MINIMUM_TIMESTAMP_MICROSECONDS, result));
    assertEquals(LocalTemporal.MINIMUM_INSTANT_MICROSECONDS, result.value);
    assertEquals(StatusCode.OK,
        plan.instantToLocal(LocalTemporal.MAXIMUM_INSTANT_MICROSECONDS, result));
    assertEquals(LocalTemporal.MAXIMUM_TIMESTAMP_MICROSECONDS, result.value);

    assertEquals(StatusCode.OK, plan.prepare(ZoneOffset.ofHours(14)));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        plan.localToInstant(LocalTemporal.MINIMUM_TIMESTAMP_MICROSECONDS, result));
    assertEquals(StatusCode.OK, plan.prepare(ZoneOffset.ofHours(-14)));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        plan.instantToLocal(LocalTemporal.MINIMUM_INSTANT_MICROSECONDS, result));

    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, plan.prepare(null));
    assertEquals(0, plan.transitionCount());
    assertEquals(0, plan.recurringRuleCount());
  }

  @Test
  void warmedRecurringConversionsDoNotAllocatePerValue() {
    java.lang.management.ThreadMXBean standard =
        ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standard instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standard;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    assertEquals(StatusCode.OK, plan.prepare(ZoneId.of("Europe/London")));
    long local = local("2024-07-15 12:00:00");
    long instant = instant("2024-07-15 11:00:00+00:00");
    int guard = 0;
    for (int index = 0; index < 10_000; index++) {
      guard += plan.localToInstant(local, result).ordinal();
      guard += plan.instantToLocal(instant, result).ordinal();
    }
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    for (int index = 0; index < 10_000; index++) {
      guard += plan.localToInstant(local, result).ordinal();
      guard += plan.instantToLocal(instant, result).ordinal();
    }
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertEquals(0, guard);
    assertTrue(allocated <= 256,
        "warmed temporal zone conversions allocated bytes: " + allocated);
  }

  private void assertGap(String first, String last) {
    assertInvalidLocal(first);
    assertInvalidLocal(last);
  }

  private void assertOverlap(String first, String last) {
    assertInvalidLocal(first);
    assertInvalidLocal(last);
  }

  private void assertInvalidLocal(String text) {
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        plan.localToInstant(local(text), result));
  }

  private void assertLocalToInstant(String local, String expected) {
    assertEquals(StatusCode.OK, plan.localToInstant(local(local), result));
    assertEquals(instant(expected), result.value);
  }

  private void assertInstantToLocal(String instant, String expected) {
    assertEquals(StatusCode.OK, plan.instantToLocal(instant(instant), result));
    assertEquals(local(expected), result.value);
  }

  private long local(String text) {
    assertEquals(
        StatusCode.OK,
        LocalTemporal.parseTimestampStatus(text, 0, text.length(), parsed));
    return parsed.value;
  }

  private long instant(String text) {
    assertEquals(
        StatusCode.OK,
        LocalTemporal.parseTimestampWithOffsetStatus(
            text, 0, text.length(), parsed));
    return parsed.value;
  }
}
