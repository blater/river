package io.riverdb.sql;

/** Resets all caller-owned command state for parser reuse. */
final class SqlCommandReset {
  private SqlCommandReset() { }

  static void reset(SqlCommand command) {
    command.tableName.reset();
    command.renamedTableName.reset();
    command.tableAlias.reset();
    command.indexName.reset();
    command.renamedIndexName.reset();
    command.sequenceName.reset();
    command.savepointName.reset();
    command.viewQuery.reset();
    command.scalarExpression.reset();
    command.projections.reset();
    command.mutationExpressions.reset();
    command.aggregates.reset();
    command.wherePredicates.reset();
    if (command.joinChain != null) command.joinChain.reset();
    command.booleanHavingPredicates.reset();
    for (SqlIdentifier columnName : command.columnNames) {
      columnName.reset();
    }
    for (int index = 0; index < command.nullProjections.length; index++) {
      command.nullProjections[index] = false;
      command.columnCheckValues[index] = 0;
      command.columnCheckTypeDescriptors[index] = 0;
      command.columnCheckComparisons[index] = null;
      command.columnTypeDescriptors[index] = 0;
      command.columnDefaultKinds[index] = 0;
      command.columnReferenceTableNames[index].reset();
      command.columnReferenceColumnNames[index].reset();
    }
    for (SqlIdentifier columnTableName : command.columnTableNames) {
      columnTableName.reset();
    }
    for (SqlIdentifier columnAlias : command.columnAliases) {
      columnAlias.reset();
    }
    command.orderColumnName.reset();
    command.type = null;
    command.key = 0;
    command.value = 0;
    command.scanLowerInclusive = 0;
    command.scanUpperExclusive = 0;
    command.columnNotNullMask = 0;
    command.columnDefaultMask = 0;
    command.columnUniqueMask = 0;
    command.columnReferenceMask = 0;
    command.rowLimit = Long.MAX_VALUE;
    command.sequenceStart = 1;
    command.sequenceIncrement = 1;
    command.boundedScan = false;
    command.selectAll = false;
    command.readCommittedTransaction = false;
    command.serializableTransaction = false;
    command.descendingOrder = false;
    command.primaryKeyIdentity = false;
    command.insertRowCount = 0;
    command.insertColumnCount = 0;
    command.updateColumnCount = 0;
    command.columnCount = 0;
    command.available = false;
    command.textBytesUsed = 0;
    for (int index = 0; index < command.insertNullMasks.length; index++) {
      command.insertNullMasks[index] = 0;
      command.insertDefaultMasks[index] = 0;
      int valueOffset = index * SqlCommand.MAXIMUM_COLUMNS;
      for (int column = 0; column < SqlCommand.MAXIMUM_COLUMNS; column++) {
        command.insertTypeDescriptors[valueOffset + column] = 0;
      }
    }
    for (int index = 0; index < command.nullUpdates.length; index++) {
      command.nullUpdates[index] = false;
      command.defaultUpdates[index] = false;
      command.updateTypeDescriptors[index] = 0;
      command.updateOperators[index] = SqlCommand.UPDATE_LITERAL;
    }
  }
}
