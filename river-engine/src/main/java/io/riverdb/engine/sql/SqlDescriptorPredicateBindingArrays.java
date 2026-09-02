package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlComparison;

/** Retained parallel arrays for descriptor predicate bindings. */
final class SqlDescriptorPredicateBindingArrays {
  private final SqlRetainedArrayAllocator allocator;
  private int[] columns = new int[0];
  private int[] descriptors = new int[0];
  private int[] columnDescriptors = new int[0];
  private long[] literals = new long[0];
  private long[] literalHighs = new long[0];
  private SqlComparison[] comparisons = new SqlComparison[0];
  private int capacity;

  SqlDescriptorPredicateBindingArrays(SqlRetainedArrayAllocator arrayAllocator) {
    allocator = arrayAllocator;
  }

  StatusCode reserve(int count) {
    if (count <= capacity) return StatusCode.OK;
    try {
      int[] nextColumns = allocator.integers(count);
      int[] nextDescriptors = allocator.integers(count);
      int[] nextColumnDescriptors = allocator.integers(count);
      long[] nextLiterals = allocator.longs(count);
      long[] nextLiteralHighs = allocator.longs(count);
      SqlComparison[] nextComparisons = allocator.comparisons(count);
      columns = nextColumns;
      descriptors = nextDescriptors;
      columnDescriptors = nextColumnDescriptors;
      literals = nextLiterals;
      literalHighs = nextLiteralHighs;
      comparisons = nextComparisons;
      capacity = count;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void clear(int leaf) {
    columns[leaf] = -1;
    descriptors[leaf] = 0;
    columnDescriptors[leaf] = 0;
    literals[leaf] = 0;
    literalHighs[leaf] = 0;
    comparisons[leaf] = null;
  }

  int column(int leaf) { return columns[leaf]; }
  void column(int leaf, int value) { columns[leaf] = value; }
  int descriptor(int leaf) { return descriptors[leaf]; }
  void descriptor(int leaf, int value) { descriptors[leaf] = value; }
  int columnDescriptor(int leaf) { return columnDescriptors[leaf]; }
  void columnDescriptor(int leaf, int value) { columnDescriptors[leaf] = value; }
  long literal(int leaf) { return literals[leaf]; }
  void literal(int leaf, long value) { literals[leaf] = value; }
  long literalHigh(int leaf) { return literalHighs[leaf]; }
  void literalHigh(int leaf, long value) { literalHighs[leaf] = value; }
  SqlComparison comparison(int leaf) { return comparisons[leaf]; }
  void comparison(int leaf, SqlComparison value) { comparisons[leaf] = value; }
}
