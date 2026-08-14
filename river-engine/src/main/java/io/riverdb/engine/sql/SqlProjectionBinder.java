package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlQuery;

/** Resolves projection, grouping, distinct, and ordering columns. */
final class SqlProjectionBinder {
  private static final int INVALID_PROJECTION = Integer.MAX_VALUE;
  private final SqlPredicateBinder predicates;

  SqlProjectionBinder(SqlPredicateBinder predicateBinder) {
    predicates = predicateBinder;
  }

  StatusCode bind(SqlCommand command, BoundSqlStatement bound) {
    int count = command.isSelectAll()
        ? bound.table.columnCount() : command.columnCount();
    if (count <= 0 || count > bound.projectedColumns.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < count; index++) {
      int column = resolve(command, bound, index);
      if (column == INVALID_PROJECTION || isDuplicate(bound, index, column)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      bound.projectedColumns[index] = column;
    }
    bound.projectedColumnCount = count;
    return StatusCode.OK;
  }

  StatusCode bindOrder(SqlCommand command, BoundSqlStatement bound) {
    int column = bound.table.findColumn(command.orderColumnName());
    if (column >= 0) {
      bound.orderColumn = column;
      return StatusCode.OK;
    }
    int aliasColumn = resolveOrderAlias(command, bound);
    if (aliasColumn < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bound.orderColumn = aliasColumn;
    return StatusCode.OK;
  }

  StatusCode bindGroup(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    StatusCode status = predicates.bind(command, query, bound);
    if (!status.isOk()) {
      return status;
    }
    if (!hasValidQualifier(command, 0)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int groupColumn = bound.table.findColumn(command.firstColumnName());
    if (groupColumn < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int aggregateColumn = -1;
    if (command.type() != SqlCommandType.GROUP_COUNT) {
      aggregateColumn = resolveGroupAggregate(command, bound);
      if (aggregateColumn == Integer.MIN_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int inputDescriptor = bound.table.typeDescriptor(aggregateColumn);
      if ((command.type() == SqlCommandType.GROUP_SUM
              || command.type() == SqlCommandType.GROUP_AVG)
          && SqlTypeDescriptor.comparisonFamily(inputDescriptor)
              != SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      if ((command.type() == SqlCommandType.GROUP_MIN
              || command.type() == SqlCommandType.GROUP_MAX)
          && SqlTypeDescriptor.comparisonFamily(inputDescriptor)
              == SqlTypeDescriptor.COMPARISON_BOOLEAN) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      if (command.hasGroupHaving()
          && !SqlTypeDescriptor.canCompare(
              aggregateResultDescriptor(command.type(), inputDescriptor),
              command.groupHavingTypeDescriptor())) {
        return StatusCode.DATATYPE_MISMATCH;
      }
    }
    if (command.isOrdered()
        && SqlTypeDescriptor.comparisonFamily(bound.table.typeDescriptor(groupColumn))
            == SqlTypeDescriptor.COMPARISON_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    bound.groupColumn = groupColumn;
    bound.groupAggregateColumn = aggregateColumn;
    return StatusCode.OK;
  }

  StatusCode bindDistinct(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    StatusCode status = predicates.bind(command, query, bound);
    if (!status.isOk()) {
      return status;
    }
    if (!hasValidQualifier(command, 0)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int column = bound.table.findColumn(command.firstColumnName());
    if (column < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bound.distinctColumn = column;
    return StatusCode.OK;
  }

  private static int resolve(
      SqlCommand command, BoundSqlStatement bound, int index) {
    if (command.isSelectAll()) {
      return index;
    }
    if (!hasValidQualifier(command, index)) {
      return INVALID_PROJECTION;
    }
    if (command.isNullProjection(index)) {
      return command.isOrdered()
          ? INVALID_PROJECTION : BoundSqlStatement.NULL_PROJECTION;
    }
    int column = bound.table.findColumn(command.columnName(index));
    return column < 0 ? INVALID_PROJECTION : column;
  }

  private static boolean hasValidQualifier(SqlCommand command, int index) {
    CharSequence qualifier = command.columnTableName(index);
    return qualifier.length() == 0
        || SqlBindingNames.matchesTable(command, qualifier);
  }

  private static boolean isDuplicate(
      BoundSqlStatement bound, int index, int column) {
    for (int previous = 0; previous < index; previous++) {
      if (bound.projectedColumns[previous] == column) {
        return true;
      }
    }
    return false;
  }

  private static int resolveOrderAlias(
      SqlCommand command, BoundSqlStatement bound) {
    int resolved = -1;
    for (int index = 0; index < command.columnCount(); index++) {
      if (!SqlBindingNames.same(
          command.columnOutputName(index), command.orderColumnName())) {
        continue;
      }
      if (resolved >= 0 || command.isNullProjection(index)) {
        return -1;
      }
      resolved = bound.table.findColumn(command.columnName(index));
    }
    return resolved;
  }

  private static int resolveGroupAggregate(
      SqlCommand command, BoundSqlStatement bound) {
    if (command.columnCount() != 2 || !hasValidQualifier(command, 1)) {
      return Integer.MIN_VALUE;
    }
    int column = bound.table.findColumn(command.columnName(1));
    return column < 0 ? Integer.MIN_VALUE : column;
  }

  static int aggregateResultDescriptor(SqlCommandType type, int inputDescriptor) {
    if (type == SqlCommandType.GROUP_MIN || type == SqlCommandType.GROUP_MAX
        || type == SqlCommandType.MIN || type == SqlCommandType.MAX) {
      return inputDescriptor;
    }
    if (type == SqlCommandType.AVG || type == SqlCommandType.GROUP_AVG) {
      int inputScale = SqlTypeDescriptor.typeId(inputDescriptor)
              == SqlTypeDescriptor.TYPE_ID_DECIMAL
          ? SqlTypeDescriptor.parameterTwo(inputDescriptor) : 0;
      int integerDigits = SqlTypeDescriptor.typeId(inputDescriptor)
              == SqlTypeDescriptor.TYPE_ID_DECIMAL
          ? SqlTypeDescriptor.parameterOne(inputDescriptor) - inputScale : 19;
      int scale = Math.min(
          Math.max(inputScale, 6),
          Math.max(0, SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION - integerDigits));
      return SqlTypeDescriptor.decimal(
          SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, scale);
    }
    return SqlTypeDescriptor.typeId(inputDescriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.decimal(
            SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION,
            SqlTypeDescriptor.parameterTwo(inputDescriptor))
        : SqlTypeDescriptor.BIGINT;
  }
}
