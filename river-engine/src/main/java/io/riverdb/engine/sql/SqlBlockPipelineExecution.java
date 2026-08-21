package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Atomically materializes a bounded chain of cardinality-changing query blocks. */
final class SqlBlockPipelineExecution {
  private final BoundSqlStatement bound;
  private final SqlBlockRowStore first = new SqlBlockRowStore();
  private final SqlBlockRowStore second = new SqlBlockRowStore();
  private final SqlBlockRow sourceRow = new SqlBlockRow();
  private final SqlBlockStageRunner runner;
  private final SqlBlockStagePlan stagePlan = new SqlBlockStagePlan();
  private final long[] outputValues = new long[8];
  private final int[] outputTypes = new int[8];
  private SqlBlockRowStore finalStore;
  private SqlBlockSchema finalSchema;
  private long rows;

  SqlBlockPipelineExecution(
      io.riverdb.engine.relational.RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlBlockPlanBinder planBinder,
      SqlJoinChainSource joinSource,
      SqlExpressionEvaluator expressions,
      SqlBoundPredicateEvaluator predicateEvaluator,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlTemporalContext temporal) {
    bound = statement;
    runner = new SqlBlockStageRunner(
        relationalSession,
        statement,
        planBinder,
        joinSource,
        expressions,
        predicateEvaluator,
        projectionEvaluator,
        temporal,
        first,
        second);
  }

  StatusCode prepare() {
    StatusCode status = close();
    SqlBoundBlockPlans plans = bound.blockPlans();
    if (status.isOk()) status = stagePlan.describe(plans);
    if (status.isOk()) status = runner.run(sourceRow, stagePlan);
    if (status.isOk()) {
      finalStore = runner.finalStore();
      finalSchema = plans.schema(0);
      rows = 0;
    } else {
      close();
    }
    return status;
  }

  StatusCode next(SqlScanRowResult result) {
    if (finalStore == null || result == null) return StatusCode.CONFLICT;
    if (rows >= bound.blockPlans().command(0).rowLimit()) return StatusCode.CONFLICT;
    StatusCode status = finalStore.next(sourceRow);
    if (!status.isOk()) return status;
    long nullMask = 0;
    for (int column = 0; column < finalSchema.count(); column++) {
      outputValues[column] = sourceRow.value(column);
      outputTypes[column] = finalSchema.descriptor(column);
      if (sourceRow.nullValue(column)) nullMask |= 1L << column;
    }
    result.set(rows++, outputValues, nullMask, outputTypes, finalSchema.count());
    for (int column = 0; column < finalSchema.count(); column++) {
      if (finalSchema.varchar(column) && !sourceRow.nullValue(column)) {
        status = result.setTextAt(
            column, sourceRow.text(column), 0, sourceRow.textLength(column));
        if (!status.isOk()) return status;
      }
    }
    return StatusCode.OK;
  }

  StatusCode next(SqlExecutionResult result, long commitSequence) {
    if (finalStore == null || result == null) return StatusCode.CONFLICT;
    if (rows >= bound.blockPlans().command(0).rowLimit()) return StatusCode.CONFLICT;
    StatusCode status = finalStore.next(sourceRow);
    if (!status.isOk()) return status;
    long nullMask = fillOutput();
    result.setProjection(
        rows++, outputValues, nullMask, outputTypes, finalSchema.count(), commitSequence);
    for (int column = 0; column < finalSchema.count(); column++) {
      if (finalSchema.varchar(column) && !sourceRow.nullValue(column)) {
        status = result.setTextAt(
            column, sourceRow.text(column), sourceRow.textLength(column));
        if (!status.isOk()) return status;
      }
    }
    return StatusCode.OK;
  }

  int columnCount() { return finalSchema == null ? 0 : finalSchema.count(); }
  CharSequence columnName(int column) { return finalSchema.name(column); }
  int typeDescriptor(int column) { return finalSchema.descriptor(column); }
  boolean nullable(int column) { return finalSchema.nullable(column); }
  long rowCount() { return stageRows(0); }
  boolean active() { return finalStore != null; }
  boolean hasResources() {
    return runner.hasResources() || first.hasResources() || second.hasResources();
  }
  StatusCode describe() { return stagePlan.describe(bound.blockPlans()); }
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
    rows = 0;
    return status;
  }

  private long fillOutput() {
    long nullMask = 0;
    for (int column = 0; column < finalSchema.count(); column++) {
      outputValues[column] = sourceRow.value(column);
      outputTypes[column] = finalSchema.descriptor(column);
      if (sourceRow.nullValue(column)) nullMask |= 1L << column;
    }
    return nullMask;
  }

  private long stageRows(int block) {
    if (finalStore == null) return 0;
    long count = finalStore.rowCount();
    return block == 0
        ? Math.min(count, bound.blockPlans().command(0).rowLimit()) : count;
  }
}
