package io.riverdb.engine.relational;

/** Validation for persisted view table lineage without coupling it to session state. */
final class RelationalViewLineage {
  private RelationalViewLineage() {
  }

  static boolean valid(int[] tableIds, int tableCount) {
    if (tableIds == null || tableCount < 1
        || tableCount > ViewDefinition.MAXIMUM_LINEAGE_TABLES
        || tableCount > tableIds.length) return false;
    for (int index = 0; index < tableCount; index++) {
      if (tableIds[index] <= 0
          || tableIds[index] > RelationalKey.MAXIMUM_TABLE_ID) return false;
    }
    return true;
  }
}
