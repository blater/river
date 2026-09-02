package io.riverdb.sql;

/** Resets all caller-owned command state for parser reuse. */
final class SqlCommandReset {
  private SqlCommandReset() { }

  static void reset(SqlCommand command) {
    int usedColumns = command.columnCount;
    int usedUpdates = command.updateColumnCount;
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
    command.grouping.reset();
    command.tableConstraints.reset();
    command.wherePredicates.reset();
    if (command.joinChain != null) command.joinChain.reset();
    command.booleanHavingPredicates.reset();
    for (int index = 0; index < usedColumns; index++) {
      command.columnNames[index].reset();
      command.nullProjections[index] = false;
      command.columnDefaultHighs[index] = 0;
      command.columnDefaultValues[index] = 0;
      command.columnCheckHighs[index] = 0;
      command.columnCheckValues[index] = 0;
      command.columnCheckTypeDescriptors[index] = 0;
      command.columnCheckComparisons[index] = null;
      command.columnTypeDescriptors[index] = 0;
      command.columnDefaultKinds[index] = 0;
      command.columnNotNull[index] = false;
      command.columnDefaults[index] = false;
      command.columnUnique[index] = false;
      command.columnReferences[index] = false;
      command.columnReferenceTableNames[index].reset();
      command.columnReferenceColumnNames[index].reset();
      command.columnTableNames[index].reset();
      command.columnAliases[index].reset();
    }
    command.orderBy.reset();
    command.type = null;
    command.key = 0;
    command.value = 0;
    command.scanLowerInclusive = 0;
    command.scanUpperExclusive = 0;
    command.rowLimit = Long.MAX_VALUE;
    command.sequenceStart = 1;
    command.sequenceIncrement = 1;
    command.boundedScan = false;
    command.selectAll = false;
    command.selectForUpdate = false;
    command.readCommittedTransaction = false;
    command.serializableTransaction = false;
    command.descendingOrder = false;
    command.primaryKeyIdentity = false;
    command.primaryKeyIdentityColumn = -1;
    command.insertRowCount = 0;
    command.insertColumnCount = 0;
    command.updateColumnCount = 0;
    command.columnCount = 0;
    command.available = false;
    command.textBytesUsed = 0;
    command.inserts.reset();
    for (int index = 0; index < usedUpdates; index++) {
      command.updateHighs[index] = 0;
      command.updateValues[index] = 0;
      command.nullUpdates[index] = false;
      command.defaultUpdates[index] = false;
      command.updateTypeDescriptors[index] = 0;
      command.updateOperators[index] = SqlCommand.UPDATE_LITERAL;
    }
  }
}
