package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;

/** Publishes one projected row and its raw or generated text into result ownership. */
final class SqlProjectionResultWriter {
  StatusCode writePoint(
      SqlExecutionResult result,
      long primaryKey,
      HeapRowResult source,
      BoundSqlStatement bound,
      SqlProjectedRow projected) {
    result.setProjection(
        primaryKey,
        projected.values(),
        projected.nullMask(),
        bound.projectedTypeDescriptors,
        bound.projectedColumnCount,
        0);
    StatusCode status = setRawText(
        result, source, bound.table, bound.projectedColumns,
        bound.projectedColumnCount, projected.nullMask());
    if (status.isOk()) status = setGeneratedText(result, projected);
    if (!status.isOk()) result.reset();
    return status;
  }

  StatusCode writeScan(
      SqlScanRowResult result,
      long primaryKey,
      HeapRowResult source,
      TableDefinition table,
      SqlScanCursor cursor,
      int[] descriptors,
      SqlProjectedRow projected) {
    result.set(
        primaryKey,
        projected.values(),
        projected.nullMask(),
        descriptors,
        cursor.projectedColumnCount());
    StatusCode status = setRawText(
        result, source, table, cursor, projected.nullMask());
    if (status.isOk()) status = setGeneratedText(result, projected);
    if (!status.isOk()) result.reset();
    return status;
  }

  private static StatusCode setRawText(
      SqlExecutionResult result,
      HeapRowResult source,
      TableDefinition table,
      int[] columns,
      int count,
      long nullMask) {
    for (int index = 0; index < count; index++) {
      int column = columns[index];
      if (!rawText(table, column, nullMask, index)) continue;
      long handle = source.getLong((column - 1) * Long.BYTES);
      StatusCode status = result.setUtf8At(
          index, source, (int) (handle >>> 32), (int) handle);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static StatusCode setRawText(
      SqlScanRowResult result,
      HeapRowResult source,
      TableDefinition table,
      SqlScanCursor cursor,
      long nullMask) {
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int column = cursor.projectedColumn(index);
      if (!rawText(table, column, nullMask, index)) continue;
      long handle = source.getLong((column - 1) * Long.BYTES);
      StatusCode status = result.setUtf8At(
          index, source, (int) (handle >>> 32), (int) handle);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static boolean rawText(
      TableDefinition table, int column, long nullMask, int projection) {
    return column > 0
        && table.isVarchar(column)
        && (nullMask & 1L << projection) == 0;
  }

  private static StatusCode setGeneratedText(
      SqlExecutionResult result, SqlProjectedRow projected) {
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      int length = projected.textLength(index);
      if (length <= 0) continue;
      StatusCode status = result.setTextAt(index, projected.text(index), length);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static StatusCode setGeneratedText(
      SqlScanRowResult result, SqlProjectedRow projected) {
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      int length = projected.textLength(index);
      if (length <= 0) continue;
      StatusCode status = result.setTextAt(index, projected.text(index), length);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }
}
