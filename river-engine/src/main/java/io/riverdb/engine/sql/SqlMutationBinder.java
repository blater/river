package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Maps INSERT and UPDATE values onto resolved table columns. */
final class SqlMutationBinder {
  private final SqlMutationExpressionBinder expressions =
      new SqlMutationExpressionBinder();
  private final SqlInsertMutationBinder inserts = new SqlInsertMutationBinder();

  StatusCode bindUpdate(SqlCommand command, BoundSqlStatement bound) {
    int capacity = Math.max(command.updateColumnCount(), command.mutationExpressionCount());
    StatusCode reserved = bound.reserveMutationColumns(capacity);
    if (!reserved.isOk()) return reserved;
    bound.projectionPrograms.beginMutations(command.mutationExpressionCount());
    for (int index = 0; index < command.updateColumnCount(); index++) {
      StatusCode status = bindUpdateColumn(command, bound, index);
      if (!status.isOk()) {
        return status;
      }
    }
    bound.updatedColumnCount = command.updateColumnCount();
    return bound.projectionPrograms.status();
  }

  StatusCode bindInsert(SqlCommand command, BoundSqlStatement bound) {
    return inserts.bind(command, bound);
  }

  StatusCode bindDescriptorExpressions(
      SqlCommand command, BoundSqlStatement bound, boolean columnsAllowed) {
    int count = command.mutationExpressionCount();
    StatusCode status = bound.reserveMutationColumns(count);
    if (status.isOk()) bound.projectionPrograms.beginMutations(count);
    for (int expression = 0; status.isOk() && expression < count; expression++) {
      status = expressions.bind(command, bound, expression, columnsAllowed);
    }
    return status.isOk() ? bound.projectionPrograms.status() : status;
  }

  private StatusCode bindUpdateColumn(
      SqlCommand command, BoundSqlStatement bound, int index) {
    int column = bound.table.findColumn(command.columnName(index));
    if (column <= 0 || isDuplicate(bound, index, column)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bound.updatedColumns[index] = column;
    if (command.updateHasExpression(index)) {
      int expression = command.updateExpression(index);
      StatusCode status = expressions.bind(command, bound, expression, true);
      if (!status.isOk()) return status;
      bound.updateResultTypeDescriptors[index] =
          bound.projectionPrograms.mutationResultDescriptor(expression);
      return SqlMutationAssignmentTypes.compatible(
              bound.updateResultTypeDescriptors[index],
              bound.table.typeDescriptor(column),
              false,
              false)
          ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
    }
    StatusCode status = bindLiteralUpdate(command, bound, index, column);
    if (!status.isOk()) return status;
    if (command.updateIsNull(index) && !bound.table.isNullable(column)
        || command.updateIsDefault(index) && !bound.table.hasDefault(column)
            && !bound.table.isNullable(column)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return SqlMutationAssignmentTypes.compatible(
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

  private static StatusCode bindLiteralUpdate(
      SqlCommand command, BoundSqlStatement bound, int index, int column) {
    int supplied = command.updateTypeDescriptor(index);
    if (command.updateIsNull(index)
        && !SqlTypedNullAssignment.compatible(
            supplied, bound.table.typeDescriptor(column))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    bound.updateResultTypeDescriptors[index] =
        command.updateIsNull(index) || command.updateIsDefault(index)
            ? bound.table.typeDescriptor(column)
            : command.updateTypeDescriptor(index);
    return StatusCode.OK;
  }

}
