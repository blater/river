package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Shared authentication and temporary scalar-primary validation for descriptor pins. */
final class RelationalDescriptorPin {
  private RelationalDescriptorPin() {
  }

  static TableDescriptor validTable(
      RelationalSession owner, SchemaPin pin, SqlValueBuffer values) {
    TableDescriptor table = validTable(owner, pin);
    return table != null && values != null && values.count() == table.columnCount()
        ? table : null;
  }

  static TableDescriptor validTable(RelationalSession owner, SchemaPin pin) {
    if (pin == null || !pin.isActive()) return null;
    if (!owner.authorizesDescriptorPin(pin)) return null;
    TableDescriptor table = pin.descriptor();
    if (table == null || table.catalogGeneration() <= 0
        || table.rowLayoutId() <= 0
        || !RelationalDescriptorKeyspace.validate(table.tableId()).isOk()) return null;
    KeyDescriptor primary = table.primaryKey();
    if (primary == null) return table;
    if (primary.kind() != KeyDescriptor.KIND_PRIMARY || !primary.isUnique()
        || primary.partCount() <= 0) return null;
    for (int part = 0; part < primary.partCount(); part++) {
      int column = primary.columnOrdinalAt(part);
      if (column < 0 || column >= table.columnCount() || table.isNullable(column)
          || primary.typeDescriptorAt(part) != table.typeDescriptorAt(column)) return null;
    }
    return table;
  }
}
