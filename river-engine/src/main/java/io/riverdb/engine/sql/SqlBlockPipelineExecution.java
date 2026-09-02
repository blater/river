package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Atomically materializes a bounded chain of cardinality-changing query blocks. */
final class SqlBlockPipelineExecution {
  private final BoundSqlStatement bound;
  private final SqlBlockRowStore first;
  private final SqlBlockRowStore second;
  private final SqlBlockRow sourceRow = new SqlBlockRow();
  private final SqlBlockStageRunner runner;
  private final SqlBlockStagePlan stagePlan;
  private final SqlBlockOutputShape output = new SqlBlockOutputShape();
  private SqlBlockRowStore finalStore;
  private SqlBlockSchema finalSchema;
  private int visibleColumns;
  private long rows;

  SqlBlockPipelineExecution(
      io.riverdb.engine.relational.RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlBlockPlanBinder planBinder,
      SqlJoinChainSource joinSource,
      SqlJoinChainPlan joinPlan,
      SqlExpressionEvaluator expressions,
      SqlBoundPredicateEvaluator predicateEvaluator,
      SqlSubqueryGraphExecution subqueries,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    bound = statement;
    first = new SqlBlockRowStore(shapeBudget);
    second = new SqlBlockRowStore(shapeBudget);
    stagePlan = new SqlBlockStagePlan(joinSource, joinPlan);
    runner = new SqlBlockStageRunner(
        relationalSession,
        statement,
        planBinder,
        joinSource,
        expressions,
        predicateEvaluator,
        subqueries,
        projectionEvaluator,
        temporal,
        shapeBudget,
        first,
        second);
  }

  StatusCode prepare() {
    StatusCode status = close();
    SqlBoundBlockPlans plans = bound.blockPlans();
    stagePlan.resetSourceAccess();
    if (status.isOk()) {
      status = stagePlan.describe(plans, bound.executableQuery.isAnalyze());
    }
    if (status.isOk()) status = runner.run(sourceRow, stagePlan);
    int visible = plans.command(0).columnCount();
    if (status.isOk()) status = output.prepare(plans.schema(0), visible);
    if (status.isOk()) {
      finalStore = runner.finalStore();
      finalSchema = plans.schema(0);
      visibleColumns = visible;
      rows = 0;
    } else {
      close();
    }
    return status;
  }

  StatusCode next(SqlScanRowResult result) {
    if (finalStore == null || result == null) return StatusCode.CONFLICT;
    if (rows >= bound.blockPlans().command(0).rowLimit()) return StatusCode.CONFLICT;
    StatusCode status = SqlBlockOutputPublisher.next(
        finalStore, sourceRow, finalSchema, output, result);
    if (status.isOk()) rows++;
    return status;
  }

  StatusCode nextRow(SqlBlockRow result) {
    if (finalStore == null || result == null) return StatusCode.CONFLICT;
    if (rows >= bound.blockPlans().command(0).rowLimit()) return StatusCode.CONFLICT;
    StatusCode status = finalStore.next(result);
    if (status.isOk()) rows++;
    return status;
  }

  StatusCode next(SqlExecutionResult result, long commitSequence) {
    if (finalStore == null || result == null) return StatusCode.CONFLICT;
    if (rows >= bound.blockPlans().command(0).rowLimit()) return StatusCode.CONFLICT;
    StatusCode status = SqlBlockOutputPublisher.next(
        finalStore, sourceRow, finalSchema, output, commitSequence, result);
    if (status.isOk()) rows++;
    return status;
  }

  int columnCount() { return finalSchema == null ? 0 : visibleColumns; }
  CharSequence columnName(int column) {
    return column >= 0 && column < visibleColumns ? finalSchema.name(column) : "";
  }
  int typeDescriptor(int column) {
    return column >= 0 && column < visibleColumns ? finalSchema.descriptor(column) : 0;
  }
  boolean nullable(int column) {
    return column >= 0 && column < visibleColumns && finalSchema.nullable(column);
  }
  long rowCount() { return stageRows(0); }
  boolean active() { return finalStore != null; }
  boolean hasResources() {
    return runner.hasResources() || first.hasResources() || second.hasResources();
  }
  StatusCode describe() {
    return stagePlan.describe(bound.blockPlans(), false);
  }
  SqlBlockStagePlan stagePlan() { return stagePlan; }

  StatusCode close() {
    StatusCode status = runner.close();
    StatusCode firstStatus = first.close();
    StatusCode secondStatus = second.close();
    if (status.isOk()) status = firstStatus;
    if (status.isOk()) status = secondStatus;
    sourceRow.reset(0);
    finalStore = null;
    finalSchema = null;
    visibleColumns = 0;
    rows = 0;
    return status;
  }

  private long stageRows(int block) {
    if (finalStore == null) return 0;
    long count = finalStore.rowCount();
    return block == 0
        ? Math.min(count, bound.blockPlans().command(0).rowLimit()) : count;
  }
}
