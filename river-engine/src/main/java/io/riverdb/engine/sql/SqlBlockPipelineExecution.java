package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Atomically materializes a bounded chain of cardinality-changing query blocks. */
final class SqlBlockPipelineExecution {
  private final BoundSqlStatement bound;
  private final SqlBlockPlanBinder binder;
  private final SqlRowProjectionEvaluator projections;
  private final SqlBlockSource source;
  private final SqlBlockStageProjector projector;
  private final SqlBlockProjectionStage projectionStage;
  private final SqlBlockRowStore first = new SqlBlockRowStore();
  private final SqlBlockRowStore second = new SqlBlockRowStore();
  private final SqlBlockRow sourceRow = new SqlBlockRow();
  private final SqlAggregateAccumulatorSet accumulator =
      new SqlAggregateAccumulatorSet();
  private final SqlHavingEvaluator having;
  private final SqlBlockAggregatePublisher publisher;
  private final SqlBlockScalarAggregateStage scalarStage;
  private final SqlBlockGroupedAggregateStage groupedStage;
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
      SqlExpressionEvaluator expressions,
      SqlRowProjectionEvaluator projectionEvaluator) {
    bound = statement;
    binder = planBinder;
    projections = projectionEvaluator;
    source = new SqlBlockSource(relationalSession, statement);
    projector = new SqlBlockStageProjector(statement, expressions, projectionEvaluator);
    projectionStage = new SqlBlockProjectionStage(statement, source, projector);
    having = new SqlHavingEvaluator(expressions, projectionEvaluator);
    publisher = new SqlBlockAggregatePublisher(statement);
    scalarStage = new SqlBlockScalarAggregateStage(
        statement, source, projector, having, accumulator, publisher);
    groupedStage = new SqlBlockGroupedAggregateStage(
        statement, having, accumulator, publisher);
  }

  StatusCode prepare() {
    StatusCode status = close();
    SqlBoundBlockPlans plans = bound.blockPlans();
    if (status.isOk()) status = stagePlan.describe(plans);
    SqlBlockRowStore input = null;
    for (int block = plans.count() - 1;
        status.isOk() && block >= 0; block--) {
      SqlBlockSchema child = block + 1 == plans.count()
          ? plans.baseSchema() : plans.schema(block + 1);
      status = binder.activate(bound, block, child);
      if (status.isOk()) status = projections.prepare(bound);
      if (!status.isOk()) break;
      SqlBlockRowStore output = input == first ? second : first;
      status = execute(block, input, output);
      input = status.isOk() ? finalStore : input;
      if (status.isOk()) stagePlan.setRows(block, stageRows(block));
    }
    if (status.isOk()) {
      finalStore = input;
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
    return source.hasResources() || first.hasResources() || second.hasResources();
  }
  StatusCode describe() { return stagePlan.describe(bound.blockPlans()); }
  SqlBlockStagePlan stagePlan() { return stagePlan; }

  StatusCode close() {
    StatusCode status = source.close();
    StatusCode firstStatus = first.close();
    StatusCode secondStatus = second.close();
    if (status.isOk()) status = firstStatus;
    if (status.isOk()) status = secondStatus;
    accumulator.clearAll();
    projector.reset();
    projectionStage.reset();
    scalarStage.reset();
    groupedStage.reset();
    publisher.reset();
    sourceRow.reset(0);
    finalStore = null;
    finalSchema = null;
    rows = 0;
    return status;
  }

  private StatusCode execute(
      int block, SqlBlockRowStore input, SqlBlockRowStore output) {
    SqlCommand command = bound.command;
    SqlCommandType type = command.type();
    if (SqlBinder.isScalarAggregate(type)) {
      StatusCode status = scalarStage.execute(
          block, input, output, outputSortKey(block));
      finalStore = status.isOk() ? output : null;
      return status;
    }
    if (SqlBinder.isGroupAggregate(type)) {
      StatusCode status = projectionStage.materialize(block, input, output, 0);
      if (!status.isOk()) return status;
      SqlBlockRowStore grouped = alternate(input, output);
      status = groupedStage.execute(block, output, grouped, outputSortKey(block));
      finalStore = status.isOk() ? grouped : null;
      return status;
    }
    StatusCode status = projectionStage.materialize(block, input, output,
        type == SqlCommandType.DISTINCT_SCAN ? 0 : outputSortKey(block));
    if (!status.isOk()) return status;
    if (type != SqlCommandType.DISTINCT_SCAN) {
      finalStore = output;
      return StatusCode.OK;
    }
    SqlBlockRowStore distinct = alternate(input, output);
    status = projectionStage.deduplicate(block, output, distinct);
    finalStore = status.isOk() ? distinct : null;
    return status;
  }

  private int outputSortKey(int block) {
    return block == 0 && bound.command.isOrdered()
        ? bound.blockPlans().schema(block).find(bound.command.orderColumnName()) : -1;
  }

  private SqlBlockRowStore alternate(
      SqlBlockRowStore input, SqlBlockRowStore output) {
    SqlBlockRowStore alternate = input == null || input == first ? second : first;
    return alternate == output ? output == first ? second : first : alternate;
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
