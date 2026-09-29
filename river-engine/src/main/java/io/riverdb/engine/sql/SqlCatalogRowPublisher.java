package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.CatalogIndexResult;
import io.riverdb.engine.relational.CatalogObjectResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Writes a bounded SHOW catalog row directly into its caller-owned result. */
final class SqlCatalogRowPublisher {
  private final int[] descriptors = new int[5];
  private final char[] name = new char[128];

  StatusCode object(
      SqlPhysicalPlan plan, CatalogObjectResult object, CharSequence objectType,
      SqlScanRowResult result) {
    return object(plan, object.name(), objectType, result);
  }

  StatusCode object(
      SqlPhysicalPlan plan, CharSequence objectName, CharSequence objectType,
      SqlScanRowResult result) {
    StatusCode status = begin(plan, 2, 0, result);
    if (status.isOk()) status = result.setTextAt(0, objectName);
    if (status.isOk()) status = result.setTextAt(1, objectType);
    return finish(status, result);
  }

  StatusCode index(
      SqlPhysicalPlan plan, CatalogIndexResult index, SqlScanRowResult result) {
    StatusCode status = begin(plan, 5, 0, result);
    if (status.isOk()) status = index.isPrimary()
        ? nullName(result) : result.setTextAt(0, index.indexName());
    if (status.isOk()) status = result.setTextAt(1, index.columnName());
    if (status.isOk()) {
      result.setProjectedValue(2, index.isUnique() ? 1 : 0);
      result.setProjectedValue(3, index.isPrimary() ? 1 : 0);
      result.setProjectedValue(4, index.isConstraint() ? 1 : 0);
    }
    return finish(status, result);
  }

  StatusCode index(
      SqlPhysicalPlan plan, TableDescriptor table, KeyDescriptor index,
      int part, boolean primary, long rowKey, SqlScanRowResult result) {
    int column = index.columnOrdinalAt(part);
    int length = table.columns().copyNameChars(column, name, 0);
    if (length < 1 || length > name.length) return StatusCode.CORRUPTION;
    StatusCode status = begin(plan, 5, rowKey, result);
    if (status.isOk()) status = index.name() == null
        ? nullName(result) : result.setTextAt(0, index.name());
    if (status.isOk()) status = result.setTextAt(1, name, 0, length);
    if (status.isOk()) {
      result.setProjectedValue(2, index.isUnique() ? 1 : 0);
      result.setProjectedValue(3, primary ? 1 : 0);
      result.setProjectedValue(
          4, index.kind() == KeyDescriptor.KIND_SECONDARY ? 0 : 1);
    }
    return finish(status, result);
  }

  StatusCode column(
      SqlPhysicalPlan plan, TableDefinition table, int column,
      char[] typeName, int typeLength, SqlScanRowResult result) {
    StatusCode status = begin(plan, 4, column, result);
    if (status.isOk()) status = result.setTextAt(0, table.columnName(column));
    if (status.isOk()) status = result.setTextAt(1, typeName, 0, typeLength);
    if (status.isOk()) {
      result.setProjectedValue(2, table.isNullable(column) ? 1 : 0);
      result.setProjectedValue(3, column + 1L);
    }
    return finish(status, result);
  }

  StatusCode column(
      SqlPhysicalPlan plan, TableDescriptor table, int column,
      char[] typeName, int typeLength, SqlScanRowResult result) {
    int length = table.columns().copyNameChars(column, name, 0);
    if (length < 1 || length > name.length) return StatusCode.CORRUPTION;
    StatusCode status = begin(plan, 4, column, result);
    if (status.isOk()) status = result.setTextAt(0, name, 0, length);
    if (status.isOk()) status = result.setTextAt(1, typeName, 0, typeLength);
    if (status.isOk()) {
      result.setProjectedValue(2, table.isNullable(column) ? 1 : 0);
      result.setProjectedValue(3, column + 1L);
    }
    return finish(status, result);
  }

  private StatusCode begin(
      SqlPhysicalPlan plan, int columns, long rowKey, SqlScanRowResult result) {
    for (int column = 0; column < columns; column++) {
      descriptors[column] = plan.resultType(column);
    }
    return result.beginProjected(rowKey, descriptors, columns);
  }

  private static StatusCode nullName(SqlScanRowResult result) {
    result.setProjectedNull(0);
    return StatusCode.OK;
  }

  private static StatusCode finish(StatusCode status, SqlScanRowResult result) {
    if (!status.isOk()) result.reset();
    return status;
  }
}
