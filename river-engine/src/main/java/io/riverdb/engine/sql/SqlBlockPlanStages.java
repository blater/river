package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Binds one block and owns the state used while preflighting its expressions. */
final class SqlBlockPlanStages {
  private final SqlBlockExpressionBinder expressions = new SqlBlockExpressionBinder();
  private final SqlBlockAggregateBinder aggregates = new SqlBlockAggregateBinder(expressions);
  private final SqlBlockProjectionBinder projections = new SqlBlockProjectionBinder(expressions);
  private final SqlBlockPredicateBinder predicates = new SqlBlockPredicateBinder();
  private final SqlBlockJoinBinder joins;
  private final SqlBooleanPredicateEvaluator predicatePreflight;

  SqlBlockPlanStages(SqlTemporalContext temporal, SqlBinder sharedBinder) {
    joins = sharedBinder == null ? null : new SqlBlockJoinBinder(sharedBinder);
    predicatePreflight = temporal == null ? null
        : new SqlBooleanPredicateEvaluator(new SqlExpressionEvaluator(), temporal);
  }

  StatusCode resolveSource(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlBoundBlockPlans plans) {
    int deepest = plans.count() - 1;
    SqlCommand command = plans.command(deepest);
    if (command.type() == SqlCommandType.JOIN_SCAN) {
      return resolveJoinSource(session, bound, command, deepest);
    }
    return session.resolveTable(command.tableName(), bound.table);
  }

  StatusCode activateBlock(
      BoundSqlStatement bound,
      int block,
      SqlBlockSchema child,
      SqlRowProjectionEvaluator evaluator) {
    StatusCode status = activate(bound, block, child);
    if (!status.isOk()) return status;
    return preflightBlock(bound, block, evaluator);
  }

  StatusCode activate(
      BoundSqlStatement bound, int block, SqlBlockSchema child) {
    SqlBoundBlockPlans plans = bound.blockPlans();
    SqlCommand source = plans.command(block);
    StatusCode status = bound.command.copyBlockFrom(source);
    if (!status.isOk()) return status;
    resetActive(bound);
    SqlCommandType type = bound.command.type();
    SqlBlockSchema output = plans.schema(block);
    if (type == SqlCommandType.JOIN_SCAN) {
      return bindJoinStage(bound, plans, block, output);
    }
    status = bindStage(bound, child, output, type);
    if (status.isOk()) projections.publishOperandSchema(bound, block, child, type);
    if (!status.isOk()) return status;
    status = validateOrder(bound, block, output);
    if (!status.isOk()) return status;
    return predicates.bind(bound.command, child, bound, block);
  }

  void resetPreflight() {
    if (predicatePreflight != null) predicatePreflight.reset();
  }

  private StatusCode resolveJoinSource(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlCommand command,
      int deepest) {
    if (joins == null) return StatusCode.FEATURE_NOT_SUPPORTED;
    return joins.resolve(session, bound, command, deepest);
  }

  private StatusCode preflightBlock(
      BoundSqlStatement bound,
      int block,
      SqlRowProjectionEvaluator evaluator) {
    if (evaluator == null) return StatusCode.OK;
    if (bound.command.type() == SqlCommandType.JOIN_SCAN) {
      return joins.preflight(
          bound,
          block,
          bound.blockPlans().command(block),
          bound.existingJoinContext(block),
          predicatePreflight,
          evaluator);
    }
    StatusCode status = evaluator.prepare(bound);
    if (!status.isOk()) return status;
    if (!isNestedSource(bound, block)) {
      status = prepareWhere(bound);
      if (!status.isOk()) return status;
    }
    return prepareHaving(bound);
  }

  private StatusCode prepareWhere(BoundSqlStatement bound) {
    if (predicatePreflight == null) return StatusCode.OK;
    return predicatePreflight.prepare(bound.command, bound.whereBoolean);
  }

  private StatusCode prepareHaving(BoundSqlStatement bound) {
    if (predicatePreflight == null) return StatusCode.OK;
    return predicatePreflight.prepare(bound.command, bound.havingBoolean);
  }

  private boolean isNestedSource(BoundSqlStatement bound, int block) {
    return bound.executableQuery.edgeCount() > 0
        && block == bound.executableQuery.sourceBlockCount() - 1;
  }

  private StatusCode bindStage(
      BoundSqlStatement bound,
      SqlBlockSchema child,
      SqlBlockSchema output,
      SqlCommandType type) {
    if (SqlBinder.isScalarAggregate(type)) {
      return aggregates.bind(bound.command, child, output, bound, false);
    }
    if (SqlBinder.isGroupAggregate(type)) {
      return aggregates.bind(bound.command, child, output, bound, true);
    }
    return projections.bind(bound.command, child, output, bound);
  }

  private StatusCode bindJoinStage(
      BoundSqlStatement bound,
      SqlBoundBlockPlans plans,
      int block,
      SqlBlockSchema output) {
    if (joins == null) return StatusCode.FEATURE_NOT_SUPPORTED;
    return joins.bind(bound, plans, block, output);
  }

  private StatusCode validateOrder(
      BoundSqlStatement bound, int block, SqlBlockSchema output) {
    if (block != 0 || !bound.command.isOrdered()) return StatusCode.OK;
    return output.find(bound.command.orderColumnName()) < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
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
    bound.accessPredicate = -1;
    bound.predicateColumn = -1;
    bound.accessComparison = null;
  }
}
