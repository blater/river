package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Transactional shape and generated-text admission for one projected row. */
final class SqlProjectedRowAdmission {
  private SqlProjectedRowAdmission() { }

  static StatusCode reserve(SqlProjectedRow row, int required) {
    if (required < 0 || required > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int capacity = BoundedArrayGrowth.capacity(
        row.values.length, required, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      long[] highs = capacity == row.highs.length
          ? row.highs : row.allocator.longs(capacity);
      long[] values = capacity == row.values.length
          ? row.values : row.allocator.longs(capacity);
      char[][] text = capacity == row.text.length
          ? row.text : row.allocator.characterLanes(capacity);
      int[] lengths = capacity == row.textLengths.length
          ? row.textLengths : row.allocator.integers(capacity);
      if (capacity != row.values.length) {
        System.arraycopy(row.highs, 0, highs, 0, row.count());
        System.arraycopy(row.values, 0, values, 0, row.count());
        System.arraycopy(row.text, 0, text, 0, row.count());
        System.arraycopy(row.textLengths, 0, lengths, 0, row.count());
      }
      StatusCode status = row.nulls.reserve(required, SqlShapeLimits.MAX_RESULT_COLUMNS);
      if (!status.isOk()) return status;
      row.highs = highs;
      row.values = values;
      row.text = text;
      row.textLengths = lengths;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  static StatusCode prepareText(SqlProjectedRow row, int projection) {
    if (projection < 0 || projection >= row.values.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (row.text[projection] != null) return StatusCode.OK;
    try {
      char[] characters = row.allocator.characters(SqlProjectedRow.MAXIMUM_GENERATED_TEXT);
      row.text[projection] = characters;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
