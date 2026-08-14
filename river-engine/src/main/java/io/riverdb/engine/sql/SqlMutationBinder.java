package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;

/** Maps INSERT and UPDATE values onto resolved table columns. */
final class SqlMutationBinder {
  StatusCode bindUpdate(SqlCommand command, BoundSqlStatement bound) {
    for (int index = 0; index < command.updateColumnCount(); index++) {
      StatusCode status = bindUpdateColumn(command, bound, index);
      if (!status.isOk()) {
        return status;
      }
    }
    bound.updatedColumnCount = command.updateColumnCount();
    return StatusCode.OK;
  }

  StatusCode bindInsert(SqlCommand command, BoundSqlStatement bound) {
    resetInsertMapping(bound);
    StatusCode status = mapInsertColumns(command, bound);
    if (!status.isOk()) {
      return status;
    }
    for (int row = 0; row < command.insertRowCount(); row++) {
      status = validateInsertRow(command, bound, row);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private static StatusCode bindUpdateColumn(
      SqlCommand command, BoundSqlStatement bound, int index) {
    int column = bound.table.findColumn(command.columnName(index));
    if (column <= 0 || isDuplicate(bound, index, column)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.updateIsNull(index) && !bound.table.isNullable(column)
        || command.updateIsDefault(index) && !bound.table.hasDefault(column)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bound.updatedColumns[index] = column;
    StatusCode status = bindUpdateSource(command, bound, index, column);
    if (!status.isOk()) {
      return status;
    }
    return compatibleAssignment(
        bound.updateResultTypeDescriptors[index],
        bound.table.typeDescriptor(column),
        command.updateIsNull(index),
        command.updateIsDefault(index))
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
  }

  private static boolean isDuplicate(
      BoundSqlStatement bound, int index, int column) {
    for (int prior = 0; prior < index; prior++) {
      if (bound.updatedColumns[prior] == column) {
        return true;
      }
    }
    return false;
  }

  private static StatusCode bindUpdateSource(
      SqlCommand command, BoundSqlStatement bound, int index, int column) {
    if (!command.isRelativeUpdate(index)) {
      bound.updateSourceColumns[index] = -1;
      bound.updateResultTypeDescriptors[index] =
          command.updateIsNull(index) || command.updateIsDefault(index)
              ? bound.table.typeDescriptor(column)
              : command.updateTypeDescriptor(index);
      return StatusCode.OK;
    }
    int source = bound.table.findColumn(command.updateSourceColumnName(index));
    if (source < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int descriptor = updateResultDescriptor(
        command, index, bound.table.typeDescriptor(source));
    if (descriptor == 0) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    bound.updateSourceColumns[index] = source;
    bound.updateResultTypeDescriptors[index] = descriptor;
    return StatusCode.OK;
  }

  private static int updateResultDescriptor(
      SqlCommand command, int index, int source) {
    return switch (command.updateOperator(index)) {
      case SqlCommand.UPDATE_ADD, SqlCommand.UPDATE_SUBTRACT ->
          ExactDecimal.addResultDescriptor(
              source, command.updateTypeDescriptor(index));
      case SqlCommand.UPDATE_MULTIPLY -> ExactDecimal.multiplyResultDescriptor(
          source, command.updateTypeDescriptor(index));
      case SqlCommand.UPDATE_DIVIDE -> ExactDecimal.divideResultDescriptor(
          source, command.updateTypeDescriptor(index));
      case SqlCommand.UPDATE_REMAINDER -> ExactDecimal.remainderResultDescriptor(
          source, command.updateTypeDescriptor(index));
      case SqlCommand.UPDATE_NEGATE, SqlCommand.UPDATE_ABSOLUTE ->
          exactNumeric(source) ? source : 0;
      case SqlCommand.UPDATE_CEILING, SqlCommand.UPDATE_FLOOR ->
          SqlTypeDescriptor.typeId(source) == SqlTypeDescriptor.TYPE_ID_BIGINT
              ? SqlTypeDescriptor.BIGINT
              : SqlTypeDescriptor.typeId(source) == SqlTypeDescriptor.TYPE_ID_DECIMAL
                  ? SqlTypeDescriptor.decimal(
                      SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 0) : 0;
      case SqlCommand.UPDATE_ROUND, SqlCommand.UPDATE_TRUNCATE ->
          ExactDecimal.quantizedDescriptor(source, (int) command.updateValue(index));
      case SqlCommand.UPDATE_CAST -> exactNumeric(source)
              && exactNumeric(command.updateExpressionTypeDescriptor(index))
              && SqlTypeDescriptor.canExplicitlyCast(
                  source, command.updateExpressionTypeDescriptor(index))
              ? command.updateExpressionTypeDescriptor(index) : 0;
      default -> 0;
    };
  }

  private static boolean compatibleAssignment(
      int source, int target, boolean nullValue, boolean defaultValue) {
    if (nullValue || defaultValue || SqlTypeDescriptor.canImplicitlyCast(source, target)) {
      return true;
    }
    return SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        && exactNumeric(source)
        && scale(source) <= SqlTypeDescriptor.parameterTwo(target);
  }

  private static boolean exactNumeric(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_BIGINT
        || type == SqlTypeDescriptor.TYPE_ID_DECIMAL;
  }

  private static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }

  private static void resetInsertMapping(BoundSqlStatement bound) {
    for (int index = 0; index < bound.insertSourceByColumn.length; index++) {
      bound.insertSourceByColumn[index] = -1;
    }
  }

  private static StatusCode mapInsertColumns(
      SqlCommand command, BoundSqlStatement bound) {
    if (command.columnCount() == 0) {
      return mapPositionalColumns(command, bound);
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

  private static StatusCode mapPositionalColumns(
      SqlCommand command, BoundSqlStatement bound) {
    if (command.insertColumnCount() != bound.table.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int column = 0; column < bound.table.columnCount(); column++) {
      bound.insertSourceByColumn[column] = column;
    }
    return StatusCode.OK;
  }

  private static StatusCode validateInsertRow(
      SqlCommand command, BoundSqlStatement bound, int row) {
    StatusCode status = validateKey(command, bound, row);
    if (!status.isOk()) {
      return status;
    }
    for (int column = 1; column < bound.table.columnCount(); column++) {
      status = validateValue(command, bound, row, column);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private static StatusCode validateKey(
      SqlCommand command, BoundSqlStatement bound, int row) {
    int source = bound.insertSourceByColumn[0];
    if (bound.table.hasIdentity()) {
      return source < 0 || command.insertIsDefault(row, source)
          ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source < 0 || command.insertIsNull(row, source)
        || command.insertIsDefault(row, source)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return SqlTypeDescriptor.canImplicitlyCast(
        command.insertTypeDescriptor(row, source),
        bound.table.typeDescriptor(0))
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
  }

  private static StatusCode validateValue(
      SqlCommand command, BoundSqlStatement bound, int row, int column) {
    int source = bound.insertSourceByColumn[column];
    if (source >= 0 && command.insertIsDefault(row, source)
        && !bound.table.hasDefault(column)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (hasIncompatibleInsertType(command, bound, row, column, source)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    boolean nullValue = source < 0
        ? !bound.table.hasDefault(column)
        : command.insertIsNull(row, source);
    return nullValue && !bound.table.isNullable(column)
        ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }

  private static boolean hasIncompatibleInsertType(
      SqlCommand command,
      BoundSqlStatement bound,
      int row,
      int column,
      int source) {
    return source >= 0
        && !command.insertIsNull(row, source)
        && !command.insertIsDefault(row, source)
        && !SqlTypeDescriptor.canImplicitlyCast(
            command.insertTypeDescriptor(row, source),
            bound.table.typeDescriptor(column));
  }
}
