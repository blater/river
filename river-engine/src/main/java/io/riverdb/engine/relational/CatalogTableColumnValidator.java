package io.riverdb.engine.relational;

/** Validates decoded table column names. */
final class CatalogTableColumnValidator {
  private CatalogTableColumnValidator() {
  }

  static boolean validColumns(TableDefinition table) {
    for (int index = 0; index < table.columnCount(); index++) {
      CharSequence name = table.columnName(index);
      if (!RelationalKey.validName(name)) {
        return false;
      }
      for (int prior = 0; prior < index; prior++) {
        if (sameName(name, table.columnName(prior))) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean sameName(CharSequence first, CharSequence second) {
    if (first.length() != second.length()) {
      return false;
    }
    for (int index = 0; index < first.length(); index++) {
      if (first.charAt(index) != second.charAt(index)) {
        return false;
      }
    }
    return true;
  }
}
