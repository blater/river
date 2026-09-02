package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Actual-count fixed mutation-expression results retained for INSERT execution. */
final class SqlMutationEvaluationState {
  private static final long BYTES_PER_EXPRESSION = 13;
  private final SqlSessionShapeBudget budget;
  private long[] values = new long[0];
  private int[] descriptors = new int[0];
  private boolean[] nulls = new boolean[0];
  private int count;

  SqlMutationEvaluationState() {
    this(new SqlSessionShapeBudget(null));
  }

  SqlMutationEvaluationState(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode reserve(int expressions) {
    if (expressions < 0 || expressions > SqlShapeLimits.MAX_UPDATE_ASSIGNMENTS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (expressions <= values.length) {
      count = expressions;
      return StatusCode.OK;
    }
    int capacity = BoundedArrayGrowth.capacity(
        values.length, expressions, SqlShapeLimits.MAX_UPDATE_ASSIGNMENTS, 8);
    long charged = (capacity - values.length) * BYTES_PER_EXPRESSION;
    StatusCode admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      long[] nextValues = new long[capacity];
      int[] nextDescriptors = new int[capacity];
      boolean[] nextNulls = new boolean[capacity];
      System.arraycopy(values, 0, nextValues, 0, values.length);
      System.arraycopy(descriptors, 0, nextDescriptors, 0, descriptors.length);
      System.arraycopy(nulls, 0, nextNulls, 0, nulls.length);
      values = nextValues;
      descriptors = nextDescriptors;
      nulls = nextNulls;
      count = expressions;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void set(int expression, long value, int descriptor, boolean nullValue) {
    values[expression] = value;
    descriptors[expression] = descriptor;
    nulls[expression] = nullValue;
  }

  long value(int expression) { return values[expression]; }
  int descriptor(int expression) { return descriptors[expression]; }
  boolean isNull(int expression) { return nulls[expression]; }

  void reset() {
    for (int index = 0; index < count; index++) {
      values[index] = 0;
      descriptors[index] = 0;
      nulls[index] = false;
    }
    count = 0;
  }
}
