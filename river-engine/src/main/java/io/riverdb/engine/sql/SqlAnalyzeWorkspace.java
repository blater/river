package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Transactionally retained planner-statistics scratch with bounded exact samples. */
final class SqlAnalyzeWorkspace {
  static final int DISTINCT_SLOTS = 128;
  private final SqlRetainedArrayAllocator allocator;
  long[] distinctValues = new long[0];
  short[] distinctCounts = new short[0];
  long[] nullCounts = new long[0];
  long[] minimumValues = new long[0];
  long[] maximumValues = new long[0];
  boolean[] minMax = new boolean[0];
  boolean[] sampled = new boolean[0];

  SqlAnalyzeWorkspace() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlAnalyzeWorkspace(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
  }

  StatusCode reserve(int columns) {
    int capacity = BoundedArrayGrowth.capacity(
        distinctCounts.length, columns, SqlShapeLimits.MAX_TABLE_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == distinctCounts.length) return StatusCode.OK;
    try {
      long[] nextDistinct = allocator.longs(capacity * DISTINCT_SLOTS);
      short[] nextCounts = allocator.shorts(capacity);
      long[] nextNulls = allocator.longs(capacity);
      long[] nextMinimum = allocator.longs(capacity);
      long[] nextMaximum = allocator.longs(capacity);
      boolean[] nextMinMax = allocator.booleans(capacity);
      boolean[] nextSampled = allocator.booleans(capacity);
      distinctValues = nextDistinct;
      distinctCounts = nextCounts;
      nullCounts = nextNulls;
      minimumValues = nextMinimum;
      maximumValues = nextMaximum;
      minMax = nextMinMax;
      sampled = nextSampled;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  long retainedBytes() {
    return (long) distinctValues.length * Long.BYTES
        + (long) distinctCounts.length * Short.BYTES
        + (long) (nullCounts.length + minimumValues.length + maximumValues.length)
            * Long.BYTES
        + minMax.length + sampled.length;
  }
}
