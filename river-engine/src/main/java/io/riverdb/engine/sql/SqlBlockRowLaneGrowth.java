package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Transactional retained growth for one block row's primitive lane families. */
final class SqlBlockRowLaneGrowth {
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  private long[] values;
  private long[] highValues;

  SqlBlockRowLaneGrowth(
      SqlRetainedArrayAllocator retainedAllocator, SqlSessionShapeBudget shapeBudget) {
    allocator = retainedAllocator;
    budget = shapeBudget;
  }

  StatusCode grow(
      long[] currentValues,
      long[] currentHighValues,
      SqlBlockRowTextStorage text,
      int required) {
    values = currentValues;
    highValues = currentHighValues;
    if (required <= currentValues.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        currentValues.length, required, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long charged = (long) (capacity - currentValues.length) * 32;
    StatusCode status = budget == null ? StatusCode.OK : budget.reserve(charged);
    if (!status.isOk()) return status;
    try {
      long[] nextValues = allocator.longs(capacity);
      long[] nextHighValues = allocator.longs(capacity);
      char[][] nextText = allocator.characterLanes(capacity);
      short[] nextLengths = allocator.shorts(capacity);
      System.arraycopy(currentValues, 0, nextValues, 0, currentValues.length);
      System.arraycopy(currentHighValues, 0, nextHighValues, 0, currentHighValues.length);
      System.arraycopy(text.lanes(), 0, nextText, 0, text.lanes().length);
      System.arraycopy(text.lengths(), 0, nextLengths, 0, text.lengths().length);
      values = nextValues;
      highValues = nextHighValues;
      text.publish(nextText, nextLengths);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      if (budget != null) budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  long[] values() { return values; }
  long[] highValues() { return highValues; }
}
