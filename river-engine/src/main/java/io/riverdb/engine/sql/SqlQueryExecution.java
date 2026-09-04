package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.storage.heap.HeapRowResult;

/** Opens, advances, and closes one prepared query using reusable physical state. */
final class SqlQueryExecution {
  private final RelationalSession session;
  private BoundSqlQuery.Block command;
  private final BoundSqlQuery query;
  private final SqlExecutionResult aggregateExecution = new SqlExecutionResult();
  private final BoundSqlStatement bound;
  private final SqlSubqueryGraphExecution subqueries;
  private final SqlBoundPredicateEvaluator predicates;
  private final SqlCurrentRowProtection currentRows;
  private final SqlPhysicalPlan plan;
  private final SqlPlanDescription planDescription = new SqlPlanDescription();
  private final long[] projectedValues = new long[3];
  private final int[] explainTypeDescriptors = new int[3];
  private final SqlProjectedRow projectedRow = new SqlProjectedRow();
  private final SqlRowProjectionEvaluator rowProjections;
  private final SqlProjectionResultWriter projectionResults =
      new SqlProjectionResultWriter();
  private final SqlSortExecution sorts;
  private final SqlActiveScanState activeScan = new SqlActiveScanState();
  private SqlScanCursor activeCursor;
  private final SqlExpressionEvaluator expressions;
  private final SqlTemporalContext temporal;
  private final SqlSessionShapeBudget shapeBudget;
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final SqlJoinExecution joins;
  private final SqlJoinChainSource joinSource;
  private final SqlJoinChainPlan joinPlan = new SqlJoinChainPlan();
  private final SqlCatalogScanExecution catalogs;
  private final SqlScanPreparation scanPreparation;
  private final SqlDescriptorScanExecution descriptorScans;
  private final SqlUniversalJoinExecution universalJoins;
  private final SqlGroupedExecution groups;
  private final SqlScanRowResult explainRow = new SqlScanRowResult();
  private final SqlPointQueryExecution pointQueries;
  private final SqlBlockPlanBinder blockBinder;
  private final SqlUnionSessionExecution unions;
  private final SqlQuerySpecialScan.Result specialScan = new SqlQuerySpecialScan.Result();
  private SqlBlockPipelineExecution blockPipeline;
  private boolean pointBlockPipeline;
  private boolean explainOnly;
  private boolean subqueriesPrepared;
  private long scanGeneration;

  SqlQueryExecution(
      RelationalSession relationalSession,
      BoundSqlStatement boundStatement,
      SqlExpressionEvaluator evaluator,
      SqlTemporalContext temporal,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlBlockPlanBinder pipelineBinder,
      SqlPhysicalPlan physicalPlan,
      SqlSessionShapeBudget shapeBudget,
      SqlBinder binder) {
    session = relationalSession;
    bound = boundStatement;
    expressions = evaluator;
    this.temporal = temporal;
    this.shapeBudget = shapeBudget;
    rowProjections = projectionEvaluator;
    blockBinder = pipelineBinder;
    plan = physicalPlan;
    query = bound.executableQuery;
    command = query.root();
    subqueries = new SqlSubqueryGraphExecution(
        session, bound, expressions, temporal, shapeBudget);
    predicates = new SqlBoundPredicateEvaluator(
        bound, expressions, subqueries, temporal, shapeBudget);
    currentRows = new SqlCurrentRowProtection(session, bound, predicates);
    joinSource = new SqlJoinChainSource(session, expressions, shapeBudget);
    pointQueries = new SqlPointQueryExecution(
        session, bound, expressions, predicates, rowProjections,
        currentRows, temporal, shapeBudget);
    joins = new SqlJoinExecution(
        bound, plan, joinSource, predicates, rowProjections, joinPlan);
    sorts = new SqlSortExecution(
        session,
        bound,
        plan,
        activeScan,
        expressions,
        subqueries,
        predicates,
        rowProjections,
        currentRows,
        joinSource,
        shapeBudget);
    groups = new SqlGroupedExecution(
        session,
        bound,
        plan,
        activeScan,
        sorts,
        expressions,
        predicates,
        rowProjections,
        temporal,
        shapeBudget);
    catalogs = new SqlCatalogScanExecution(session, plan, activeScan, bound.table);
    descriptorScans = new SqlDescriptorScanExecution(
        session, temporal, shapeBudget, bound, binder, predicates);
    universalJoins = new SqlUniversalJoinExecution(
        session, expressions, temporal, rowProjections, joinPlan, shapeBudget);
    scanPreparation = new SqlScanPreparation(
        session, bound, plan, activeScan, sorts, joins, aggregateExecution);
    unions = new SqlUnionSessionExecution(
        session, binder, expressions, temporal, shapeBudget);
  }

