package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Actual-count retained primitive storage for non-composite physical plan steps. */
final class SqlPhysicalPlanSteps {
  private final SqlSessionShapeBudget budget;
  private long[] operators = new long[0];
  private long[] details = new long[0];
  private int count;

  SqlPhysicalPlanSteps(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode add(long operator, long detail) {
    if (count == SqlShapeLimits.MAX_PLAN_STEPS) return StatusCode.RESOURCE_EXHAUSTED;
    if (count == operators.length) {
      int capacity = BoundedArrayGrowth.capacity(
          operators.length, count + 1, SqlShapeLimits.MAX_PLAN_STEPS, 8);
      long charged = (capacity - operators.length) * 2L * Long.BYTES;
      StatusCode admission = budget.reserve(charged);
      if (!admission.isOk()) return admission;
      try {
        long[] nextOperators = new long[capacity];
        long[] nextDetails = new long[capacity];
        System.arraycopy(operators, 0, nextOperators, 0, count);
        System.arraycopy(details, 0, nextDetails, 0, count);
        operators = nextOperators;
        details = nextDetails;
      } catch (OutOfMemoryError error) {
        budget.rollback(charged);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    operators[count] = operator;
    details[count] = detail;
    count++;
    return StatusCode.OK;
  }

  void reset() { count = 0; }
  int count() { return count; }
  long operator(int index) { return operators[index]; }
  long detail(int index) { return details[index]; }
}
