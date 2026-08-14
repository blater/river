package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlQuery;

/** Resolves parser-owned names and literals into reusable execution state. */
final class SqlBinder {
  private final SqlQueryBlockBinder queryBlocks = new SqlQueryBlockBinder();
  private final SqlPredicateBinder predicates = new SqlPredicateBinder();
  private final SqlMutationBinder mutations = new SqlMutationBinder();
  private final SqlProjectionBinder projections =
      new SqlProjectionBinder(predicates);

  StatusCode captureExecutableQuery(BoundSqlStatement bound) {
    return bound.executableQuery.capture(bound.command, bound.query);
  }

  void publishExecutableQuery(BoundSqlStatement bound) {
    bound.executableQuery.publishBinding();
  }

  StatusCode bindQueryBlocks(RelationalSession session, BoundSqlStatement bound) {
    return queryBlocks.bind(session, bound);
  }

  StatusCode bindDataCommand(
      SqlCommand command,
      SqlQuery query,
      BoundSqlStatement bound) {
    bound.updatedColumnCount = 0;
    bound.predicateColumn = -1;
    bound.predicateCount = 0;
    bound.accessPredicate = -1;
    bound.projectedColumnCount = 0;
    return switch (command.type()) {
      case COUNT, DELETE -> predicates.bind(command, query, bound);
      case COUNT_VALUE, SUM, AVG, MIN, MAX -> bindValueAggregate(command, query, bound);
      case INSERT -> mutations.bindInsert(command, bound);
      case SELECT, SCAN -> bindProjectedCommand(command, query, bound);
      case UPDATE -> bindUpdate(command, query, bound);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
  }

  private StatusCode bindValueAggregate(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    StatusCode status = bindProjections(command, bound);
    if (!status.isOk()) {
      return status;
    }
    if ((command.type() == SqlCommandType.SUM
            || command.type() == SqlCommandType.AVG)
        && SqlTypeDescriptor.comparisonFamily(
            bound.table.typeDescriptor(bound.projectedColumns[0]))
            != SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if ((command.type() == SqlCommandType.MIN
            || command.type() == SqlCommandType.MAX)
        && SqlTypeDescriptor.comparisonFamily(
            bound.table.typeDescriptor(bound.projectedColumns[0]))
            == SqlTypeDescriptor.COMPARISON_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return predicates.bind(command, query, bound);
  }

  private StatusCode bindProjectedCommand(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    StatusCode status = bindProjections(command, bound);
    return status.isOk() ? predicates.bind(command, query, bound) : status;
  }

  private StatusCode bindUpdate(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    StatusCode status = predicates.bind(command, query, bound);
    if (!status.isOk() || command.updateColumnCount() <= 0
        || command.updateColumnCount() != command.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return mutations.bindUpdate(command, bound);
  }

  StatusCode bindProjections(SqlCommand command, BoundSqlStatement bound) {
    return projections.bind(command, bound);
  }

  StatusCode bindOrder(SqlCommand command, BoundSqlStatement bound) {
    return projections.bindOrder(command, bound);
  }

  StatusCode bindGroupAggregate(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    return projections.bindGroup(command, query, bound);
  }

  StatusCode bindDistinct(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    return projections.bindDistinct(command, query, bound);
  }

  StatusCode bindJoin(SqlCommand command, BoundSqlStatement bound) {
    if (SqlBindingNames.matchesTable(command, command.joinTableName())
        || command.joinTableAlias().length() > 0
            && SqlBindingNames.matchesTable(command, command.joinTableAlias())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int outerJoinColumn = bound.table.findColumn(command.joinOuterColumnName());
    int innerJoinColumn = bound.joinTable.findColumn(command.joinInnerColumnName());
    if (outerJoinColumn < 0 || innerJoinColumn < 0 || command.columnCount() <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!SqlTypeDescriptor.canCompare(
        bound.table.typeDescriptor(outerJoinColumn),
        bound.joinTable.typeDescriptor(innerJoinColumn))) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    for (int index = 0; index < command.columnCount(); index++) {
      int descriptor = resolveJoinProjection(command, bound, index);
      if (descriptor == Integer.MIN_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      bound.projectedColumns[index] = descriptor;
    }
    bound.projectedColumnCount = command.columnCount();
    StatusCode status = predicates.bindJoin(command, bound);
    if (status.isOk()) {
      bound.joinOuterColumn = outerJoinColumn;
      bound.joinInnerColumn = innerJoinColumn;
    }
    return status;
  }

  private static int resolveJoinProjection(
      SqlCommand command, BoundSqlStatement bound, int index) {
    if (command.isNullProjection(index)) {
      return Integer.MIN_VALUE;
    }
    if (SqlBindingNames.matchesTable(command, command.columnTableName(index))) {
      int column = bound.table.findColumn(command.columnName(index));
      return column < 0 ? Integer.MIN_VALUE : column;
    }
    if (SqlBindingNames.matchesJoinTable(
        command, command.columnTableName(index))) {
      int column = bound.joinTable.findColumn(command.columnName(index));
      return column < 0 ? Integer.MIN_VALUE : -column - 1;
    }
    return Integer.MIN_VALUE;
  }

  static boolean isValueAggregate(SqlCommandType type) {
    return type == SqlCommandType.SUM
        || type == SqlCommandType.AVG
        || type == SqlCommandType.MIN
        || type == SqlCommandType.MAX;
  }

  static boolean isScalarAggregate(SqlCommandType type) {
    return type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || isValueAggregate(type);
  }

  static boolean isGroupAggregate(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

}
