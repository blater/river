package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.format.btree.TupleKeyBuilder;

/** Encodes validated tuple parts into one admitted builder and scratch owner. */
final class RelationalTupleKeyPartEncoder {
  private boolean containsNull;

  StatusCode encode(
      TupleKeyBuilder builder,
      KeyDescriptor key,
      SqlValueBuffer values,
      int partCount,
      RelationalTupleKeyScratch scratch) {
    containsNull = false;
    StatusCode status = StatusCode.OK;
    for (int part = 0; status.isOk() && part < partCount; part++) {
      int column = key.columnOrdinalAt(part);
      int descriptor = key.typeDescriptorAt(part);
      status = encodePart(builder, values, column, descriptor, scratch);
    }
    return status;
  }

  boolean containsNull() { return containsNull; }

  private StatusCode encodePart(
      TupleKeyBuilder builder,
      SqlValueBuffer values,
      int column,
      int descriptor,
      RelationalTupleKeyScratch scratch) {
    if (values.isNull(column)) {
      containsNull = true;
      return builder.addNull(descriptor);
    }
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      StatusCode status = scratch.copyText(values, column);
      return status.isOk() ? builder.addText(descriptor, scratch.text()) : status;
    }
    return SqlTypeDescriptor.isWideDecimal(descriptor)
        ? builder.addDecimal128(
            descriptor, values.highValueAt(column), values.valueAt(column))
        : builder.addFixed(descriptor, values.valueAt(column));
  }
}
