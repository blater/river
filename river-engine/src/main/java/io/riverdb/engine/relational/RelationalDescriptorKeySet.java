package io.riverdb.engine.relational;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Indexes the primary and secondary tuple keys without materializing a collection. */
final class RelationalDescriptorKeySet {
  private RelationalDescriptorKeySet() {
  }

  static int count(TableDescriptor table) {
    return table == null ? 0
        : (table.primaryKey() == null ? 0 : 1) + table.secondaryKeyCount();
  }

  static KeyDescriptor at(TableDescriptor table, int index) {
    if (table.primaryKey() != null) {
      if (index == 0) return table.primaryKey();
      index--;
    }
    return table.secondaryKeyAt(index);
  }
}
