package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Copies and expands query-shaped state between reusable SQL commands. */
final class SqlCommandQueryState {
  private SqlCommandQueryState() { }

  static StatusCode expandSelectAll(SqlCommand command, SqlCommand source) {
    if (!command.selectAll || command.columnCount != 0
        || source == null || source.columnCount <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    command.selectAll = false;
    for (int index = 0; index < source.columnCount; index++) {
      SqlIdentifier column = command.writableNextColumnName();
      if (column == null) return StatusCode.RESOURCE_EXHAUSTED;
      column.copyFrom(source.columnOutputName(index));
      StatusCode status = command.setProjectionColumn(index, "", column);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  static void copy(SqlCommand command, SqlCommand source) {
    command.reset();
    command.scalarExpression.copyFrom(source.scalarExpression);
    command.projections.copyFrom(source.projections);
    command.aggregates.copyFrom(source.aggregates);
    command.wherePredicates.copyFrom(source.wherePredicates);
    boolean joined = source.joinChain != null && source.joinChain.stageCount() > 0;
    if (joined) {
      command.writableJoinChain().copyFrom(source.joinChain);
    } else {
      command.tableName.copyFrom(source.tableName);
      command.tableAlias.copyFrom(source.tableAlias);
    }
    command.booleanHavingPredicates.copyFrom(source.booleanHavingPredicates);
    System.arraycopy(source.textBytes, 0, command.textBytes, 0, source.textBytesUsed);
    command.textBytesUsed = source.textBytesUsed;
    for (int index = 0; index < source.columnCount; index++) {
      command.writableNextColumnName().copyFrom(source.columnNames[index]);
      command.writableColumnTableName(index).copyFrom(source.columnTableNames[index]);
      command.writableColumnAlias(index).copyFrom(source.columnAliases[index]);
      command.nullProjections[index] = source.nullProjections[index];
    }
    command.orderColumnName.copyFrom(source.orderColumnName);
    command.descendingOrder = source.descendingOrder;
    if (source.selectAll) command.setSelectAll();
    command.setRowLimit(source.rowLimit);
    if (source.type == SqlCommandType.SCAN) {
      command.setScan(source.scanLowerInclusive, source.scanUpperExclusive, source.boundedScan);
    } else {
      command.set(source.type, source.key, source.value);
    }
  }
}
