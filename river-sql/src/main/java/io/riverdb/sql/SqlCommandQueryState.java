package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Copies and expands query-shaped state between reusable SQL commands. */
final class SqlCommandQueryState {
  private SqlCommandQueryState() { }

  static StatusCode expandSelectAll(SqlCommand command, SqlCommand source) {
    return SqlSelectAllExpansion.expand(command, source);
  }

  static StatusCode copy(SqlCommand command, SqlCommand source) {
    command.reset();
    if (source == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    command.type = source.type;
    StatusCode status = command.scalarExpression.copyFrom(source.scalarExpression);
    if (status.isOk()) status = command.projections.copyFrom(source.projections, source.columnCount);
    if (status.isOk()) status = command.aggregates.copyFrom(source.aggregates);
    if (status.isOk() && !command.grouping.copyFrom(source.grouping)) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) status = command.wherePredicates.copyFrom(source.wherePredicates);
    if (!status.isOk()) return failed(command, status);
    boolean joined = source.joinChain != null && source.joinChain.stageCount() > 0;
    if (joined) {
      status = command.ensureJoinChain();
      if (status.isOk()) {
        status = command.writableJoinChain().copyFrom(source.joinChain);
      }
      if (!status.isOk()) return failed(command, status);
    } else {
      command.tableName.copyFrom(source.tableName);
      command.tableAlias.copyFrom(source.tableAlias);
    }
    status = command.booleanHavingPredicates.copyFrom(source.booleanHavingPredicates);
    if (!status.isOk()) return failed(command, status);
    System.arraycopy(source.textBytes, 0, command.textBytes, 0, source.textBytesUsed);
    command.textBytesUsed = source.textBytesUsed;
    for (int index = 0; index < source.columnCount; index++) {
      SqlIdentifier column = command.writableNextColumnName();
      if (column == null) return failed(command, StatusCode.RESOURCE_EXHAUSTED);
      column.copyFrom(source.columnNames[index]);
      command.writableColumnTableName(index).copyFrom(source.columnTableNames[index]);
      command.writableColumnAlias(index).copyFrom(source.columnAliases[index]);
      command.nullProjections[index] = source.nullProjections[index];
    }
    if (!command.orderBy.copyFrom(source.orderBy)) {
      return failed(command, StatusCode.RESOURCE_EXHAUSTED);
    }
    command.descendingOrder = source.descendingOrder;
    if (source.selectAll) command.setSelectAll();
    if (source.selectForUpdate) command.setSelectForUpdate();
    command.setRowLimit(source.rowLimit);
    if (source.type == SqlCommandType.SCAN) {
      command.setScan(source.scanLowerInclusive, source.scanUpperExclusive, source.boundedScan);
    } else {
      command.set(source.type, source.key, source.value);
    }
    return StatusCode.OK;
  }

  private static StatusCode failed(SqlCommand command, StatusCode status) {
    command.reset();
    return status;
  }
}
