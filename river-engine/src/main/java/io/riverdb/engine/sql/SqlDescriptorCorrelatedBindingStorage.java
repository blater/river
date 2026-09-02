package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlComparison;

/** Transactionally grown retained lanes for correlated descriptor bindings. */
final class SqlDescriptorCorrelatedBindingStorage {
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  byte[] leftKinds = new byte[0];
  byte[] rightKinds = new byte[0];
  int[] leftColumns = new int[0];
  int[] rightColumns = new int[0];
  int[] leftDescriptors = new int[0];
  int[] rightDescriptors = new int[0];
  long[] leftHighs = new long[0];
  long[] rightHighs = new long[0];
  long[] leftValues = new long[0];
  long[] rightValues = new long[0];
  SqlComparison[] comparisons = new SqlComparison[0];
  private int capacity;

  SqlDescriptorCorrelatedBindingStorage(SqlRetainedArrayAllocator arrayAllocator) {
    this(arrayAllocator, null);
  }

  SqlDescriptorCorrelatedBindingStorage(
      SqlRetainedArrayAllocator arrayAllocator, SqlSessionShapeBudget shapeBudget) {
    allocator = arrayAllocator;
    budget = shapeBudget;
  }

  StatusCode reserve(int count) {
    if (count <= capacity) return StatusCode.OK;
    long charged = (long) (count - capacity)
        * (2 + 4 * Integer.BYTES + 4 * Long.BYTES + Long.BYTES);
    StatusCode admitted = budget == null || charged == 0
        ? StatusCode.OK : budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      byte[] nextLeftKinds = allocator.bytes(count);
      byte[] nextRightKinds = allocator.bytes(count);
      int[] nextLeftColumns = allocator.integers(count);
      int[] nextRightColumns = allocator.integers(count);
      int[] nextLeftDescriptors = allocator.integers(count);
      int[] nextRightDescriptors = allocator.integers(count);
      long[] nextLeftHighs = allocator.longs(count);
      long[] nextRightHighs = allocator.longs(count);
      long[] nextLeftValues = allocator.longs(count);
      long[] nextRightValues = allocator.longs(count);
      SqlComparison[] nextComparisons = allocator.comparisons(count);
      leftKinds = nextLeftKinds;
      rightKinds = nextRightKinds;
      leftColumns = nextLeftColumns;
      rightColumns = nextRightColumns;
      leftDescriptors = nextLeftDescriptors;
      rightDescriptors = nextRightDescriptors;
      leftHighs = nextLeftHighs;
      rightHighs = nextRightHighs;
      leftValues = nextLeftValues;
      rightValues = nextRightValues;
      comparisons = nextComparisons;
      capacity = count;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      if (budget != null && charged > 0) budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
