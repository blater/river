package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves a bounded grouping tuple and its raw or computed aggregate operands. */
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
    boolean computedKey = hasComputedKey(command);
    boolean computedAggregate = hasComputedAggregate(command);
    boolean computedHaving = command.booleanHavingPredicates().isAvailable();
    StatusCode status = validateScope(
        query, computedKey, computedAggregate, computedHaving);
    if (status.isOk()) status = aggregates.bind(command, bound, true);
    if (!status.isOk()) return status;
    status = bindGroupKeys(command, bound);
    if (status.isOk()) status = having.bind(command, bound);
    if (!status.isOk()) return status;
    if (command.isOrdered()) {
      for (int key = 0; key < command.groupExpressionCount(); key++) {
        if (SqlTypeDescriptor.comparisonFamily(
            bound.projectionPrograms.resultDescriptor(key))
            == SqlTypeDescriptor.COMPARISON_BOOLEAN) {
          return StatusCode.DATATYPE_MISMATCH;
        }
      }
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

  private static StatusCode bindGroupKeys(
      SqlCommand command, BoundSqlStatement bound) {
    int count = command.groupExpressionCount();
    if (count <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int key = 0; key < count; key++) {
      if (!SqlTypeDescriptor.isValid(
          bound.projectionPrograms.resultDescriptor(key))) {
        return StatusCode.DATATYPE_MISMATCH;
      }
    }
    int first = bound.projectionPrograms.rawColumn(0);
    bound.groupColumn = count == 1 && first >= 0
        ? first : SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
    bound.sortKeyProjection = count == 1 && first >= 0 ? -1 : 0;
    return StatusCode.OK;
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

  private static boolean hasComputedKey(SqlCommand command) {
    for (int key = 0; key < command.groupExpressionCount(); key++) {
      SqlScalarExpression expression = command.groupExpression(key);
      if (expression != null && expression.isAvailable()
          && !expression.isDirectColumnReference()) return true;
    }
    return false;
  }
}