  boolean hasActiveScan() {
    return universalJoins.active() || descriptorScans.activeWith(activeScan);
  }

  StatusCode prepareUnion() {
    return unions.prepare(bound.query, bound.command);
  }

  StatusCode resolveUniversalJoin(SqlBoundJoinContext context) {
    return universalJoins.resolve(bound.command, context);
  }

  boolean universalJoinMatched() { return universalJoins.matched(); }

  StatusCode releaseUniversalJoin() { return universalJoins.close(); }

  StatusCode configureUniversalJoin() {
    return universalJoins.configure(bound, plan);
  }

  StatusCode prepareDescriptorScan() {
    return descriptorScans.prepare(bound.command, bound.query, plan);
  }

  boolean descriptorScanMatched() { return descriptorScans.matched(); }

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
      activeCursor = cursor;
    }
    return status;
  }

  void completeFailedStart() {
    if (activeCursor != null) activeCursor.complete();
    activeCursor = null;
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
    StatusCode status = subqueries.close();
    if (!status.isOk()) {
      return status;
    }
    subqueries.clearExternalJoinSource();
    subqueriesPrepared = false;
    command = query.root();
    plan.reset();
    if (query.edgeCount() > 0) plan.setSubqueries(subqueries.plan());
    explainOnly = false;
    plan.setCommand(command);
    plan.setNestedDepth(query.planDepth());
    explainOnly = query.isExplain() && !query.isAnalyze();
    return SqlQueryScanInitialization.validate(command, query);
  }

  StatusCode prepareNested() {
    if (!explainOnly) return prepareSubqueries();
    subqueries.describe();
    return StatusCode.OK;
  }

  boolean explainOnly() {
    return explainOnly;
  }

  void adoptPreparedQuery() {
    command = query.root();
    plan.setCommand(command);
    plan.setNestedDepth(query.planDepth());
    if (query.edgeCount() > 0) plan.setSubqueries(subqueries.plan());
    plan.setOrderColumn(bound.orderColumn);
    if (unions.active()) {
      plan.setBlockResult(unions.schema(), unions.schema().count());
      plan.setUnionStages(unions.stagePlan());
      plan.setActualRows(unions.rowCount());
      return;
    }
    if (bound.hasBlockPlans()) {
      plan.setBlockResult(
          bound.blockPlans().schema(0), bound.blockPlans().command(0).columnCount());
      if (blockPipeline != null) plan.setBlockStages(blockPipeline.stagePlan());
      if (blockPipeline != null && blockPipeline.active()) {
        plan.setActualRows(blockPipeline.rowCount());
      }
    }
  }

  StatusCode prepareProjectionPrograms() {
    StatusCode status = prepareSubqueries();
    if (status.isOk() && command.type() != SqlCommandType.JOIN_SCAN
        && query.edgeCount() == 0) {
      status = predicates.prepare();
    }
    if (status.isOk()) status = rowProjections.prepare(bound);
    if (status.isOk()) status = reserveAggregateResultText();
    return status.isOk() ? groups.prepareHaving() : status;
  }

  private StatusCode reserveAggregateResultText() {
    for (int invocation = 0; invocation < bound.aggregates.count(); invocation++) {
      if (io.riverdb.base.type.SqlTypeDescriptor.typeId(
          bound.aggregates.resultDescriptor(invocation))
          == io.riverdb.base.type.SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        return activeScan.reserveAggregateText(true);
      }
    }
    return StatusCode.OK;
  }

  StatusCode configureJoin() {
    if (query.edgeCount() == 0) {
      return joins.configure(bound.command, bound.existingJoinContext(0));
    }
    int root = query.sourceBlockCount() - 1;
    subqueries.registerExternalJoinSource(root, joinSource);
    StatusCode status = prepareSubqueries();
    return status.isOk() ? joins.configure(
        bound.query.block(root),
        bound.existingJoinContext(root),
        bound.nestedBoolean(root),
        subqueries.joinPredicates(root)) : status;
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
          subqueries,
          rowProjections,
          temporal,
          shapeBudget);
    }
    int source = query.sourceBlockCount() - 1;
    if (query.edgeCount() > 0 && query.block(source).joinChain() != null) {
      subqueries.registerExternalJoinSource(source, joinSource);
    }
    StatusCode status = prepareSubqueries();
    if (!status.isOk()) return status;
    return explainOnly ? blockPipeline.describe() : blockPipeline.prepare();
  }

  StatusCode executeBlockPipeline(SqlExecutionResult result) {
    return executeBlockPipeline(result, false);
  }

  StatusCode executeBlockPipeline(
      SqlExecutionResult result, boolean acceptFirstRow) {
    pointBlockPipeline = true;
    explainOnly = false;
    StatusCode status = preparePipeline();
    status = SqlPointBlockExecution.execute(
        blockPipeline, status, session.visibleCommitSequence(), result, acceptFirstRow);
    pointBlockPipeline = blockPipeline != null && blockPipeline.hasResources();
    return status;
  }

  boolean hasBlockPipelinePlan() {
    return bound.hasBlockPlans() || unions.active();
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
    if (descriptorScans.matched()) {
      return claimCursor(cursor, descriptorScans.open());
    }
    if (universalJoins.matched()) {
      return claimCursor(cursor, universalJoins.open());
    }
    return beginParsedScan(cursor);
  }

  StatusCode claimPreparedPlan(SqlScanCursor cursor) {
    if (cursor == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    long nextGeneration = scanGeneration == Long.MAX_VALUE ? 1 : scanGeneration + 1;
    StatusCode status = cursor.claimPlan(this, nextGeneration);
    if (status.isOk()) {
      scanGeneration = nextGeneration;
      activeCursor = cursor;
    }
    return status;
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
    return unions.active() || bound.hasBlockPlans()
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
    if (unions.active()) {
      return claimCursor(cursor, activeScan.claimSorted(unions.rowCount()));
    }
    if (bound.hasBlockPlans()) {
      long rows = blockPipeline == null ? 0 : blockPipeline.rowCount();
      return claimCursor(cursor, activeScan.claimSorted(rows));
    }
    return claimCursor(cursor, scanPreparation.begin(explainOnly));
  }
  public StatusCode nextScan(SqlScanCursor cursor, SqlScanRowResult result) {
    if (cursor == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode admission = result.admit(cursor, this, scanGeneration, plan);
    if (!admission.isOk()) return admission;
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
    if (bound.hasBlockPlans()
        && blockPipeline != null && blockPipeline.active()) {
      StatusCode status = blockPipeline.next(result);
      if (status.isOk()) cursor.rowReturned();
      return status;
    }
    SqlQuerySpecialScan.next(
        unions, universalJoins, descriptorScans, plan, catalogs, activeScan,
        projectedValues, explainTypeDescriptors, bound.projectedTypeDescriptors,
        cursor, result, specialScan);
    if (specialScan.matched()) return specialScan.status();
    if (!plan.aggregate() && cursor.limitReached()) {
      return StatusCode.CONFLICT;
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
      status = predicates.evaluate(primaryKey, source);
      if (!status.isOk()) return status;
      source = subqueries.evaluatedRow(
          query.sourceBlockCount() - 1, source);
      if (!predicates.matched()) {
        subqueries.releaseRow(query.sourceBlockCount() - 1);
        continue;
      }
      if (bound.command.isSelectForUpdate()) {
        status = currentRows.lockAndRecheck(primaryKey);
        if (status == StatusCode.CONFLICT) continue;
        if (!status.isOk()) return status;
        source = subqueries.evaluatedRow(
            query.sourceBlockCount() - 1, currentRows.row());
      }
      status = projectRelationalRow(primaryKey, source, cursor, result);
      return bound.command.isSelectForUpdate()
          ? currentRows.finish(status) : releaseProjectedRow(status);
    }
  }

  private StatusCode releaseProjectedRow(StatusCode status) {
    subqueries.releaseRow(query.sourceBlockCount() - 1);
    return status;
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
    return validateRow(source);
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
    return SqlScanMetadata.name(cursor, this, scanGeneration, plan, index);
  }

  public int scanColumnTypeDescriptor(SqlScanCursor cursor, int index) {
    return SqlScanMetadata.descriptor(cursor, this, scanGeneration, plan, index);
  }

  public boolean scanColumnIsNullable(SqlScanCursor cursor, int index) {
    return SqlScanMetadata.nullable(cursor, this, scanGeneration, plan, index);
  }
  boolean syntheticScan() {
    return plan.explainResult()
        || plan.aggregate() && !descriptorScans.matched();
  }

  StatusCode terminalStatus() {
    return activeScan.terminalStatus();
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
      return finishPointStatement();
    }
    if (plan.aggregate()) {
      result.setTransaction(
          activeScan.aggregateTransactionActive(),
          activeScan.aggregateCommitSequence());
      cursor.complete();
      activeScan.complete();
      return finishPointStatement();
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
    activeCursor = null;
  }

  private StatusCode closePhysicalResources() {
    StatusCode status = SqlQueryExecutionResourceCleanup.close(
        session,
        universalJoins,
        descriptorScans,
        unions,
        catalogs,
        subqueries,
        joins,
        pointQueries,
        activeScan,
        sorts,
        blockPipeline,
        groups);
    if (status.isOk()) {
      pointBlockPipeline = false;
      subqueriesPrepared = false;
      predicates.reset();
      rowProjections.reset();
    }
    return status;
  }

  StatusCode executePointQuery(SqlExecutionResult result) {
    return pointQueries.execute(command.type(), result);
  }

  StatusCode finishPointStatement() {
    StatusCode status = SqlPointStatementFinish.finish(
        predicates, subqueries, rowProjections, pointQueries);
    if (status.isOk()) subqueriesPrepared = false;
    return status;
  }

  boolean hasPointResources() {
    return SqlPointResourceState.has(
        subqueries, pointQueries, pointBlockPipeline, blockPipeline);
  }

  StatusCode closePointResources() {
    StatusCode status = SqlPointResourceClose.close(
        subqueries, pointQueries, pointBlockPipeline, blockPipeline);
    if (status.isOk()) subqueriesPrepared = false;
    if (status.isOk()) pointBlockPipeline = false;
    return status;
  }

  private StatusCode prepareSubqueries() {
    if (query.edgeCount() == 0 || subqueriesPrepared) return StatusCode.OK;
    StatusCode status = subqueries.prepare();
    if (status.isOk()) subqueriesPrepared = true;
    return status;
  }

  private int[] scanProjectionTypeDescriptors(SqlScanCursor cursor) {
    return plan.copyResultDescriptors(
        plan.explainResult() ? explainTypeDescriptors : bound.projectedTypeDescriptors,
        cursor.projectedColumnCount());
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
