package io.riverdb.engine.relational;

/** Read-only accessors for persisted table check programs. */
final class TableDefinitionCheckView {
  private TableDefinitionCheckView() { }

  static long word(TableDefinition table, int word) { return table.checkColumns.word(word); }
  static boolean hasChecks(TableDefinition table) { return !table.checkColumns.isEmpty(); }
  static boolean hasCheck(TableDefinition table, int column) {
    return column >= 0 && column < table.columnCount && table.checkColumns.get(column);
  }
  static int comparison(TableDefinition table, int column) { return valid(table, column) ? table.checkComparisons[column] : 0; }
  static long value(TableDefinition table, int column) { return valid(table, column) ? table.checkValues[column] : 0; }
  static int descriptor(TableDefinition table, int column) { return hasCheck(table, column) ? table.checkTypeDescriptors[column] : 0; }
  static int nodeCount(TableDefinition table, int column) {
    return hasCheck(table, column) ? table.checkNodeCounts[column] : 0;
  }
  static int totalNodes(TableDefinition table) { return table.checkNodeCount; }
  static int operator(TableDefinition table, int column, int node) {
    int offset = nodeOffset(table, column, node);
    return offset < 0 ? 0 : Byte.toUnsignedInt(table.checkOperators[offset]);
  }
  static long operand(TableDefinition table, int column, int node) {
    int offset = nodeOffset(table, column, node); return offset < 0 ? 0 : table.checkOperands[offset];
  }
  static int nodeDescriptor(TableDefinition table, int column, int node) {
    int offset = nodeOffset(table, column, node); return offset < 0 ? 0 : table.checkNodeDescriptors[offset];
  }
  static int programOperator(TableDefinition table, int node) { return node >= 0 && node < table.checkNodeCount ? Byte.toUnsignedInt(table.checkOperators[node]) : 0; }
  static long programOperand(TableDefinition table, int node) { return node >= 0 && node < table.checkNodeCount ? table.checkOperands[node] : 0; }
  static int programDescriptor(TableDefinition table, int node) { return node >= 0 && node < table.checkNodeCount ? table.checkNodeDescriptors[node] : 0; }
  static int[] validationStack(TableDefinition table) { return table.checkValidationStack; }

  private static boolean valid(TableDefinition table, int column) { return column >= 0 && column < table.columnCount; }
  private static int nodeOffset(TableDefinition table, int column, int node) {
    return hasCheck(table, column) && node >= 0 && node < nodeCount(table, column)
        ? table.checkNodeOffsets[column] + node : -1;
  }
}
