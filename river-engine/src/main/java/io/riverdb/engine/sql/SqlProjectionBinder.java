package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlQuery;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves projection, grouping, distinct, and ordering columns. */
final class SqlProjectionBinder {
  private static final int AMBIGUOUS_ALIAS = -2;
  private final SqlPredicateBinder predicates;
  private final SqlRowProjectionBinder rows = new SqlRowProjectionBinder();
  private final SqlGroupedAggregateBinder groups;

  SqlProjectionBinder(SqlPredicateBinder predicateBinder) {
    predicates = predicateBinder;
    groups = new SqlGroupedAggregateBinder(predicateBinder);
  }

  StatusCode bind(SqlCommand command, BoundSqlStatement bound) {
    if (!command.isSelectAll()
        && command.columnCount() > 0
        && (command.projectionExpression(0) == null
            || !command.projectionExpression(0).isAvailable())) {
      return bindLegacy(command, bound);
    }
    if (SqlRowProjectionBinder.hasComputed(command)
        && bound.executableQuery.sourceBlockCount() > 1) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return rows.bind(command, bound);
  }

  private static StatusCode bindLegacy(
      SqlCommand command, BoundSqlStatement bound) {
    int count = command.columnCount();
    if (count <= 0 || count > bound.projectedColumns.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bound.projectionPrograms.begin(count);
    for (int index = 0; index < count; index++) {
      if (!hasValidQualifier(command, index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
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
    }
    bound.projectedColumnCount = count;
    return StatusCode.OK;
  }

  StatusCode bindOrder(SqlCommand command, BoundSqlStatement bound) {
    int column = bound.table.findColumn(command.orderColumnName());
    int aliasProjection = resolveOrderAlias(command);
    if (aliasProjection == AMBIGUOUS_ALIAS
        || aliasProjection >= 0
            && column >= 0
            && bound.projectionPrograms.rawColumn(aliasProjection) < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (aliasProjection >= 0 && column < 0) {
      return rows.bindOrderAlias(command, bound, aliasProjection);
    }
    if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    bound.orderColumn = column;
    bound.sortKeyProjection = -1;
    return StatusCode.OK;
  }

  StatusCode bindJoinOrder(SqlCommand command, BoundSqlStatement bound) {
    int projection = resolveOrderAlias(command);
    if (projection < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    bound.orderColumn = SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
    bound.sortKeyProjection = projection;
    return StatusCode.OK;
  }

  StatusCode bindGroup(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    return groups.bind(command, query, bound);
  }

  StatusCode bindDistinct(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    boolean computed = SqlRowProjectionBinder.hasComputed(command);
    if (computed && query.sourceBlockCount() > 1) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    StatusCode status = rows.bind(command, bound);
    if (!status.isOk()) {
      return status;
    }
    int column = bound.projectionPrograms.rawColumn(0);
    if (column < 0) {
      status = rows.validateComputedKey(command, bound, 0);
      if (!status.isOk()) return status;
      column = SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
      bound.sortKeyProjection = 0;
    }
    bound.distinctColumn = column;
    return predicates.bind(command, query, bound);
  }

  private static boolean hasValidQualifier(SqlCommand command, int index) {
    CharSequence qualifier = command.columnTableName(index);
    return qualifier.length() == 0
        || SqlBindingNames.matchesTable(command, qualifier);
  }

  private static int resolveOrderAlias(SqlCommand command) {
    int resolved = -1;
    for (int index = 0; index < command.columnCount(); index++) {
      if (!SqlBindingNames.same(
          command.columnOutputName(index), command.orderColumnName())) {
        continue;
      }
      if (resolved >= 0 || command.isNullProjection(index)) {
        return AMBIGUOUS_ALIAS;
      }
      resolved = index;
    }
    return resolved;
  }

  static int aggregateResultDescriptor(SqlCommandType type, int inputDescriptor) {
    if (type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE) {
      return SqlTypeDescriptor.BIGINT;
    }
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

  static int aggregateResultDescriptor(int kind, int inputDescriptor) {
    return switch (kind) {
      case io.riverdb.sql.SqlAggregateKind.COUNT,
          io.riverdb.sql.SqlAggregateKind.COUNT_VALUE -> SqlTypeDescriptor.BIGINT;
      case io.riverdb.sql.SqlAggregateKind.MIN,
          io.riverdb.sql.SqlAggregateKind.MAX -> inputDescriptor;
      case io.riverdb.sql.SqlAggregateKind.AVG -> aggregateResultDescriptor(
          SqlCommandType.AVG, inputDescriptor);
      case io.riverdb.sql.SqlAggregateKind.SUM -> aggregateResultDescriptor(
          SqlCommandType.SUM, inputDescriptor);
      default -> 0;
    };
  }
}
