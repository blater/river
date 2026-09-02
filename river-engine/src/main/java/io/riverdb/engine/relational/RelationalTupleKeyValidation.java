package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;

/** Validates one tuple-key request before scratch growth or builder mutation. */
final class RelationalTupleKeyValidation {
  private RelationalTupleKeyValidation() { }

  static StatusCode validate(
      KeyDescriptor key,
      SqlValueBuffer values,
      int parts,
      long logicalRowId,
      boolean physical) {
    if (key == null || values == null || parts <= 0 || parts > key.partCount()
        || physical && parts != key.partCount()
        || physical != (logicalRowId > 0)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int part = 0; part < parts; part++) {
      int column = key.columnOrdinalAt(part);
      if (column < 0 || column >= values.count()
          || values.descriptorAt(column) != key.typeDescriptorAt(part)
          || key.kind() == KeyDescriptor.KIND_PRIMARY && values.isNull(column)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return StatusCode.OK;
  }
}
