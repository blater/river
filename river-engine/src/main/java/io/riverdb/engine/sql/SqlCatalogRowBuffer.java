package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.relational.CatalogIndexResult;
import io.riverdb.engine.relational.CatalogObjectResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Reusable materialized carrier for the bounded SHOW catalog result shape. */
final class SqlCatalogRowBuffer {
  private static final int MAX_COLUMNS = 5;
  private static final int MAX_TEXT_BYTES = 512;
  private static final int MAX_TEXT_CHARACTERS = 128;

  private final SqlValueBuffer values = new SqlValueBuffer();
  private final int[] descriptors = new int[MAX_COLUMNS];
  private final char[] text = new char[MAX_TEXT_CHARACTERS];

  StatusCode reserve(int columns, int textBytes) {
    return values.reserve(columns, MAX_COLUMNS, textBytes, MAX_TEXT_BYTES);
  }

  StatusCode loadObject(
      SqlPhysicalPlan plan, CatalogObjectResult object, CharSequence objectType) {
    return loadObject(plan, object.name(), objectType);
  }

  StatusCode loadObject(
      SqlPhysicalPlan plan, CharSequence objectName, CharSequence objectType) {
    StatusCode status = values.clearForSize(2);
    if (!status.isOk()) return status;
    status = values.setText(0, plan.resultType(0), objectName);
    return status.isOk()
        ? values.setText(1, plan.resultType(1), objectType)
        : status;
  }

  StatusCode loadIndex(SqlPhysicalPlan plan, CatalogIndexResult index) {
    StatusCode status = values.clearForSize(5);
    if (!status.isOk()) return status;
    status = index.isPrimary()
        ? values.setNull(0, plan.resultType(0))
        : values.setText(0, plan.resultType(0), index.indexName());
    if (!status.isOk()) return status;
    status = values.setText(1, plan.resultType(1), index.columnName());
    if (!status.isOk()) return status;
    status = values.setFixed(2, plan.resultType(2), index.isUnique() ? 1 : 0);
    if (!status.isOk()) return status;
    status = values.setFixed(3, plan.resultType(3), index.isPrimary() ? 1 : 0);
    return status.isOk()
        ? values.setFixed(4, plan.resultType(4), index.isConstraint() ? 1 : 0)
        : status;
  }

  StatusCode loadIndex(
      SqlPhysicalPlan plan,
      TableDescriptor table,
      KeyDescriptor index,
      int part,
      boolean primary) {
    int column = index.columnOrdinalAt(part);
    int nameLength = table.columns().copyNameChars(column, text, 0);
    if (nameLength < 1 || nameLength > text.length) return StatusCode.CORRUPTION;
    StatusCode status = values.clearForSize(5);
    if (!status.isOk()) return status;
    status = index.name() == null
        ? values.setNull(0, plan.resultType(0))
        : values.setText(0, plan.resultType(0), index.name());
    if (!status.isOk()) return status;
    status = values.setText(1, plan.resultType(1), text, 0, nameLength);
    if (!status.isOk()) return status;
    status = values.setFixed(2, plan.resultType(2), index.isUnique() ? 1 : 0);
    if (!status.isOk()) return status;
    status = values.setFixed(3, plan.resultType(3), primary ? 1 : 0);
    return status.isOk()
        ? values.setFixed(
            4,
            plan.resultType(4),
            index.kind() == KeyDescriptor.KIND_SECONDARY ? 0 : 1)
        : status;
  }

  StatusCode loadColumn(
      SqlPhysicalPlan plan,
      TableDefinition table,
      int column,
      char[] typeName,
      int typeNameLength) {
    StatusCode status = values.clearForSize(4);
    if (!status.isOk()) return status;
    status = values.setText(0, plan.resultType(0), table.columnName(column));
    if (!status.isOk()) return status;
    status = values.setText(1, plan.resultType(1), typeName, 0, typeNameLength);
    if (!status.isOk()) return status;
    status = values.setFixed(2, plan.resultType(2), table.isNullable(column) ? 1 : 0);
    return status.isOk()
        ? values.setFixed(3, plan.resultType(3), column + 1L)
        : status;
  }

  StatusCode loadColumn(
      SqlPhysicalPlan plan,
      TableDescriptor table,
      int column,
      char[] typeName,
      int typeNameLength) {
    StatusCode status = values.clearForSize(4);
    if (!status.isOk()) return status;
    int nameLength = table.columns().copyNameChars(column, text, 0);
    if (nameLength < 1 || nameLength > text.length) return StatusCode.CORRUPTION;
    status = values.setText(0, plan.resultType(0), text, 0, nameLength);
    if (!status.isOk()) return status;
    status = values.setText(1, plan.resultType(1), typeName, 0, typeNameLength);
    if (!status.isOk()) return status;
    status = values.setFixed(2, plan.resultType(2), table.isNullable(column) ? 1 : 0);
    return status.isOk()
        ? values.setFixed(3, plan.resultType(3), column + 1L)
        : status;
  }

  StatusCode publish(long key, SqlScanRowResult result) {
    int count = values.count();
    for (int column = 0; column < count; column++) {
      descriptors[column] = values.descriptorAt(column);
    }
    StatusCode status = result.beginProjected(key, descriptors, count);
    if (!status.isOk()) {
      result.reset();
      return status;
    }
    for (int column = 0; column < count; column++) {
      if (values.isNull(column)) {
        result.setProjectedNull(column);
        continue;
      }
      result.setProjectedValue(column, values.valueAt(column));
      if (SqlTypeDescriptor.typeId(descriptors[column]) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        continue;
      }
      int characters = values.copyTextChars(column, text, 0);
      if (characters < 0) {
        result.reset();
        return StatusCode.INVARIANT_BROKEN;
      }
      status = result.setTextAt(column, text, 0, characters);
      if (!status.isOk()) {
        result.reset();
        return status;
      }
    }
    return StatusCode.OK;
  }
}
