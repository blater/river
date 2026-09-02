package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Allocation-free row publication after result-shape reservation. */
final class SqlProjectionPublication {
  private SqlProjectionPublication() {}

  static StatusCode writePoint(
      SqlExecutionResult result,
      long primaryKey,
      HeapRowResult source,
      BoundSqlStatement bound,
      SqlProjectedRow projected) {
    StatusCode status = projected.status();
    if (status.isOk()) {
      status = result.beginProjection(
          primaryKey, bound.projectedTypeDescriptors, bound.projectedColumnCount, 0);
    }
    for (int index = 0; status.isOk() && index < bound.projectedColumnCount; index++) {
      status = publishPoint(result, source, bound, projected, index);
    }
    if (!status.isOk()) result.reset();
    return status;
  }

  static StatusCode writeScan(
      SqlScanRowResult result,
      long primaryKey,
      HeapRowResult source,
      TableDefinition table,
      SqlScanCursor cursor,
      int[] descriptors,
      SqlProjectedRow projected) {
    StatusCode status = projected.status();
    if (status.isOk()) {
      status = result.beginProjected(primaryKey, descriptors, cursor.projectedColumnCount());
    }
    for (int index = 0; status.isOk() && index < cursor.projectedColumnCount(); index++) {
      status = publishScan(result, source, table, cursor, descriptors, projected, index);
    }
    if (!status.isOk()) result.reset();
    return status;
  }

  private static StatusCode publishPoint(
      SqlExecutionResult result,
      HeapRowResult source,
      BoundSqlStatement bound,
      SqlProjectedRow projected,
      int index) {
    if (projected.isNull(index)) {
      result.setProjectedNull(index);
      return StatusCode.OK;
    }
    int column = bound.projectedColumns[index];
    if (rawText(bound.table, column)) {
      long handle = source.getLong(bound.table.valueOffset(column));
      return result.setUtf8At(index, source, (int) (handle >>> 32), (int) handle);
    }
    if (text(bound.projectedTypeDescriptors[index])) {
      return result.setTextAt(index, projected.text(index), projected.textLength(index));
    }
    publishValue(
        result, index, bound.projectedTypeDescriptors[index], projected);
    return StatusCode.OK;
  }

  private static StatusCode publishScan(
      SqlScanRowResult result,
      HeapRowResult source,
      TableDefinition table,
      SqlScanCursor cursor,
      int[] descriptors,
      SqlProjectedRow projected,
      int index) {
    if (projected.isNull(index)) {
      result.setProjectedNull(index);
      return StatusCode.OK;
    }
    int column = cursor.projectedColumn(index);
    if (rawText(table, column)) {
      long handle = source.getLong(table.valueOffset(column));
      return result.setUtf8At(index, source, (int) (handle >>> 32), (int) handle);
    }
    if (text(descriptors[index])) {
      return result.setTextAt(index, projected.text(index), projected.textLength(index));
    }
    publishValue(result, index, descriptors[index], projected);
    return StatusCode.OK;
  }

  private static void publishValue(
      SqlExecutionResult result,
      int index,
      int descriptor,
      SqlProjectedRow projected) {
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      result.setProjectedDecimal128(
          index, projected.highValue(index), projected.value(index));
    } else result.setProjectedValue(index, projected.value(index));
  }

  private static void publishValue(
      SqlScanRowResult result,
      int index,
      int descriptor,
      SqlProjectedRow projected) {
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      result.setProjectedDecimal128(
          index, projected.highValue(index), projected.value(index));
    } else result.setProjectedValue(index, projected.value(index));
  }

  private static boolean rawText(TableDefinition table, int column) {
    return column > 0 && table.isVarchar(column);
  }

  private static boolean text(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
}
