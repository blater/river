package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;

/** Binds source-row-free INSERT values and their fixed-width programs. */
final class SqlInsertMutationBinder {
  private final SqlMutationExpressionBinder expressions =
      new SqlMutationExpressionBinder();

  StatusCode bind(SqlCommand command, BoundSqlStatement bound) {
    bound.projectionPrograms.beginMutations(command.mutationExpressionCount());
    StatusCode status = SqlInsertColumnMapping.map(command, bound);
    for (int row = 0;
        status.isOk() && row < command.insertRowCount(); row++) {
      status = validateRow(command, bound, row);
    }
    return status;
  }

  private StatusCode validateRow(
      SqlCommand command, BoundSqlStatement bound, int row) {
    StatusCode status = validateKey(command, bound, row);
    for (int column = 1;
        status.isOk() && column < bound.table.columnCount(); column++) {
      status = validateValue(command, bound, row, column);
    }
    return status;
  }

  private StatusCode validateKey(
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
    if (command.insertHasExpression(row, source)) {
      return bindExpression(command, bound, row, source, 0);
    }
    return SqlTypeDescriptor.canImplicitlyCast(
        command.insertTypeDescriptor(row, source),
        bound.table.typeDescriptor(0))
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
  }

  private StatusCode validateValue(
      SqlCommand command, BoundSqlStatement bound, int row, int column) {
    int source = bound.insertSourceByColumn[column];
    if (source >= 0 && command.insertIsDefault(row, source)
        && !bound.table.hasDefault(column)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = source >= 0 && command.insertHasExpression(row, source)
        ? bindExpression(command, bound, row, source, column) : StatusCode.OK;
    if (!status.isOk()) return status;
    if (incompatibleLiteral(command, bound, row, column, source)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    boolean nullValue = source < 0
        ? !bound.table.hasDefault(column)
        : command.insertIsNull(row, source);
    return nullValue && !bound.table.isNullable(column)
        ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }

  private StatusCode bindExpression(
      SqlCommand command,
      BoundSqlStatement bound,
      int row,
      int source,
      int column) {
    int expression = command.insertExpression(row, source);
    StatusCode status = expressions.bind(command, bound, expression, false);
    if (!status.isOk()) return status;
    int result = bound.projectionPrograms.mutationResultDescriptor(expression);
    return SqlMutationAssignmentTypes.compatible(
            result, bound.table.typeDescriptor(column), false, false)
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
  }

  private static boolean incompatibleLiteral(
      SqlCommand command,
      BoundSqlStatement bound,
      int row,
      int column,
      int source) {
    if (source < 0 || command.insertIsDefault(row, source)
        || command.insertHasExpression(row, source)) {
      return false;
    }
    int descriptor = command.insertTypeDescriptor(row, source);
    return command.insertIsNull(row, source)
        ? !SqlTypedNullAssignment.compatible(
            descriptor, bound.table.typeDescriptor(column))
        : !SqlTypeDescriptor.canImplicitlyCast(
            descriptor, bound.table.typeDescriptor(column));
  }
}
