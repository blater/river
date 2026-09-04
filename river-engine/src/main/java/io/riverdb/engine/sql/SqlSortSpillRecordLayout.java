package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableSchema;

/** Checked canonical byte sizing shared by spill record admission and decoding. */
final class SqlSortSpillRecordLayout {
  private static final int FIXED_HEADER_LONGS = 4;

  private SqlSortSpillRecordLayout() {}

  static int fixedBytes(int projections, int nullWords, int generatedTextBytes) {
    long bytes = (2L * projections + FIXED_HEADER_LONGS + 1 + nullWords) * Long.BYTES
        + generatedTextBytes;
    return bytes < 0 || bytes > Integer.MAX_VALUE ? -1 : (int) bytes;
  }

  static int maximumRecordBytes(
      int projections, int nullWords, int generatedTextBytes, boolean textRows) {
    int fixed = fixedBytes(projections, nullWords, generatedTextBytes);
    if (fixed < 0) return -1;
    long bytes = (long) Integer.BYTES + fixed + Integer.BYTES;
    if (textRows) bytes += Integer.BYTES + TableSchema.MAXIMUM_ROW_BYTES;
    return bytes > Integer.MAX_VALUE ? -1 : (int) bytes;
  }
}
