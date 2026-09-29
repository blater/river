package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Descriptor checks and exact bounded decode-workspace admission. */
final class RelationalDescriptorShapeValidation {
  private RelationalDescriptorShapeValidation() {
  }

  static StatusCode validate(TableDescriptor table) {
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
