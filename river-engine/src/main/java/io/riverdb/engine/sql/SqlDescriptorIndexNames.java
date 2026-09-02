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
        && (primary.matchesName(name) || same(PRIMARY_NAME, name));
  }

  private static boolean same(CharSequence left, CharSequence right) {
    if (left == null || right == null || left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (Character.toUpperCase(left.charAt(index))
          != Character.toUpperCase(right.charAt(index))) return false;
    }
    return true;
  }
}
