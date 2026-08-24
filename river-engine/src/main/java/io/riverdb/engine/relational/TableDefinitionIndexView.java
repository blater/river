package io.riverdb.engine.relational;

/** Index-state queries and mutations for a reusable table definition. */
final class TableDefinitionIndexView {
  private TableDefinitionIndexView() { }

  static int uniqueValueIndexTableId(TableDefinition table) {
    int slot = preferredSlot(table);
    return slot < 0 ? 0 : table.uniqueIndexTableIds[slot];
  }

  static int uniqueValueIndexState(TableDefinition table) {
    int slot = preferredSlot(table);
    return slot < 0 ? TableDefinition.INDEX_NONE : table.uniqueIndexStates[slot];
  }

  static int uniqueValueIndexColumn(TableDefinition table) {
    int slot = preferredSlot(table);
    return slot < 0 ? -1 : table.uniqueIndexColumns[slot];
  }

  static int uniqueIndexTableId(TableDefinition table, int slot) {
    return validSlot(table, slot) ? table.uniqueIndexTableIds[slot] : 0;
  }

  static int uniqueIndexState(TableDefinition table, int slot) {
    return validSlot(table, slot) ? table.uniqueIndexStates[slot] : TableDefinition.INDEX_NONE;
  }

  static int uniqueIndexColumn(TableDefinition table, int slot) {
    return validSlot(table, slot) ? table.uniqueIndexColumns[slot] : -1;
  }

  static boolean indexIsUnique(TableDefinition table, int slot) {
    return validSlot(table, slot) && table.uniqueIndexes[slot];
  }

  static boolean indexIsConstraint(TableDefinition table, int slot) {
    return validSlot(table, slot) && table.constraintIndexes[slot];
  }

  static int readyIndexSlotOn(TableDefinition table, int column) {
    for (int index = 0; index < table.uniqueIndexCount; index++) {
      if (table.uniqueIndexStates[index] == TableDefinition.INDEX_READY
          && table.uniqueIndexColumns[index] == column) {
        return index;
      }
    }
    return -1;
  }

  static int readyIndexSlotForTableId(TableDefinition table, int tableId) {
    for (int index = 0; index < table.uniqueIndexCount; index++) {
      if (table.uniqueIndexStates[index] == TableDefinition.INDEX_READY
          && table.uniqueIndexTableIds[index] == tableId) {
        return index;
      }
    }
    return -1;
  }

  static int readyIndexCount(TableDefinition table) {
    int count = 0;
    for (int index = 0; index < table.uniqueIndexCount; index++) {
      if (table.uniqueIndexStates[index] == TableDefinition.INDEX_READY) {
        count++;
      }
    }
    return count;
  }

  static int buildingIndexSlot(TableDefinition table) {
    for (int index = 0; index < table.uniqueIndexCount; index++) {
      if (table.uniqueIndexStates[index] == TableDefinition.INDEX_BUILDING) {
        return index;
      }
    }
    return -1;
  }

  private static int preferredSlot(TableDefinition table) {
    int slot = buildingIndexSlot(table);
    return slot < 0 ? firstReadyIndexSlot(table) : slot;
  }

  private static int firstReadyIndexSlot(TableDefinition table) {
    for (int index = 0; index < table.uniqueIndexCount; index++) {
      if (table.uniqueIndexStates[index] == TableDefinition.INDEX_READY) {
        return index;
      }
    }
    return -1;
  }

  private static boolean validSlot(TableDefinition table, int slot) {
    return slot >= 0 && slot < table.uniqueIndexCount;
  }
}
