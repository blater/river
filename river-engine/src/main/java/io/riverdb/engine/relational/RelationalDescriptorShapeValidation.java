package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Descriptor checks and exact bounded decode-workspace admission. */
final class RelationalDescriptorShapeValidation {
  private RelationalDescriptorShapeValidation() {
  }

  static StatusCode reserve(TableDescriptor table, SqlValueBuffer values) {
    StatusCode status = validate(table);
    if (!status.isOk()) return status;
    int textBytes = maximumTextBytes(table);
    if (textBytes < 0) return StatusCode.CORRUPTION;
    return values.reserve(
        table.columnCount(), SqlShapeLimits.MAX_TABLE_COLUMNS,
        textBytes, TableSchema.MAXIMUM_ROW_BYTES);
  }

  private static int maximumTextBytes(TableDescriptor table) {
    long bytes = 0;
    for (int index = 0; index < table.columnCount(); index++) {
      int descriptor = table.typeDescriptorAt(index);
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        bytes += SqlTypeDescriptor.parameterOne(descriptor) * 4L;
        if (bytes > TableSchema.MAXIMUM_ROW_BYTES) return -1;
      }
    }
    return (int) bytes;
  }

  private static StatusCode validate(TableDescriptor table) {
    if (table == null || table.columnCount() <= 0
        || table.columnCount() > SqlShapeLimits.MAX_TABLE_COLUMNS
        || table.encodedMaximumRowBytes() > TableSchema.MAXIMUM_ROW_BYTES) {
      return StatusCode.CORRUPTION;
    }
    KeyDescriptor primary = table.primaryKey();
    if (primary == null) return StatusCode.OK;
    if (primary.kind() != KeyDescriptor.KIND_PRIMARY || !primary.isUnique()
        || primary.partCount() <= 0) return StatusCode.CORRUPTION;
    for (int part = 0; part < primary.partCount(); part++) {
      int column = primary.columnOrdinalAt(part);
      if (column < 0 || column >= table.columnCount() || table.isNullable(column)
          || primary.typeDescriptorAt(part) != table.typeDescriptorAt(column)) {
        return StatusCode.CORRUPTION;
      }
    }
    return StatusCode.OK;
  }
}
