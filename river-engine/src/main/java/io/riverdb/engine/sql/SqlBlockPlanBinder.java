package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Bottom-up binder for compact cardinality-changing query-block plans. */
final class SqlBlockPlanBinder {
  private final SqlBlockExpressionBinder expressions = new SqlBlockExpressionBinder();
  private final SqlBlockAggregateBinder aggregates = new SqlBlockAggregateBinder(expressions);
  private final SqlBlockProjectionBinder projections = new SqlBlockProjectionBinder(expressions);
  private final SqlBlockPredicateBinder predicates = new SqlBlockPredicateBinder();
  private final SqlBooleanPredicateEvaluator predicatePreflight;

  SqlBlockPlanBinder(SqlTemporalContext temporal) {
    predicatePreflight = temporal == null ? null
        : new SqlBooleanPredicateEvaluator(new SqlExpressionEvaluator(), temporal);
  }

  StatusCode bind(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlRowProjectionEvaluator evaluator) {
    SqlBoundBlockPlans plans = bound.blockPlans();
    StatusCode status = plans.capture(bound.query);
    if (!status.isOk()) return status;
    SqlCommand deepest = plans.command(plans.count() - 1);
    status = session.resolveTable(deepest.tableName(), bound.table);
    if (status.isOk()) physicalSchema(bound);
    SqlBlockSchema child = plans.baseSchema();
    for (int block = plans.count() - 1;
        status.isOk() && block >= 0; block--) {
      status = activate(bound, block, child);
      if (status.isOk() && evaluator != null) status = evaluator.prepare(bound);
      if (status.isOk() && predicatePreflight != null) {
        status = predicatePreflight.prepare(bound.command, bound.whereBoolean);
      }
      if (status.isOk() && predicatePreflight != null) {
        status = predicatePreflight.prepare(bound.command, bound.havingBoolean);
      }
      child = plans.schema(block);
    }
    if (status.isOk()) status = activate(bound, 0, plans.schema(1));
    if (predicatePreflight != null) predicatePreflight.reset();
    return status;
  }

  StatusCode validateTail(
      BoundSqlStatement bound, int firstBlock) {
    SqlBoundBlockPlans plans = bound.blockPlans();
    StatusCode status = plans.captureForValidation(bound.query);
    if (!status.isOk() || firstBlock < 0
        || firstBlock >= plans.count()) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    physicalSchema(bound);
    SqlBlockSchema child = plans.baseSchema();
    for (int block = plans.count() - 1;
        status.isOk() && block >= firstBlock; block--) {
      status = activate(bound, block, child);
      child = plans.schema(block);
    }
    return status;
  }

  StatusCode activate(BoundSqlStatement bound, int block, SqlBlockSchema child) {
    SqlBoundBlockPlans plans = bound.blockPlans();
    SqlCommand source = plans.command(block);
    StatusCode status = bound.command.copyBlockFrom(source);
    if (!status.isOk()) return status;
    resetActive(bound);
    SqlCommandType type = bound.command.type();
    SqlBlockSchema output = plans.schema(block);
    if (SqlBinder.isScalarAggregate(type)) {
      status = aggregates.bind(bound.command, child, output, bound, false);
    } else if (SqlBinder.isGroupAggregate(type)) {
      status = aggregates.bind(bound.command, child, output, bound, true);
    } else {
      status = projections.bind(bound.command, child, output, bound);
    }
    if (status.isOk()) projections.publishOperandSchema(bound, block, child, type);
    if (status.isOk() && block == 0 && bound.command.isOrdered()
        && output.find(bound.command.orderColumnName()) < 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status.isOk() ? predicates.bind(bound.command, child, bound, block) : status;
  }

  private void physicalSchema(BoundSqlStatement bound) {
    SqlBlockSchema physical = bound.blockPlans().baseSchema();
    physical.set(bound.table.columnCount());
    for (int column = 0; column < bound.table.columnCount(); column++) {
      physical.setColumn(
          column,
          bound.table.columnName(column),
          bound.table.typeDescriptor(column),
          bound.table.isNullable(column));
    }
  }

  private static void resetActive(BoundSqlStatement bound) {
    bound.projectionPrograms.reset();
    bound.aggregates.reset();
    bound.whereBoolean.reset();
    bound.havingBoolean.reset();
    bound.projectedColumnCount = 0;
    bound.predicateCount = 0;
    bound.groupColumn = -1;
    bound.groupAggregateColumn = -1;
    bound.distinctColumn = -1;
    bound.orderColumn = -1;
    bound.sortKeyProjection = -1;
  }
}
