package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Index-state mutations for a reusable table definition. */
final class TableDefinitionIndexMutation {
  private TableDefinitionIndexMutation() { }

  static void set(
      TableDefinition table,
      int slot,
      int tableId,
      int state,
      int column,
      boolean unique,
      boolean constraint) {
    table.uniqueIndexTableIds[slot] = tableId;
    table.uniqueIndexStates[slot] = state;
    table.uniqueIndexColumns[slot] = column;
    table.uniqueIndexes[slot] = unique;
    table.constraintIndexes[slot] = constraint;
  }

  static StatusCode upsert(
      TableDefinition table,
      int tableId,
      int state,
      int column,
      boolean unique,
      boolean constraint) {
    if (tableId <= 0
        || (state != TableDefinition.INDEX_BUILDING
            && state != TableDefinition.INDEX_READY
            && state != TableDefinition.INDEX_DROPPING)
        || column <= 0
        || column >= table.columnCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < table.uniqueIndexCount; index++) {
      if (table.uniqueIndexTableIds[index] == tableId
          || table.uniqueIndexColumns[index] == column) {
        set(table, index, tableId, state, column, unique, constraint);
        return StatusCode.OK;
      }
    }
    if (table.uniqueIndexCount >= SqlShapeLimits.MAX_SECONDARY_INDEXES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode capacity = ensureCapacity(table, table.uniqueIndexCount + 1);
    if (!capacity.isOk()) return capacity;
    set(table, table.uniqueIndexCount++, tableId, state, column, unique, constraint);
    return StatusCode.OK;
  }

  static StatusCode remove(TableDefinition table, int tableId) {
    for (int index = 0; index < table.uniqueIndexCount; index++) {
      if (table.uniqueIndexTableIds[index] != tableId) {
        continue;
      }
      shiftLeft(table, index);
      table.uniqueIndexCount--;
      set(table, table.uniqueIndexCount, 0, TableDefinition.INDEX_NONE, 0, false, false);
      return StatusCode.OK;
    }
    return StatusCode.CONFLICT;
  }

  private static void shiftLeft(TableDefinition table, int removedSlot) {
    for (int move = removedSlot; move < table.uniqueIndexCount - 1; move++) {
      set(
          table,
          move,
          table.uniqueIndexTableIds[move + 1],
          table.uniqueIndexStates[move + 1],
          table.uniqueIndexColumns[move + 1],
          table.uniqueIndexes[move + 1],
          table.constraintIndexes[move + 1]);
    }
  }

  static StatusCode ensureCapacity(TableDefinition table, int required) {
    if (required <= table.uniqueIndexTableIds.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        table.uniqueIndexTableIds.length,
        required,
        SqlShapeLimits.MAX_SECONDARY_INDEXES,
        4);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      int[] tableIds = new int[capacity];
      int[] states = new int[capacity];
      int[] columns = new int[capacity];
      boolean[] unique = new boolean[capacity];
      boolean[] constraint = new boolean[capacity];
      System.arraycopy(
          table.uniqueIndexTableIds, 0, tableIds, 0, table.uniqueIndexCount);
      System.arraycopy(table.uniqueIndexStates, 0, states, 0, table.uniqueIndexCount);
      System.arraycopy(table.uniqueIndexColumns, 0, columns, 0, table.uniqueIndexCount);
      System.arraycopy(table.uniqueIndexes, 0, unique, 0, table.uniqueIndexCount);
      System.arraycopy(
          table.constraintIndexes, 0, constraint, 0, table.uniqueIndexCount);
      table.uniqueIndexTableIds = tableIds;
      table.uniqueIndexStates = states;
      table.uniqueIndexColumns = columns;
      table.uniqueIndexes = unique;
      table.constraintIndexes = constraint;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
