package io.riverdb.engine.sql;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Case-normalized descriptor index-name predicates shared by catalog selection. */
final class SqlDescriptorIndexNames {
  private static final String PRIMARY_NAME = "PRIMARY";

  private SqlDescriptorIndexNames() { }

  static boolean matches(TableDescriptor table, CharSequence name) {
    return table.findSecondaryKey(name) >= 0 || primary(table, name);
  }

  static boolean primary(TableDescriptor table, CharSequence name) {
    KeyDescriptor primary = table.primaryKey();
    return primary != null
        && (primary.matchesName(name) || SqlBindingNames.sameIgnoringCase(PRIMARY_NAME, name));
  }
}
