package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.storage.heap.HeapRowResult;

/** Opens, advances, and closes one prepared query using reusable physical state. */
final class SqlQueryExecution {
  private static final int NESTED_SCALAR = 1;
  private static final int NESTED_EXISTENCE = 2;
  private static final int NESTED_MEMBERSHIP = 3;
  private static final int MAXIMUM_MEMBERSHIP_VALUES =
      SqlNestedQueryExecution.MAXIMUM_MEMBERSHIP_VALUES;

  private final RelationalSession session;
  private BoundSqlQuery.Block command;
  private final BoundSqlQuery query;
  private final SqlExecutionResult aggregateExecution = new SqlExecutionResult();
  private final BoundSqlStatement bound;
  private final SqlNestedQueryExecution nestedExecution;
  private final SqlBoundPredicateEvaluator predicates;
  private final SqlPhysicalPlan plan = new SqlPhysicalPlan();
  private final SqlPlanDescription planDescription = new SqlPlanDescription();
  private final long[] projectedValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final SqlProjectedRow projectedRow = new SqlProjectedRow();
  private final SqlRowProjectionEvaluator rowProjections;
  private final SqlProjectionResultWriter projectionResults =
      new SqlProjectionResultWriter();
  private final SqlSortExecution sorts;
  private final SqlActiveScanState activeScan = new SqlActiveScanState();
  private final SqlExpressionEvaluator expressions;
  private final SqlTemporalContext temporal;
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final SqlJoinExecution joins;
  private final SqlJoinChainSource joinSource;
  private final SqlJoinChainPlan joinPlan = new SqlJoinChainPlan();
  private final SqlCatalogScanExecution catalogs;
  private final SqlScanPreparation scanPreparation;
  private final SqlGroupedExecution groups;
  private final SqlScanRowResult explainRow = new SqlScanRowResult();
  private final SqlPointQueryExecution pointQueries;
  private final SqlBlockPlanBinder blockBinder;
  private SqlBlockPipelineExecution blockPipeline;
  private boolean pointBlockPipeline;
  private boolean explainOnly;
  private long scanGeneration;

  SqlQueryExecution(
      RelationalSession relationalSession,
      BoundSqlStatement boundStatement,
      SqlExpressionEvaluator evaluator,
      SqlTemporalContext temporal,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlBlockPlanBinder pipelineBinder) {
    session = relationalSession;
    bound = boundStatement;
    expressions = evaluator;
    this.temporal = temporal;
    rowProjections = projectionEvaluator;
    blockBinder = pipelineBinder;
    query = bound.executableQuery;
    command = query.root();
    nestedExecution = new SqlNestedQueryExecution(
        session, bound, expressions);
    predicates = new SqlBoundPredicateEvaluator(
        bound, expressions, nestedExecution, temporal);
    joinSource = new SqlJoinChainSource(session, bound, expressions, predicates);
    pointQueries = new SqlPointQueryExecution(
        session, bound, expressions, predicates, rowProjections, temporal);
    joins = new SqlJoinExecution(
        bound, plan, joinSource, rowProjections, joinPlan);
    sorts = new SqlSortExecution(
        session,
        bound,
        plan,
        activeScan,
        expressions,
        nestedExecution,
        predicates,
        rowProjections,
        joinSource);
    groups = new SqlGroupedExecution(
        session,
        bound,
        plan,
        activeScan,
        sorts,
        expressions,
        predicates,
        rowProjections,
        temporal);
    catalogs = new SqlCatalogScanExecution(session, plan, activeScan, bound.table);
    scanPreparation = new SqlScanPreparation(
        session, bound, plan, activeScan, sorts, joins, aggregateExecution);
  }

  boolean hasActiveScan() {
    return activeScan.isActive();
  }

  StatusCode retryFailedStartCleanup() {
    return closePhysicalResources();
  }

  private StatusCode claimCursor(
      SqlScanCursor cursor, StatusCode status) {
    if (!status.isOk()) {
      return status;
    }
    long nextGeneration = scanGeneration == Long.MAX_VALUE
        ? 1 : scanGeneration + 1;
    status = plan.claimCapability(cursor, this, nextGeneration);
    if (status.isOk()) {
      scanGeneration = nextGeneration;
    }
    return status;
  }

