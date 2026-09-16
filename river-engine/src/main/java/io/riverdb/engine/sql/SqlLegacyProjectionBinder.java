package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Binds the fixed column projection representation used by legacy commands. */
final class SqlLegacyProjectionBinder {
  private SqlLegacyProjectionBinder() { }

  static StatusCode bind(SqlCommand command, BoundSqlStatement bound) {
    int count = command.columnCount();
    StatusCode reserved = bound.reserveProjectionColumns(count);
    if (count <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!reserved.isOk()) return reserved;
    bound.projectionPrograms.begin(count);
    for (int index = 0; index < count; index++) {
      StatusCode status = bindColumn(command, bound, index);
      if (!status.isOk()) return status;
    }
    bound.projectedColumnCount = count;
    return bound.projectionPrograms.status();
  }

  private static StatusCode bindColumn(
      SqlCommand command, BoundSqlStatement bound, int index) {
    if (!hasValidQualifier(command, index)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int column = command.isNullProjection(index)
        ? BoundSqlStatement.NULL_PROJECTION
        : bound.table.findColumn(command.columnName(index));
    if (column < 0 && column != BoundSqlStatement.NULL_PROJECTION) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int previous = 0; previous < index; previous++) {
      if (bound.projectedColumns[previous] == column) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    bound.projectedColumns[index] = column;
    bound.projectedTypeDescriptors[index] = column < 0
        ? SqlTypeDescriptor.BIGINT : bound.table.typeDescriptor(column);
    bound.projectionPrograms.append(
        index,
        column < 0 ? SqlScalarExpression.NULL : SqlScalarExpression.COLUMN,
        column < 0 ? 0 : column,
        bound.projectedTypeDescriptors[index]);
    bound.projectionPrograms.finish(
        index, bound.projectedTypeDescriptors[index], column < 0 ? -1 : column);
    return StatusCode.OK;
  }

  private static boolean hasValidQualifier(SqlCommand command, int index) {
    CharSequence qualifier = command.columnTableName(index);
    return qualifier.length() == 0
        || SqlBindingNames.matchesTable(command, qualifier);
  }
}
