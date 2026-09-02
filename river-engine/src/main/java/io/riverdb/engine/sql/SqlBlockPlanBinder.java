package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Bottom-up binder for compact cardinality-changing query-block plans. */
final class SqlBlockPlanBinder {
  private final SqlBinder binder;
  private final SqlBlockPlanStages stages;
  private final SchemaPin accessPin = new SchemaPin();
  private final StatusDetail accessDetail = new StatusDetail(128);
  private final SqlBlockColumnLineage accessLineage = new SqlBlockColumnLineage();

  SqlBlockPlanBinder(SqlTemporalContext temporal) {
    this(temporal, null, new SqlSessionShapeBudget(null));
  }

  SqlBlockPlanBinder(SqlTemporalContext temporal, SqlBinder sharedBinder) {
    this(temporal, sharedBinder, new SqlSessionShapeBudget(null));
  }

  SqlBlockPlanBinder(
      SqlTemporalContext temporal,
      SqlBinder sharedBinder,
      SqlSessionShapeBudget shapeBudget) {
    binder = sharedBinder;
    stages = new SqlBlockPlanStages(temporal, sharedBinder, shapeBudget);
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
    status = activateBlocks(bound, evaluator, plans);
    if (status.isOk()) plans.prepareProjectionLiveness();
    return status.isOk() ? prepareRootAccess(session, bound, plans) : status;
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
    StatusCode status = plans.count() == 1
        ? StatusCode.OK : activate(bound, 0, plans.schema(1));
    return status;
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

  private StatusCode prepareRootAccess(
      RelationalSession session, BoundSqlStatement bound, SqlBoundBlockPlans plans) {
    plans.setRootAccessColumn(-1);
    if (!plans.descriptorSource()
        || plans.command(0).type() == io.riverdb.sql.SqlCommandType.JOIN_SCAN) {
      return StatusCode.OK;
    }
    accessDetail.reset();
    StatusCode status = session.resolveDescriptor(
        plans.command(plans.count() - 1).tableName(), accessPin, accessDetail);
    if (status.isOk()) {
      SqlUniversalDescriptorIndexAccess rootAccess = plans.rootAccess();
      if (plans.count() > 1) accessLineage.prepare(plans);
      rootAccess.prepare(
          plans.command(0), accessPin.descriptor(), 0, null, bound.whereBoolean,
          plans.count() > 1 ? accessLineage : null);
      if (rootAccess.active()) {
        status = rootAccess.bind(null);
        if (status == StatusCode.CONFLICT) {
          rootAccess.markEmpty();
          status = StatusCode.OK;
        }
      }
      if (status.isOk()) plans.setRootAccessColumn(rootAccess.accessColumn());
    }
    StatusCode released = accessPin.isActive() ? accessPin.release() : StatusCode.OK;
    return status.isOk() ? released : status;
  }

}