  void completeFailedStart() {
    activeScan.complete();
    activeScan.reset();
  }

  SqlExecutionResult aggregateExecution() {
    return aggregateExecution;
  }

  StatusCode initializeScan() {
    if (activeScan.isActive()) {
      return StatusCode.CONFLICT;
    }
    StatusCode resetStatus = activeScan.reset();
    if (!resetStatus.isOk()) {
      return resetStatus;
    }
    StatusCode status = nestedExecution.resetForStatement();
    if (!status.isOk()) {
      return status;
    }
    command = query.root();
    plan.reset();
    explainOnly = false;
    plan.setCommand(command);
    plan.setNestedDepth(query.sourcePlanDepth());
    if (command.type() == SqlCommandType.SHOW_TABLES) {
      if (query.isExplain()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return StatusCode.OK;
    }
    if (command.type() == SqlCommandType.SHOW_INDEXES
        || command.type() == SqlCommandType.SHOW_COLUMNS) {
      if (query.isExplain()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return StatusCode.OK;
    }
    explainOnly = query.isExplain() && !query.isAnalyze();
    if (query.isExplain()
        && (command.type() == SqlCommandType.NEXT_SEQUENCE_VALUE
            || command.type() == SqlCommandType.SCALAR_EXPRESSION)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  StatusCode prepareNested() {
    return nestedExecution.prepare(explainOnly);
  }

  boolean explainOnly() {
    return explainOnly;
  }

  void adoptPreparedQuery() {
    command = query.root();
    plan.setCommand(command);
    plan.setNestedDepth(query.sourcePlanDepth());
    plan.setOrderColumn(bound.orderColumn);
    if (bound.hasBlockPlans()) {
      plan.setBlockResult(bound.blockPlans().schema(0));
      if (blockPipeline != null) plan.setBlockStages(blockPipeline.stagePlan());
      if (blockPipeline != null && blockPipeline.active()) {
        plan.setActualRows(blockPipeline.rowCount());
      }
    }
  }

  StatusCode prepareProjectionPrograms() {
    StatusCode status = predicates.prepare();
    if (status.isOk()) status = rowProjections.prepare(bound);
    return status.isOk() ? groups.prepareHaving() : status;
  }

  StatusCode prepareBlockPipeline() {
    pointBlockPipeline = false;
    return preparePipeline();
  }

  private StatusCode preparePipeline() {
    if (blockPipeline == null) {
      blockPipeline = new SqlBlockPipelineExecution(
          session,
          bound,
          blockBinder,
          joinSource,
          joinPlan,
          expressions,
          predicates,
          rowProjections,
          temporal);
    }
    return explainOnly ? blockPipeline.describe() : blockPipeline.prepare();
  }

  StatusCode executeBlockPipeline(SqlExecutionResult result) {
    pointBlockPipeline = true;
    StatusCode status = preparePipeline();
    if (status.isOk() && blockPipeline.rowCount() == 0) status = StatusCode.CONFLICT;
    if (status.isOk() && blockPipeline.rowCount() > 1) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) status = blockPipeline.next(
        result, session.visibleCommitSequence());
    StatusCode closed = blockPipeline == null ? StatusCode.OK : blockPipeline.close();
    if (closed.isOk()) pointBlockPipeline = false;
    return status.isOk() ? closed : status;
  }

  boolean hasBlockPipelinePlan() {
    return bound.hasBlockPlans();
  }

  SqlBoundPredicateEvaluator predicateEvaluator() {
    return predicates;
  }

  StatusCode beginScan(SqlScanCursor cursor) {
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.type() == SqlCommandType.SHOW_TABLES) {
      return claimCursor(cursor, catalogs.beginObjects());
    }
    if (command.type() == SqlCommandType.SHOW_INDEXES) {
      return claimCursor(cursor, catalogs.beginIndexes(command.tableName()));
    }
    if (command.type() == SqlCommandType.SHOW_COLUMNS) {
      return claimCursor(cursor, catalogs.beginColumns(command.tableName()));
    }
    return beginParsedScan(cursor);
  }

  StatusCode configureScalarAggregateExplain() {
    return planDescription.configureScalarAggregate(plan, bound, command);
  }

  StatusCode drainAnalyze(SqlScanCursor cursor) {
    long actualRows = 0;
    StatusCode status;
    while ((status = nextScan(cursor, explainRow)) == StatusCode.OK) {
      actualRows++;
    }
    if (status == StatusCode.CONFLICT) {
      status = StatusCode.OK;
    }
    plan.setActualRows(actualRows);
    return status;
  }

  StatusCode describeCurrentPlan(SqlScanCursor cursor) {
    return bound.hasBlockPlans()
        || command.type() == SqlCommandType.JOIN_SCAN
        ? StatusCode.OK : planDescription.describe(plan);
  }

  StatusCode claimExplainResult(
      SqlScanCursor cursor,
      SqlExecutionResult completed,
      boolean analyzed) {
    StatusCode status = cursor.reset();
    if (status.isOk()) {
      status = activeScan.reset();
    }
    if (status.isOk()) {
      planDescription.configureExplainResult(plan, analyzed);
      status = activeScan.claimExplain(
          completed.transactionActive(),
          completed.commitSequence());
      status = claimCursor(cursor, status);
    }
    return status;
  }

  private StatusCode beginParsedScan(SqlScanCursor cursor) {
    if (bound.hasBlockPlans()) {
      int rows = blockPipeline == null ? 0 : (int) blockPipeline.rowCount();
      return claimCursor(cursor, activeScan.claimSorted(rows));
    }
    return claimCursor(cursor, scanPreparation.begin(explainOnly));
  }
  public StatusCode nextScan(SqlScanCursor cursor, SqlScanRowResult result) {
    if (cursor == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!cursor.isOwnedBy(this, scanGeneration)) {
      return StatusCode.CONFLICT;
    }
    result.reset();
    if (activeScan.terminalStatus() != null) {
      return activeScan.terminalStatus();
    }
    StatusCode status = nextActiveScan(cursor, result);
    if (!status.isOk() && status != StatusCode.CONFLICT) {
      activeScan.fail(status);
    }
    return status;
  }

  private StatusCode nextActiveScan(
      SqlScanCursor cursor, SqlScanRowResult result) {
    if (plan.catalogObjectScan()) {
      return catalogs.nextObject(cursor, result);
    }
    if (plan.catalogIndexScan()) {
      return catalogs.nextIndex(cursor, result);
    }
    if (plan.catalogColumnScan()) {
      return catalogs.nextColumn(cursor, result);
    }
    if (plan.explainResult()) {
      return nextExplainStep(cursor, result);
    }
    if (bound.hasBlockPlans()
        && blockPipeline != null && blockPipeline.active()) {
      StatusCode status = blockPipeline.next(result);
      if (status.isOk()) cursor.rowReturned();
      return status;
    }
    if (!plan.aggregate() && cursor.limitReached()) {
      return StatusCode.CONFLICT;
    }
    if (plan.aggregate()) {
      return nextAggregate(cursor, result);
    }
    if (plan.groupAggregate()) {
      return groups.nextAggregate(cursor, result);
    }
    if (plan.distinct()) {
      return groups.nextDistinct(cursor, result);
    }
    if (plan.sorts()) {
      return sorts.next(cursor, result);
    }
    if (command.type() == SqlCommandType.JOIN_SCAN) {
      return joins.next(cursor, result);
    }
    return nextRelationalRow(cursor, result);
  }

  private StatusCode nextExplainStep(
      SqlScanCursor cursor, SqlScanRowResult result) {
    int step = activeScan.currentPlanStep(plan.stepCount());
    if (step < 0) {
      return StatusCode.CONFLICT;
    }
    projectedValues[0] = plan.operator(step);
    projectedValues[1] = plan.detail(step);
    projectedValues[2] = plan.stepRows(step);
    long nullMask = plan.explainAnalyzed() && plan.stepRows(step) >= 0
        ? 0 : 1L << 2;
    result.set(
        step,
        projectedValues,
        nullMask,
        scanProjectionTypeDescriptors(cursor),
        3);
    StatusCode status = result.setPackedTextAt(0, plan.operator(step));
    if (status.isOk()) {
      activeScan.advancePlanStep();
      cursor.rowReturned();
    }
    return status;
  }

  private StatusCode nextAggregate(
      SqlScanCursor cursor, SqlScanRowResult result) {
    if (cursor.rowsReturned() > 0) {
      return StatusCode.CONFLICT;
    }
    if (!activeScan.aggregateAvailable()) return StatusCode.CONFLICT;
    projectedValues[0] = activeScan.aggregateValue();
    result.set(
        0,
        projectedValues,
        activeScan.aggregateNull() ? 1 : 0,
        scanProjectionTypeDescriptors(cursor),
        1);
    if (activeScan.aggregateTextLength() >= 0
        && plan.resultType(0) != 0
        && io.riverdb.base.type.SqlTypeDescriptor.typeId(plan.resultType(0))
            == io.riverdb.base.type.SqlTypeDescriptor.TYPE_ID_VARCHAR
        && !activeScan.aggregateNull()) {
      StatusCode textStatus = result.setTextAt(
          0,
          activeScan.aggregateText(),
          0,
          activeScan.aggregateTextLength());
      if (!textStatus.isOk()) return textStatus;
    }
    cursor.rowReturned();
    return StatusCode.OK;
  }

  private StatusCode nextRelationalRow(
      SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status = prepareRelationalCandidate(result);
      if (!status.isOk()) {
        return status;
      }
      long primaryKey = plan.valueIndex()
          ? indexed.key() : result.relational().key();
      HeapRowResult source = plan.valueIndex()
          ? indexed.row() : result.relational().row();
      source = nestedExecution.evaluatedRow(source);
      if (nestedExecution.rejectsOuterRow()) {
        continue;
      }
      status = predicates.evaluate(primaryKey, source);
      if (!status.isOk()) return status;
      if (!predicates.matched()) continue;
      status = evaluateAfterPredicates(primaryKey, source);
      if (!status.isOk()) {
        return status;
      }
      source = nestedExecution.evaluatedRow(source);
      if (nestedExecution.rejectsOuterRow()) {
        continue;
      }
      return projectRelationalRow(primaryKey, source, cursor, result);
    }
  }

  private StatusCode prepareRelationalCandidate(SqlScanRowResult result) {
    StatusCode status = nextRelationalSource(result);
    if (!status.isOk()) {
      return status;
    }
    long primaryKey = plan.valueIndex()
        ? indexed.key() : result.relational().key();
    HeapRowResult source = plan.valueIndex()
        ? indexed.row() : result.relational().row();
    return evaluateBeforePredicates(primaryKey, source);
  }

  private StatusCode evaluateBeforePredicates(
      long primaryKey, HeapRowResult source) {
    StatusCode status = validateRow(source);
    return status.isOk()
        ? nestedExecution.evaluateBeforePredicates(primaryKey, source)
        : status;
  }

  private StatusCode evaluateAfterPredicates(
      long primaryKey, HeapRowResult source) {
    return nestedExecution.evaluateAfterPredicates(primaryKey, source);
  }

  private StatusCode nextRelationalSource(SqlScanRowResult result) {
    return plan.valueIndex()
        ? session.nextValueScan(
            bound.table, activeScan.relational(), result.relational(), indexed)
        : session.nextScan(activeScan.relational(), result.relational());
  }

  private StatusCode projectRelationalRow(
      long primaryKey,
      HeapRowResult source,
      SqlScanCursor cursor,
      SqlScanRowResult result) {
    StatusCode status = rowProjections.project(primaryKey, source, projectedRow);
    if (!status.isOk()) return status;
    status = projectionResults.writeScan(
        result,
        primaryKey,
        source,
        bound.table,
        cursor,
        scanProjectionTypeDescriptors(cursor),
        projectedRow);
    if (status.isOk()) {
      cursor.rowReturned();
    }
    return status;
  }

  public CharSequence scanColumnName(SqlScanCursor cursor, int index) {
    if (cursor == null || !cursor.isOwnedBy(this, scanGeneration)) {
      return null;
    }
    return index >= 0 && index < plan.resultColumnCount()
        ? plan.resultName(index) : null;
  }

  public int scanColumnTypeDescriptor(SqlScanCursor cursor, int index) {
    if (cursor == null || !cursor.isOwnedBy(this, scanGeneration)) {
      return 0;
    }
    return index >= 0 && index < plan.resultColumnCount()
        ? plan.resultType(index) : 0;
  }

  public boolean scanColumnIsNullable(SqlScanCursor cursor, int index) {
    return cursor != null
        && cursor.isOwnedBy(this, scanGeneration)
        && plan.resultNullable(index);
  }
  boolean syntheticScan() {
    return plan.explainResult() || plan.aggregate();
  }

  StatusCode closeSyntheticScan(
      SqlScanCursor cursor, SqlExecutionResult result) {
    if (cursor == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!cursor.isOwnedBy(this, scanGeneration)) {
      return StatusCode.CONFLICT;
    }
    result.reset();
    if (plan.explainResult()) {
      result.setTransaction(
          activeScan.aggregateTransactionActive(),
          activeScan.explainCommitSequence());
      cursor.complete();
      activeScan.complete();
      finishPointStatement();
      return StatusCode.OK;
    }
    if (plan.aggregate()) {
      result.setTransaction(
          activeScan.aggregateTransactionActive(),
          activeScan.aggregateCommitSequence());
      cursor.complete();
      activeScan.complete();
      finishPointStatement();
      return StatusCode.OK;
    }
    return StatusCode.CONFLICT;
  }

  StatusCode closePhysicalScan(SqlScanCursor cursor) {
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!cursor.isOwnedBy(this, scanGeneration)) {
      return StatusCode.CONFLICT;
    }
    return closePhysicalResources();
  }

