package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Retained descriptors and direct result admission for one block-pipeline output. */
final class SqlBlockOutputShape {
  private int[] descriptors = new int[0];
  private int count;

  StatusCode prepare(SqlBlockSchema schema) {
    return prepare(schema, schema == null ? -1 : schema.count());
  }

  StatusCode prepare(SqlBlockSchema schema, int visibleColumns) {
    if (schema == null || !schema.status().isOk()) return StatusCode.CONFLICT;
    if (visibleColumns < 0 || visibleColumns > schema.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int columns = visibleColumns;
    if (columns > descriptors.length) {
      int capacity = BoundedArrayGrowth.capacity(
          descriptors.length, columns, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
      try {
        descriptors = new int[capacity];
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    for (int index = 0; index < columns; index++) {
      descriptors[index] = schema.descriptor(index);
    }
    count = columns;
    return StatusCode.OK;
  }

  StatusCode begin(SqlScanRowResult result, long key) {
    return result.beginProjected(key, descriptors, count);
  }

  StatusCode begin(SqlExecutionResult result, long key, long commitSequence) {
    return result.beginProjection(key, descriptors, count, commitSequence);
  }

  int count() { return count; }
}
