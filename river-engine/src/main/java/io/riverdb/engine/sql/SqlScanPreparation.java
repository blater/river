package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;

/** Selects and opens the physical operator for one fully bound SQL scan. */
final class SqlScanPreparation {
  private static final int NULL_PROJECTION = BoundSqlStatement.NULL_PROJECTION;

  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlPhysicalPlan plan;
  private final SqlActiveScanState scan;
  private final SqlSortExecution sorts;
  private final SqlJoinExecution joins;
  private final SqlExecutionResult aggregate;

  SqlScanPreparation(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlPhysicalPlan physicalPlan,
      SqlActiveScanState activeScan,
      SqlSortExecution sortExecution,
      SqlJoinExecution joinExecution,
      SqlExecutionResult aggregateExecution) {
    session = relationalSession;
    bound = statement;
    query = statement.executableQuery;
    plan = physicalPlan;
    scan = activeScan;
    sorts = sortExecution;
    joins = joinExecution;
    aggregate = aggregateExecution;
  }

  StatusCode begin(boolean explainOnly) {
    BoundSqlQuery.Block command = query.root();
    if (!query.isExecutable()
        && command.type() != SqlCommandType.NEXT_SEQUENCE_VALUE
        && command.type() != SqlCommandType.SCALAR_EXPRESSION
        && !isScalarAggregate(command.type())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    SqlCommandType type = command.type();
    if (type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || type == SqlCommandType.NEXT_SEQUENCE_VALUE
        || type == SqlCommandType.SCALAR_EXPRESSION
        || isValueAggregate(type)) {
      return beginScalar(command);
    }
    if (isGroupAggregate(type)) return beginGrouped(command, false, explainOnly);
    if (type == SqlCommandType.DISTINCT_SCAN) {
      return beginGrouped(command, true, explainOnly);
    }
    if (type == SqlCommandType.JOIN_SCAN) {
      StatusCode status = joins.begin();
      return status.isOk() ? scan.claim() : status;
    }
    return type == SqlCommandType.SCAN || type == SqlCommandType.SELECT
        ? beginRows(command, explainOnly) : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode beginScalar(BoundSqlQuery.Block command) {
    plan.setAccessColumn(bound.accessPredicate >= 0
        ? bound.predicateColumn == 0 || bound.table.hasIndexOn(bound.predicateColumn)
            ? bound.predicateColumn : -1
        : -1);
    plan.setAggregate(command.type() == SqlCommandType.COUNT
            || command.type() == SqlCommandType.SCALAR_EXPRESSION
        ? -1 : bound.projectedColumns[0]);
    plan.setResultColumn(
        0,
        bound.projectedColumnCount > 0 ? bound.projectedColumns[0] : -1,
        aggregate.typeDescriptorAt(0),
        aggregateColumnName(command));
    return scan.claimAggregate(
        aggregate.value(),
        aggregate.isNull(0),
        aggregate.transactionActive(),
        aggregate.commitSequence());
  }

  private StatusCode beginGrouped(
      BoundSqlQuery.Block command, boolean distinct, boolean explainOnly) {
    plan.setFilterCount(bound.predicateCount);
    int groupedColumn = distinct ? bound.distinctColumn : bound.groupColumn;
    int aggregateColumn = distinct ? -1 : bound.groupAggregateColumn;
    boolean ordered = groupedColumn == 0
        || groupedColumn > 0
            && bound.table.hasIndexOn(groupedColumn)
            && !bound.table.isNullable(groupedColumn);
    plan.setSort(!ordered);
    plan.setAccessColumn(ordered ? groupedColumn : -1);
    int sortedRows = -1;
    StatusCode status = ordered
        ? beginOrdered(command, groupedColumn, groupedColumn > 0)
        : beginMaterialized(groupedColumn, aggregateColumn, distinct, explainOnly);
    if (status.isOk() && !ordered && !explainOnly) sortedRows = sorts.totalRows();
    if (!status.isOk()) return status;
    configureGrouped(command, groupedColumn, aggregateColumn, distinct);
    return scan.claimSortedInput(sortedRows);
  }

  private void configureGrouped(
      BoundSqlQuery.Block command,
      int groupedColumn,
      int aggregateColumn,
      boolean distinct) {
    if (distinct) plan.setDistinct(groupedColumn);
    else plan.setGroupAggregate(groupedColumn, aggregateColumn);
    plan.setResultColumn(
        0,
        groupedColumn,
        bound.table.typeDescriptor(groupedColumn),
        command.columnOutputName(0));
    if (!distinct) {
      int inputDescriptor = aggregateColumn < 0
          ? SqlTypeDescriptor.BIGINT : bound.table.typeDescriptor(aggregateColumn);
      int type = SqlProjectionBinder.aggregateResultDescriptor(
          command.type(), inputDescriptor);
      plan.setResultColumn(
          1, aggregateColumn, type, groupAggregateColumnName(command));
    }
  }

  private StatusCode beginMaterialized(
      int groupedColumn, int aggregateColumn, boolean distinct, boolean explainOnly) {
    boolean bounded = bound.accessPredicate >= 0;
    int indexColumn = bounded
            && bound.predicateColumn > 0
            && bound.table.hasIndexOn(bound.predicateColumn)
        ? bound.predicateColumn : -1;
    boolean valueIndex = indexColumn > 0;
    plan.setAccessColumn(valueIndex
        ? indexColumn : bounded && bound.predicateColumn == 0 ? 0 : -1);
    StatusCode status = openRowSource(query.root(), indexColumn);
    if (!status.isOk() || explainOnly) return status;
    bound.projectedColumns[0] = groupedColumn;
    bound.projectedColumnCount = distinct ? 1 : 2;
    if (!distinct) {
      bound.projectedColumns[1] = aggregateColumn < 0
          ? NULL_PROJECTION : aggregateColumn;
    }
    return sorts.materialize(valueIndex, groupedColumn);
  }

  private StatusCode beginRows(BoundSqlQuery.Block command, boolean explainOnly) {
    plan.setCommand(command);
    plan.setNestedDepth(query.sourcePlanDepth());
    plan.setFilterCount(bound.predicateCount);
    configureRowResult(command);
    if (explainOnly && query.blockCount() > 1) {
      bound.accessPredicate = -1;
      bound.predicateColumn = -1;
    }
    int orderColumn = command.isOrdered() ? plan.orderColumn() : -1;
    if (command.isOrdered() && orderColumn < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean materialized = requiresMaterializedSort(command, orderColumn);
    plan.setSort(materialized);
    int indexColumn = scanIndexColumn(command, orderColumn, materialized);
    boolean valueIndex = indexColumn > 0;
    plan.setAccessColumn(valueIndex
        ? indexColumn
        : bound.accessPredicate >= 0 && bound.predicateColumn == 0 ? 0 : -1);
    StatusCode status = openRowSource(command, indexColumn);
    if (!status.isOk()) return status;
    if (!materialized || explainOnly) return scan.claim();
    status = sorts.materialize(valueIndex, orderColumn);
    return status.isOk() ? scan.claimSorted(sorts.totalRows()) : status;
  }

  private void configureRowResult(BoundSqlQuery.Block command) {
    for (int index = 0; index < bound.projectedColumnCount; index++) {
      int column = bound.projectedColumns[index];
      bound.projectedTypeDescriptors[index] = column >= 0
          ? bound.table.typeDescriptor(column) : SqlTypeDescriptor.BIGINT;
    }
    plan.setResultShape(
        bound.projectedColumns,
        bound.projectedTypeDescriptors,
        bound.projectedColumnCount,
        command);
    for (int index = 0; index < bound.projectedColumnCount; index++) {
      if (plan.resultNameLength(index) == 0) {
        int projection = bound.projectedColumns[index];
        plan.setResultColumn(
            index,
            projection,
            plan.resultType(index),
            projection == NULL_PROJECTION ? "null" : bound.table.columnName(projection));
      }
    }
  }

  private int scanIndexColumn(
      BoundSqlQuery.Block command, int orderColumn, boolean materialized) {
    if (command.isOrdered() && !materialized) return orderColumn > 0 ? orderColumn : -1;
    return bound.predicateColumn > 0
            && bound.table.hasIndexOn(bound.predicateColumn)
            && !bound.table.isVarchar(bound.predicateColumn)
        ? bound.predicateColumn : -1;
  }

  private boolean requiresMaterializedSort(
      BoundSqlQuery.Block command, int orderColumn) {
    return command.isOrdered()
        && (bound.table.isVarchar(orderColumn)
            || command.isDescendingOrder()
            || orderColumn > 0
                && (!bound.table.hasIndexOn(orderColumn)
                    || bound.table.isNullable(orderColumn)));
  }

  private StatusCode openRowSource(BoundSqlQuery.Block command, int indexColumn) {
    boolean bounded = bound.accessPredicate >= 0;
    boolean equality = bounded && command.isEqualityPredicate(bound.accessPredicate);
    if (indexColumn > 0) {
      if (!bounded || bound.predicateColumn != indexColumn) {
        return session.beginValueScan(bound.table, indexColumn, scan.relational());
      }
      if (equality && accessValue(command) == Long.MAX_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return session.beginValueScan(
          bound.table,
          indexColumn,
          equality ? accessValue(command) : accessLower(command),
          equality ? accessValue(command) + 1 : accessUpper(command),
          scan.relational());
    }
    if (!bounded || bound.predicateColumn != 0) {
      return session.beginScan(bound.table, scan.relational());
    }
    if (equality && accessValue(command) == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return session.beginScan(
        bound.table,
        equality ? accessValue(command) : accessLower(command),
        equality ? accessValue(command) + 1 : accessUpper(command),
        scan.relational());
  }

  private StatusCode beginOrdered(
      BoundSqlQuery.Block command, int column, boolean valueIndex) {
    int predicate = orderedBoundPredicate(command, column);
    if (predicate < 0) {
      return valueIndex
          ? session.beginValueScan(bound.table, column, scan.relational())
          : session.beginScan(bound.table, scan.relational());
    }
    boolean equality = command.isEqualityPredicate(predicate);
    long lower = equality
        ? command.predicateValue(predicate) : command.predicateLowerInclusive(predicate);
    if (equality && lower == Long.MAX_VALUE) return StatusCode.INVALID_EXTERNAL_INPUT;
    long upper = equality ? lower + 1 : command.predicateUpperExclusive(predicate);
    return valueIndex
        ? session.beginValueScan(bound.table, column, lower, upper, scan.relational())
        : session.beginScan(bound.table, lower, upper, scan.relational());
  }

  private int orderedBoundPredicate(BoundSqlQuery.Block command, int column) {
    if (command.hasDisjunction()) return -1;
    int bounded = -1;
    for (int index = 0; index < bound.predicateCount; index++) {
      if (bound.predicateColumns[index] == column
          && (command.isEqualityPredicate(index) || command.isRangePredicate(index))
          && (bounded < 0 || command.isEqualityPredicate(index))) {
        bounded = index;
        if (command.isEqualityPredicate(index)) return index;
      }
    }
    return bounded;
  }

  private long accessValue(BoundSqlQuery.Block command) {
    return bound.accessValue;
  }

  private long accessLower(BoundSqlQuery.Block command) {
    return bound.accessLowerInclusive;
  }

  private long accessUpper(BoundSqlQuery.Block command) {
    return bound.accessUpperExclusive;
  }

  private static CharSequence aggregateColumnName(BoundSqlQuery.Block command) {
    CharSequence alias = command.columnAlias(0);
    if (alias.length() > 0) return alias;
    return command.type() == SqlCommandType.SUM ? "sum"
        : command.type() == SqlCommandType.AVG ? "avg"
        : command.type() == SqlCommandType.SCALAR_EXPRESSION ? "expression"
        : command.type() == SqlCommandType.MIN ? "min"
            : command.type() == SqlCommandType.MAX ? "max" : "count";
  }

  private static CharSequence groupAggregateColumnName(BoundSqlQuery.Block command) {
    CharSequence alias = command.columnAlias(1);
    if (command.type() != SqlCommandType.GROUP_COUNT && alias.length() > 0) return alias;
    return command.type() == SqlCommandType.GROUP_SUM ? "sum"
        : command.type() == SqlCommandType.GROUP_AVG ? "avg"
        : command.type() == SqlCommandType.GROUP_MIN ? "min"
            : command.type() == SqlCommandType.GROUP_MAX ? "max" : "count";
  }

  private static boolean isValueAggregate(SqlCommandType type) {
    return type == SqlCommandType.SUM
        || type == SqlCommandType.AVG
        || type == SqlCommandType.MIN
        || type == SqlCommandType.MAX;
  }

  private static boolean isScalarAggregate(SqlCommandType type) {
    return type == SqlCommandType.COUNT || type == SqlCommandType.COUNT_VALUE
        || isValueAggregate(type);
  }

  private static boolean isGroupAggregate(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }
}
