package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Retained geometric storage admission for a reusable table definition. */
final class TableDefinitionCapacity {
  private static final int INITIAL_COLUMNS = 8;
  private static final int INITIAL_NODES = 8;
  private static final int INITIAL_TEXT_BYTES = 64;

  private TableDefinitionCapacity() { }

  static StatusCode ensure(
      TableDefinition table,
      int columns,
      int nodes,
      int textBytes,
      int indexes) {
    if (columns < 0 || columns > SqlShapeLimits.MAX_TABLE_COLUMNS
        || nodes < 0 || nodes > TableSchema.MAXIMUM_CHECK_PROGRAM_NODES
        || textBytes < 0 || textBytes > TableSchema.MAXIMUM_ROW_BYTES
        || indexes < 0 || indexes > SqlShapeLimits.MAX_SECONDARY_INDEXES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = ensureColumns(table, columns);
    if (status.isOk()) status = ensureNodes(table, nodes);
    if (status.isOk()) status = ensureText(table, textBytes);
    if (status.isOk()) status = TableDefinitionIndexMutation.ensureCapacity(table, indexes);
    return status;
  }

  static void clearBits(TableDefinition table) {
    clear(table.notNullColumns);
    clear(table.defaultColumns);
    clear(table.checkColumns);
    clear(table.referenceColumns);
  }

  private static StatusCode ensureColumns(TableDefinition table, int required) {
    if (required <= table.columnNames.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        table.columnNames.length,
        required,
        SqlShapeLimits.MAX_TABLE_COLUMNS,
        INITIAL_COLUMNS);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = grow(table.notNullColumns, capacity);
    if (status.isOk()) status = grow(table.defaultColumns, capacity);
    if (status.isOk()) status = grow(table.checkColumns, capacity);
    if (status.isOk()) status = grow(table.referenceColumns, capacity);
    if (!status.isOk()) return status;
    try {
      TableDefinitionColumnName[] names = new TableDefinitionColumnName[capacity];
      long[] defaultValues = new long[capacity];
      byte[] defaultKinds = new byte[capacity];
      int[] descriptors = new int[capacity];
      int[] valueOffsets = new int[capacity];
      long[] checkValues = new long[capacity];
      int[] comparisons = new int[capacity];
      int[] checkDescriptors = new int[capacity];
      int[] nodeCounts = new int[capacity];
      int[] nodeOffsets = new int[capacity];
      int[] references = new int[capacity];
      int retained = table.columnCount;
      System.arraycopy(table.columnNames, 0, names, 0, retained);
      System.arraycopy(table.defaultValues, 0, defaultValues, 0, retained);
      System.arraycopy(table.defaultKinds, 0, defaultKinds, 0, retained);
      System.arraycopy(table.typeDescriptors, 0, descriptors, 0, retained);
      System.arraycopy(table.valueOffsets, 0, valueOffsets, 0, retained);
      System.arraycopy(table.checkValues, 0, checkValues, 0, retained);
      System.arraycopy(table.checkComparisons, 0, comparisons, 0, retained);
      System.arraycopy(table.checkTypeDescriptors, 0, checkDescriptors, 0, retained);
      System.arraycopy(table.checkNodeCounts, 0, nodeCounts, 0, retained);
      System.arraycopy(table.checkNodeOffsets, 0, nodeOffsets, 0, retained);
      System.arraycopy(table.referenceTableIds, 0, references, 0, retained);
      table.columnNames = names;
      table.defaultValues = defaultValues;
      table.defaultKinds = defaultKinds;
      table.typeDescriptors = descriptors;
      table.valueOffsets = valueOffsets;
      table.checkValues = checkValues;
      table.checkComparisons = comparisons;
      table.checkTypeDescriptors = checkDescriptors;
      table.checkNodeCounts = nodeCounts;
      table.checkNodeOffsets = nodeOffsets;
      table.referenceTableIds = references;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  static StatusCode ensureNames(TableDefinition table, int count) {
    try {
      for (int index = 0; index < count; index++) {
        if (table.columnNames[index] == null) {
          table.columnNames[index] = new TableDefinitionColumnName();
        }
      }
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static StatusCode ensureNodes(TableDefinition table, int required) {
    if (required <= table.checkOperators.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        table.checkOperators.length,
        required,
        TableSchema.MAXIMUM_CHECK_PROGRAM_NODES,
        INITIAL_NODES);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      byte[] operators = new byte[capacity];
      long[] operands = new long[capacity];
      int[] descriptors = new int[capacity];
      int[] stack = new int[capacity];
      System.arraycopy(table.checkOperators, 0, operators, 0, table.checkNodeCount);
      System.arraycopy(table.checkOperands, 0, operands, 0, table.checkNodeCount);
      System.arraycopy(
          table.checkNodeDescriptors, 0, descriptors, 0, table.checkNodeCount);
      table.checkOperators = operators;
      table.checkOperands = operands;
      table.checkNodeDescriptors = descriptors;
      table.checkValidationStack = stack;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static StatusCode ensureText(TableDefinition table, int required) {
    if (required <= table.defaultTextBytes.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        table.defaultTextBytes.length,
        required,
        TableSchema.MAXIMUM_ROW_BYTES,
        INITIAL_TEXT_BYTES);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      byte[] text = new byte[capacity];
      System.arraycopy(
          table.defaultTextBytes, 0, text, 0, table.defaultTextBytesUsed);
      table.defaultTextBytes = text;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static StatusCode grow(ColumnBitSet bits, int capacity) {
    int words = bits.wordCount();
    long[] retained;
    try {
      retained = new long[words];
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    bits.copyWords(retained);
    StatusCode status = bits.reserve(capacity, SqlShapeLimits.MAX_TABLE_COLUMNS);
    if (!status.isOk()) return status;
    status = bits.clearForSize(capacity);
    if (!status.isOk()) return status;
    for (int index = 0; index < words; index++) bits.setWord(index, retained[index]);
    return StatusCode.OK;
  }

  private static void clear(ColumnBitSet bits) {
    bits.clearForSize(bits.bitCount());
  }
}
