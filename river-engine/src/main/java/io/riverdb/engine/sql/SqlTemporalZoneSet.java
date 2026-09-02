package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;

/** Actual-shape, budgeted, high-water owner for lazily prepared zone plans. */
final class SqlTemporalZoneSet {
  private static final long REFERENCE_BYTES = 8;
  private static final long PLAN_RETAINED_BYTES = 768;
  private final SqlSessionShapeBudget budget;
  private final int maximum;
  private SqlTemporalZonePlan[] zones = new SqlTemporalZonePlan[0];
  private int count;

  SqlTemporalZoneSet(SqlSessionShapeBudget shapeBudget, int maximumCount) {
    budget = shapeBudget;
    maximum = maximumCount;
  }

  StatusCode reserve(int required) {
    if (required < 0 || required > maximum) return StatusCode.RESOURCE_EXHAUSTED;
    if (required <= zones.length) {
      count = required;
      return StatusCode.OK;
    }
    int capacity = BoundedArrayGrowth.capacity(zones.length, required, maximum, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long charged = (long) (capacity - zones.length) * REFERENCE_BYTES;
    StatusCode admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      SqlTemporalZonePlan[] next = new SqlTemporalZonePlan[capacity];
      System.arraycopy(zones, 0, next, 0, zones.length);
      zones = next;
      count = required;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode ensure(int index) {
    if (index < 0 || index >= count) return StatusCode.CONFLICT;
    if (zones[index] != null) return StatusCode.OK;
    StatusCode admitted = budget.reserve(PLAN_RETAINED_BYTES);
    if (!admitted.isOk()) return admitted;
    try {
      zones[index] = new SqlTemporalZonePlan(budget);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      budget.rollback(PLAN_RETAINED_BYTES);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  SqlTemporalZonePlan get(int index) {
    return index < 0 || index >= count ? null : zones[index];
  }

  int retainedPlanCount() {
    int retained = 0;
    for (SqlTemporalZonePlan zone : zones) if (zone != null) retained++;
    return retained;
  }

  void reset() {
    for (int index = 0; index < count; index++) {
      if (zones[index] != null) zones[index].reset();
    }
    count = 0;
  }
}
