package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Admits and publishes derived SELECT-star projection state as one operation. */
final class SqlSelectAllExpansion {
  private SqlSelectAllExpansion() { }

  static StatusCode expand(SqlCommand command, SqlCommand source) {
    StatusCode status = validate(command, source);
    if (!status.isOk()) return status;
    if (!admit(command, source.columnCount)) return StatusCode.RESOURCE_EXHAUSTED;
    command.selectAll = false;
    for (int index = 0; index < source.columnCount; index++) {
      SqlIdentifier column = command.writableNextColumnName();
      if (column == null) return failed(command);
      column.copyFrom(source.columnOutputName(index));
      if (!command.setProjectionColumn(index, "", column).isOk()) return failed(command);
    }
    return StatusCode.OK;
  }

  private static StatusCode validate(SqlCommand command, SqlCommand source) {
    if (!command.selectAll || command.columnCount != 0
        || source == null || source.columnCount <= 0
        || source.columnCount > SqlCommand.MAXIMUM_PROJECTIONS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < source.columnCount; index++) {
      SqlIdentifier output = source.columnOutputName(index);
      if (output == null || output.length() == 0
          || output.length() > SqlIdentifier.MAXIMUM_LENGTH) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return StatusCode.OK;
  }

  private static boolean admit(SqlCommand command, int columns) {
    return SqlCommandCapacity.ensureColumns(command, columns)
        && SqlProjectionCapacity.ensureExpressions(command.projections, columns)
        && SqlProjectionCapacity.ensureSymbols(command.projections, columns);
  }

  private static StatusCode failed(SqlCommand command) {
    command.projections.reset();
    for (int index = 0; index < command.columnCount; index++) {
      command.columnNames[index].reset();
      command.columnTableNames[index].reset();
      command.columnAliases[index].reset();
      command.nullProjections[index] = false;
    }
    command.columnCount = 0;
    command.selectAll = true;
    return StatusCode.RESOURCE_EXHAUSTED;
  }
}
