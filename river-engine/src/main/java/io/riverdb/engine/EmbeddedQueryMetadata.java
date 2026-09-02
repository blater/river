package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.QueryMetadata;
import io.riverdb.engine.sql.SqlScanCursor;
import io.riverdb.engine.sql.SqlSession;

/** Reusable immutable-to-callers metadata for one embedded query generation. */
final class EmbeddedQueryMetadata implements QueryMetadata {
  private int columns;
  private int maximumTextBytes;
  private long generation;

  StatusCode prepare(SqlSession session, SqlScanCursor cursor) {
    if (session == null || cursor == null || generation == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int nextColumns = cursor.projectedColumnCount();
    if (nextColumns <= 0 || nextColumns > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.INVARIANT_BROKEN;
    }
    long textBytes = 0;
    for (int index = 0; index < nextColumns; index++) {
      int descriptor = session.scanColumnTypeDescriptor(cursor, index);
      if (!SqlTypeDescriptor.isValid(descriptor)) return StatusCode.INVARIANT_BROKEN;
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        textBytes += (long) SqlTypeDescriptor.parameterOne(descriptor) * 4;
        if (textBytes > SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
      }
    }
    columns = nextColumns;
    maximumTextBytes = (int) textBytes;
    generation++;
    return StatusCode.OK;
  }

  @Override
  public int columnCount() {
    return columns;
  }

  @Override
  public int maximumEncodedTextBytes() {
    return maximumTextBytes;
  }

  @Override
  public long reservationGeneration() {
    return generation;
  }
}
