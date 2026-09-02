package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;

/** Reusable lossless row coercion into the reconciled UNION schema. */
final class SqlUnionRowCoercion {
  private final SqlDescriptorNumericAssignment numeric = new SqlDescriptorNumericAssignment();
  private final SqlValueBuffer value = new SqlValueBuffer();

  StatusCode prepare() {
    return value.reserve(1, 1, 0, 0);
  }

  StatusCode convert(
      SqlBlockRow source, SqlBlockSchema sourceSchema,
      SqlBlockSchema targetSchema, SqlBlockRow target) {
    StatusCode status = target.reset(targetSchema.count());
    for (int column = 0; status.isOk() && column < targetSchema.count(); column++) {
      status = copy(source, sourceSchema, targetSchema, target, column);
    }
    if (status.isOk()) target.setKey(0);
    return status;
  }

  private StatusCode copy(
      SqlBlockRow source, SqlBlockSchema sourceSchema,
      SqlBlockSchema targetSchema, SqlBlockRow target, int column) {
    int from = sourceSchema.descriptor(column);
    int to = targetSchema.descriptor(column);
    if (source.nullValue(column)) {
      target.setNull(column);
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.typeId(to) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return target.setText(
          column, source.text(column), 0, source.textLength(column));
    }
    if (!SqlNumericTypeRules.isNumeric(to) || from == to) {
      if (SqlTypeDescriptor.isWideDecimal(to)) {
        target.setDecimal128(column, source.highValue(column), source.value(column));
      } else target.setValue(column, source.value(column));
      return StatusCode.OK;
    }
    StatusCode status = value.clearForSize(1);
    if (status.isOk()) {
      status = numeric.assign(
          value, 0, source.highValue(column), source.value(column), from, to);
    }
    if (!status.isOk()) return status;
    if (SqlTypeDescriptor.isWideDecimal(to)) {
      target.setDecimal128(column, value.highValueAt(0), value.valueAt(0));
    } else target.setValue(column, value.valueAt(0));
    return StatusCode.OK;
  }
}
