package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves one raw grouping key and its raw or computed aggregate operand. */
final class SqlGroupedAggregateBinder {
  private final SqlPredicateBinder predicates;
  private final SqlRowProjectionBinder rows = new SqlRowProjectionBinder();
  private final SqlAggregateSetBinder aggregates = new SqlAggregateSetBinder(rows);
  private final SqlPostAggregateProgramBinder having =
      new SqlPostAggregateProgramBinder();

  SqlGroupedAggregateBinder(SqlPredicateBinder predicateBinder) {
    predicates = predicateBinder;
  }

  StatusCode bind(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    boolean computedKey = computedKey(command);
    boolean computedAggregate = hasComputedAggregate(command);
    boolean computedHaving = command.booleanHavingPredicates().isAvailable();
    StatusCode status = validateScope(
        query, computedKey, computedAggregate, computedHaving);
    if (status.isOk()) status = aggregates.bind(command, bound, true);
    if (!status.isOk()) return status;
    status = bindGroupKey(command, bound, computedKey);
    if (status.isOk()) status = having.bind(command, bound);
    if (!status.isOk()) return status;
    if (command.isOrdered()
        && SqlTypeDescriptor.comparisonFamily(
            SqlPrimitiveSortKey.descriptor(bound, bound.groupColumn))
            == SqlTypeDescriptor.COMPARISON_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return predicates.bind(command, query, bound);
  }

  private static StatusCode validateScope(
      SqlQuery query,
      boolean computedKey,
      boolean computedAggregate,
      boolean computedHaving) {
    return query.sourceBlockCount() > 1
            && (computedKey || computedAggregate || computedHaving)
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
  }

  private StatusCode bindGroupKey(
      SqlCommand command, BoundSqlStatement bound, boolean computed) {
    int column = computed
        ? SqlBoundProjectionPrograms.COMPUTED_PROJECTION
        : resolveGroupColumn(command, bound);
    if (column < 0 && !SqlPrimitiveSortKey.computed(column)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (computed) {
      StatusCode status = rows.validateComputedKey(command, bound, 0);
      if (!status.isOk()) return status;
      bound.sortKeyProjection = 0;
    }
    bound.groupColumn = column;
    bound.projectedTypeDescriptors[0] =
        bound.projectionPrograms.resultDescriptor(0);
    return StatusCode.OK;
  }

  private static int resolveGroupColumn(
      SqlCommand command, BoundSqlStatement bound) {
    if (!validQualifier(command, 0)) return -1;
    return bound.table.findColumn(command.firstColumnName());
  }

  private static boolean hasComputedAggregate(SqlCommand command) {
    for (int invocation = 0;
        invocation < command.aggregateInvocationCount(); invocation++) {
      int lane = command.aggregateOperandProjection(invocation);
      if (lane >= 0
          && !command.aggregateOperandExpression(lane).isDirectColumnReference()) {
        return true;
      }
    }
    return false;
  }

  private static boolean computedKey(SqlCommand command) {
    SqlScalarExpression expression = command.projectionExpression(0);
    return expression != null && expression.isAvailable()
        && !expression.isDirectColumnReference();
  }

  private static boolean validQualifier(SqlCommand command, int index) {
    CharSequence qualifier = command.columnTableName(index);
    return qualifier.length() == 0
        || SqlBindingNames.matchesTable(command, qualifier);
  }
}