  void completeScan(SqlScanCursor cursor) {
    cursor.complete();
    activeScan.complete();
  }

  private StatusCode closePhysicalResources() {
    StatusCode status = StatusCode.OK;
    status = catalogs.close();
    status = joins.closeAfter(status);
    if (status.isOk() && pointQueries.hasResources()) {
      status = pointQueries.closeResources();
    }
    if (status.isOk() && activeScan.relational().isActive()) {
      status = session.closeScan(activeScan.relational());
    }
    if (status.isOk() && sorts.hasResources()) {
      status = sorts.close();
    }
    if (status.isOk() && blockPipeline != null && blockPipeline.hasResources()) {
      status = blockPipeline.close();
    }
    if (status.isOk()) pointBlockPipeline = false;
    if (status.isOk()) {
      status = nestedExecution.close();
    }
    groups.resetText();
    if (status.isOk()) {
      predicates.reset();
      rowProjections.reset();
    }
    return status;
  }

  StatusCode executePointQuery(SqlExecutionResult result) {
    return pointQueries.execute(command.type(), result);
  }

  void finishPointStatement() {
    predicates.reset();
    rowProjections.reset();
    pointQueries.finishStatement();
  }

  boolean hasPointResources() {
    return pointQueries.hasResources()
        || pointBlockPipeline
            && blockPipeline != null && blockPipeline.hasResources();
  }

  StatusCode closePointResources() {
    StatusCode status = pointQueries.closeResources();
    if (status.isOk() && pointBlockPipeline
        && blockPipeline != null && blockPipeline.hasResources()) {
      status = blockPipeline.close();
    }
    if (status.isOk()) pointBlockPipeline = false;
    return status;
  }

  private int[] scanProjectionTypeDescriptors(SqlScanCursor cursor) {
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      bound.projectedTypeDescriptors[index] =
          scanColumnTypeDescriptor(cursor, index);
    }
    return bound.projectedTypeDescriptors;
  }

  private StatusCode validateRow(HeapRowResult source) {
    return validateRow(source, bound.table);
  }

  private StatusCode validateRow(
      HeapRowResult source,
      TableDefinition definition) {
    return source.length() >= definition.fixedRowBytes()
            && source.length() <= definition.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

}
