package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableSchema;

/** Checked sizing policy for the configured resident part of a paged sort run. */
final class SqlSortRunCapacity {
  private SqlSortRunCapacity() {}

  static int rows(
      int projections, boolean textRows, boolean generatedTextRows, long payloadBytes) {
    long nullBytes = ((projections + Long.SIZE - 1L) >>> 6) * Long.BYTES;
    int lanes = roundedLanes(projections);
    long bytes = 4L * Long.BYTES + 2L * Integer.BYTES + 1
        + 2L * lanes * Long.BYTES + nullBytes;
    if (textRows) bytes += TableSchema.MAXIMUM_ROW_BYTES;
    if (generatedTextRows) {
      bytes += (long) lanes * (1 + 2 * SqlProjectedRow.MAXIMUM_GENERATED_TEXT);
    }
    long rows = Math.max(1, payloadBytes / Math.max(1, bytes));
    return (int) Math.min(Integer.MAX_VALUE, rows);
  }

  static long add(long left, long right) {
    return left < 0 || right < 0 || left > Long.MAX_VALUE - right
        ? Long.MAX_VALUE : left + right;
  }

  private static int roundedLanes(int projections) {
    int lanes = 1;
    while (lanes < projections && lanes <= Integer.MAX_VALUE / 2) lanes *= 2;
    return lanes < projections ? projections : lanes;
  }
}
