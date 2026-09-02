package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Reusable actual-count sort tuple columns, descriptors, and directions. */
final class SqlBlockSortShape {
  private static final int BYTES_PER_PART = Integer.BYTES * 2 + 1;
  private static final int WARM_PARTS = 64;
  private static final int[] EMPTY_INTS = new int[0];
  private static final boolean[] EMPTY_BOOLEANS = new boolean[0];
  private final SqlSessionShapeBudget budget;
  private final SqlRetainedArrayAllocator allocator;
  private int[] columns = new int[0];
  private int[] descriptors = new int[0];
  private boolean[] descending = new boolean[0];
  private int count;

  SqlBlockSortShape(SqlSessionShapeBudget shapeBudget) {
    this(shapeBudget, SqlRetainedArrayAllocator.STANDARD);
  }

  SqlBlockSortShape(
      SqlSessionShapeBudget shapeBudget, SqlRetainedArrayAllocator retainedAllocator) {
    budget = shapeBudget;
    allocator = retainedAllocator;
  }

  boolean set(
      SqlBlockSchema schema, int[] sourceColumns, boolean[] directions, int required) {
    if (schema == null || sourceColumns == null || directions == null
        || required < 0 || sourceColumns.length < required || directions.length < required) {
      return false;
    }
    for (int part = 0; part < required; part++) {
      if (sourceColumns[part] < 0 || sourceColumns[part] >= schema.count()) return false;
    }
    int capacity = BoundedArrayGrowth.capacity(
        columns.length, required, SqlShapeLimits.MAX_ORDER_BY_EXPRESSIONS, 8);
    if (capacity < 0 || !grow(capacity)) return false;
    for (int part = 0; part < required; part++) {
      int column = sourceColumns[part];
      columns[part] = column;
      descriptors[part] = schema.descriptor(column);
      descending[part] = directions[part];
    }
    count = required;
    return true;
  }

  void clear() { count = 0; }
  void close() {
    count = 0;
    if (columns.length <= WARM_PARTS) return;
    int released = columns.length;
    columns = EMPTY_INTS;
    descriptors = EMPTY_INTS;
    descending = EMPTY_BOOLEANS;
    budget.rollback((long) released * BYTES_PER_PART);
  }
  int count() { return count; }
  int column(int part) { return columns[part]; }
  int descriptor(int part) { return descriptors[part]; }
  boolean descending(int part) { return descending[part]; }

  private boolean grow(int capacity) {
    if (capacity == columns.length) return true;
    long charged = (long) capacity * BYTES_PER_PART;
    StatusCode status = budget.reserve(charged);
    if (!status.isOk()) return false;
    try {
      int[] nextColumns = allocator.integers(capacity);
      int[] nextDescriptors = allocator.integers(capacity);
      boolean[] nextDescending = allocator.booleans(capacity);
      int previous = columns.length;
      columns = nextColumns;
      descriptors = nextDescriptors;
      descending = nextDescending;
      if (previous > 0) budget.rollback((long) previous * BYTES_PER_PART);
      return true;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return false;
    }
  }
}
