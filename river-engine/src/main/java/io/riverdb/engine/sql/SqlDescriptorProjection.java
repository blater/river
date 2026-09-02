package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Reusable direct-column result shape and publisher for descriptor rows. */
final class SqlDescriptorProjection {
  private static final int MAXIMUM_TEXT_CHARS = Utf8Text.MAXIMUM_BUFFER_CHARACTERS;
  private final char[] textChars = new char[MAXIMUM_TEXT_CHARS];
  private final SqlDescriptorColumnName descriptorName = new SqlDescriptorColumnName();
  private final SqlDescriptorProjectionShape shape;

  SqlDescriptorProjection() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlDescriptorProjection(SqlRetainedArrayAllocator arrayAllocator) {
    shape = new SqlDescriptorProjectionShape(arrayAllocator);
  }

  StatusCode prepare(SqlCommand command, TableDescriptor table) {
    return shape.prepare(command, table);
  }

  StatusCode publish(
      SqlValueBuffer values,
      long key,
      long commitSequence,
      SqlExecutionResult result) {
    StatusCode status = result.beginProjection(
        key, shape.descriptors, shape.count, commitSequence);
    for (int index = 0; status.isOk() && index < shape.count; index++) {
      status = publishValue(result, values, index, shape.columns[index]);
    }
    return status;
  }

  StatusCode configurePlan(
      SqlCommand command, TableDescriptor table, SqlPhysicalPlan plan) {
    StatusCode status = plan.beginResult(shape.count);
    for (int index = 0; status.isOk() && index < shape.count; index++) {
      int column = shape.columns[index];
      CharSequence name = command.isSelectAll()
          ? descriptorName.load(table, column) : command.columnOutputName(index);
      plan.setResultColumn(index, column, shape.descriptors[index], name);
      plan.setResultNullable(index, column < 0 || table.isNullable(column));
    }
    return status;
  }

  StatusCode publishScan(
      SqlValueBuffer values, long key, SqlScanRowResult result) {
    StatusCode status = result.beginProjected(key, shape.descriptors, shape.count);
    for (int index = 0; status.isOk() && index < shape.count; index++) {
      status = publishScanValue(result, values, index, shape.columns[index]);
    }
    return status;
  }

  StatusCode publishScan(
      SqlBlockRow values, long key, SqlScanRowResult result) {
    StatusCode status = result.beginProjected(key, shape.descriptors, shape.count);
    for (int index = 0; status.isOk() && index < shape.count; index++) {
      status = publishScanValue(result, values, index, shape.columns[index]);
    }
    return status;
  }

  int orderColumn(SqlCommand command, TableDescriptor table) {
    return shape.orderColumn(command, table);
  }

  int orderCount() { return shape.orderCount; }
  int[] orderColumns() { return shape.orderColumns; }
  boolean[] orderDescending() { return shape.descending; }

  private StatusCode publishValue(
      SqlExecutionResult result, SqlValueBuffer values, int projection, int column) {
    if (column < 0 || values.isNull(column)) {
      result.setProjectedNull(projection);
      return StatusCode.OK;
    }
    int descriptor = values.descriptorAt(column);
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      result.setProjectedDecimal128(
          projection, values.highValueAt(column), values.valueAt(column));
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      result.setProjectedValue(projection, values.valueAt(column));
      return StatusCode.OK;
    }
    int chars = values.copyTextChars(column, textChars, 0);
    return chars < 0 ? StatusCode.CORRUPTION : result.setTextAt(projection, textChars, chars);
  }

  private StatusCode publishScanValue(
      SqlScanRowResult result, SqlValueBuffer values, int projection, int column) {
    if (column < 0 || values.isNull(column)) {
      result.setProjectedNull(projection);
      return StatusCode.OK;
    }
    int descriptor = values.descriptorAt(column);
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      result.setProjectedDecimal128(
          projection, values.highValueAt(column), values.valueAt(column));
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      result.setProjectedValue(projection, values.valueAt(column));
      return StatusCode.OK;
    }
    int chars = values.copyTextChars(column, textChars, 0);
    return chars < 0 ? StatusCode.CORRUPTION : result.setTextAt(projection, textChars, chars);
  }

  private StatusCode publishScanValue(
      SqlScanRowResult result, SqlBlockRow values, int projection, int column) {
    if (column < 0 || values.nullValue(column)) {
      result.setProjectedNull(projection);
      return StatusCode.OK;
    }
    int descriptor = shape.descriptors[projection];
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      result.setProjectedDecimal128(
          projection, values.highValue(column), values.value(column));
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      result.setProjectedValue(projection, values.value(column));
      return StatusCode.OK;
    }
    return result.setTextAt(
        projection, values.text(column), values.textLength(column));
  }

  StatusCode reserveOrder(int required) {
    return shape.reserveOrder(required);
  }
}
