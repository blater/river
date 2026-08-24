package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;

/** Bottom-up binder for compact cardinality-changing query-block plans. */
final class SqlBlockPlanBinder {
  private final SqlBinder binder;
  private final SqlBlockPlanStages stages;

  SqlBlockPlanBinder(SqlTemporalContext temporal) {
    this(temporal, null);
  }

  SqlBlockPlanBinder(SqlTemporalContext temporal, SqlBinder sharedBinder) {
    binder = sharedBinder;
    stages = new SqlBlockPlanStages(temporal, sharedBinder);
  }

  StatusCode bind(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlRowProjectionEvaluator evaluator) {
    SqlBoundBlockPlans plans = bound.blockPlans();
    StatusCode status = plans.capture(bound.query);
    if (!status.isOk()) return status;
    status = bindCaptured(session, bound, evaluator, plans);
    stages.resetPreflight();
    return status;
  }

  private StatusCode bindCaptured(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlRowProjectionEvaluator evaluator,
      SqlBoundBlockPlans plans) {
    StatusCode status = stages.resolveSource(session, bound, plans);
    if (!status.isOk()) return status;
    status = bindNestedQueries(session, bound);
    if (!status.isOk()) return status;
    physicalSchema(bound, plans.count() - 1);
    return activateBlocks(bound, evaluator, plans);
  }

  private StatusCode bindNestedQueries(
      RelationalSession session, BoundSqlStatement bound) {
    if (bound.executableQuery.edgeCount() == 0) return StatusCode.OK;
    if (binder == null) return StatusCode.FEATURE_NOT_SUPPORTED;
    return binder.bindQueryBlocks(session, bound);
  }

  private StatusCode activateBlocks(
      BoundSqlStatement bound,
      SqlRowProjectionEvaluator evaluator,
      SqlBoundBlockPlans plans) {
    SqlBlockSchema child = plans.baseSchema();
    for (int block = plans.count() - 1; block >= 0; block--) {
      StatusCode status = stages.activateBlock(bound, block, child, evaluator);
      if (!status.isOk()) return status;
      child = plans.schema(block);
    }
    return activate(bound, 0, plans.schema(1));
  }

  StatusCode validateTail(
      BoundSqlStatement bound, int firstBlock) {
    SqlBoundBlockPlans plans = bound.blockPlans();
    StatusCode status = plans.captureForValidation(bound.query);
    if (!status.isOk()) return status;
    if (firstBlock < 0 || firstBlock >= plans.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    physicalSchema(bound, plans.count() - 1);
    SqlBlockSchema child = plans.baseSchema();
    for (int block = plans.count() - 1; block >= firstBlock; block--) {
      status = activate(bound, block, child);
      if (!status.isOk()) return status;
      child = plans.schema(block);
    }
    return status;
  }

  StatusCode activate(BoundSqlStatement bound, int block, SqlBlockSchema child) {
    return stages.activate(bound, block, child);
  }

  private void physicalSchema(BoundSqlStatement bound, int deepest) {
    SqlBlockSchema physical = bound.blockPlans().baseSchema();
    SqlBoundJoinContext context = bound.blockPlans().command(deepest).joinChain() == null
        ? null : bound.existingJoinContext(deepest);
    io.riverdb.engine.relational.TableDefinition table = context == null
        ? bound.table : context.table(0);
    physical.set(table.columnCount());
    for (int column = 0; column < table.columnCount(); column++) {
      physical.setColumn(
          column,
          table.columnName(column),
          table.typeDescriptor(column),
          table.isNullable(column));
    }
  }

}
