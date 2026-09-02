package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Resolves one INSERT source tuple layout onto physical table columns. */
final class SqlInsertColumnMapping {
  private SqlInsertColumnMapping() {}

  static StatusCode map(SqlCommand command, BoundSqlStatement bound) {
    StatusCode reserved = bound.reserveInsertColumns(bound.table.columnCount());
    if (!reserved.isOk()) return reserved;
    for (int index = 0; index < bound.table.columnCount(); index++) {
      bound.insertSourceByColumn[index] = -1;
    }
    if (command.columnCount() == 0) {
      return positional(command, bound);
    }
    if (command.insertColumnCount() != command.columnCount()
        || command.columnCount() > bound.table.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int source = 0; source < command.columnCount(); source++) {
      int column = bound.table.findColumn(command.columnName(source));
      if (column < 0 || bound.insertSourceByColumn[column] >= 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      bound.insertSourceByColumn[column] = source;
    }
    return StatusCode.OK;
  }

  private static StatusCode positional(
      SqlCommand command, BoundSqlStatement bound) {
    if (command.insertColumnCount() != bound.table.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int column = 0; column < bound.table.columnCount(); column++) {
      bound.insertSourceByColumn[column] = column;
    }
    return StatusCode.OK;
  }
}
