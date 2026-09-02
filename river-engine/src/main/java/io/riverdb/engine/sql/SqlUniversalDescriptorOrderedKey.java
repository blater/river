package io.riverdb.engine.sql;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Selects a tuple index whose leading part provides the requested order. */
final class SqlUniversalDescriptorOrderedKey {
  private SqlUniversalDescriptorOrderedKey() { }

  static KeyDescriptor find(TableDescriptor descriptor, int column) {
    KeyDescriptor primary = descriptor.primaryKey();
    if (startsWith(primary, column)) return primary;
    for (int index = 0; index < descriptor.secondaryKeyCount(); index++) {
      KeyDescriptor key = descriptor.secondaryKeyAt(index);
      if (startsWith(key, column)) return key;
    }
    return null;
  }

  private static boolean startsWith(KeyDescriptor key, int column) {
    return key != null && key.partCount() > 0 && key.columnOrdinalAt(0) == column;
  }
}
