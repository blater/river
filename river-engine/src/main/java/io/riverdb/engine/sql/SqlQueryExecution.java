package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlIdentifier;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Opens, advances, and closes one prepared query using reusable physical state. */
final class SqlQueryExecution {
  private static final long PLAN_AGGREGATE = PackedText.pack("agg");
  private static final long PLAN_DISTINCT = PackedText.pack("dedupe");
  private static final long PLAN_FILTER = PackedText.pack("filter");
  private static final long PLAN_GROUP = PackedText.pack("group");
  private static final long PLAN_INDEX = PackedText.pack("index");
  private static final long PLAN_JOIN = PackedText.pack("join");
  private static final long PLAN_LEFT = PackedText.pack("left");
  private static final long PLAN_LIMIT = PackedText.pack("limit");
  private static final long PLAN_LOOKUP = PackedText.pack("lookup");
  private static final long PLAN_NESTED = PackedText.pack("nested");
  private static final long PLAN_PRIMARY = PackedText.pack("primary");
  private static final long PLAN_SORT = PackedText.pack("sort");
  private static final long PLAN_TABLE = PackedText.pack("table");
  private static final int NULL_PROJECTION = BoundSqlStatement.NULL_PROJECTION;
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
  private final long[] projectedValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final SqlSortExecution sorts;
  private final SqlActiveScanState activeScan = new SqlActiveScanState();
  private final SqlExpressionEvaluator expressions;
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final SqlJoinExecution joins;
  private final SqlCatalogScanExecution catalogs;
  private final SqlScanPreparation scanPreparation;
  private final RelationalScanCursor aggregateCursor = new RelationalScanCursor();
  private final RelationalScanResult aggregateRow = new RelationalScanResult();
  private final SqlGroupedExecution groups;
  private final SqlScanRowResult explainRow = new SqlScanRowResult();
  private final SqlPointAggregateExecution pointAggregates;
  private boolean explainOnly;
  private long scanGeneration;

  SqlQueryExecution(
      RelationalSession relationalSession,
      BoundSqlStatement boundStatement,
      SqlExpressionEvaluator evaluator) {
    session = relationalSession;
    bound = boundStatement;
    expressions = evaluator;
    query = bound.executableQuery;
    command = query.root();
    nestedExecution = new SqlNestedQueryExecution(
        session, bound, expressions);
    predicates = new SqlBoundPredicateEvaluator(bound, expressions, nestedExecution);
    pointAggregates = new SqlPointAggregateExecution(
        session, bound, expressions, predicates);
    joins = new SqlJoinExecution(
        session, bound, plan, activeScan, expressions, predicates);
    sorts = new SqlSortExecution(
        session, bound, plan, activeScan, expressions, nestedExecution, predicates);
    groups = new SqlGroupedExecution(
        session, bound, plan, activeScan, sorts, expressions, predicates);
    catalogs = new SqlCatalogScanExecution(session, plan, activeScan);
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
    plan.setCommand(command);
    plan.setNestedDepth(query.sourcePlanDepth());
    if (command.type() == SqlCommandType.SHOW_TABLES) {
      if (query.isExplain()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return StatusCode.OK;
    }
    if (command.type() == SqlCommandType.SHOW_INDEXES) {
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
    return beginParsedScan(cursor);
  }

  void configureScalarAggregateExplain() {
    plan.setFilterCount(bound.predicateCount);
    plan.setAggregate(
        command.type() == SqlCommandType.COUNT
            ? -1 : bound.projectedColumns[0]);
    plan.setAccessColumn(
        bound.accessPredicate >= 0
            ? bound.predicateColumn == 0
                || bound.table.hasIndexOn(bound.predicateColumn)
                ? bound.predicateColumn : -1
            : -1);
    describePlan(null);
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

  void describeCurrentPlan(SqlScanCursor cursor) {
    describePlan(cursor);
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
      configureExplainResultShape(analyzed);
      status = activeScan.claimExplain(
          completed.transactionActive(),
          completed.commitSequence());
      status = claimCursor(cursor, status);
    }
    return status;
  }

  private StatusCode beginParsedScan(SqlScanCursor cursor) {
    return claimCursor(cursor, scanPreparation.begin(explainOnly));
  }
  private void describePlan(SqlScanCursor cursor) {
    plan.resetSteps();
    if (plan.rowLimit() != Long.MAX_VALUE) {
      addPlanStep(PLAN_LIMIT, plan.rowLimit());
    }
    describeLogicalPlanStep();
    describePhysicalPlanSteps();
  }

  private void describeLogicalPlanStep() {
    if (plan.aggregate()) {
      addPlanStep(PLAN_AGGREGATE, plan.aggregateColumn());
    } else if (plan.groupAggregate()) {
      addPlanStep(PLAN_GROUP, plan.groupAggregateColumn());
    } else if (plan.distinct()) {
      addPlanStep(PLAN_DISTINCT, plan.groupColumn());
    } else if (plan.join()) {
      addPlanStep(
          plan.leftJoin() ? PLAN_LEFT : PLAN_JOIN,
          plan.joinOuterColumn());
    }
  }

  private void describePhysicalPlanSteps() {
    if (plan.nestedDepth() > 1) {
      addPlanStep(PLAN_NESTED, plan.nestedDepth());
    }
    if (plan.sorts()) {
      addPlanStep(PLAN_SORT, plan.descending() ? -1 : 1);
    }
    if (plan.filterCount() > 0) {
      addPlanStep(PLAN_FILTER, plan.filterCount());
    }
    addPlanStep(
        plan.accessColumn() > 0
            ? PLAN_INDEX
            : plan.accessColumn() == 0 ? PLAN_PRIMARY : PLAN_TABLE,
        plan.accessColumn());
    if (plan.join()) {
      addPlanStep(
          plan.joinInnerIndexed() ? PLAN_LOOKUP : PLAN_TABLE,
          plan.joinInnerColumn());
    }
  }

  private void addPlanStep(long operator, long detail) {
    plan.addStep(operator, detail);
  }

  private void configureExplainResultShape(boolean analyzed) {
    plan.setExplainResult(analyzed);
    plan.setResultColumn(
        0, 0, SqlTypeDescriptor.varchar(64), "operator");
    plan.setResultColumn(1, 1, SqlTypeDescriptor.BIGINT, "detail");
    plan.setResultColumn(2, 2, SqlTypeDescriptor.BIGINT, "rows");
  }

  public StatusCode nextScan(SqlScanCursor cursor, SqlScanRowResult result) {
    if (cursor == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!cursor.isOwnedBy(this, scanGeneration)) {
      return StatusCode.CONFLICT;
    }
    result.reset();
    if (plan.catalogObjectScan()) {
      return catalogs.nextObject(cursor, result);
    }
    if (plan.catalogIndexScan()) {
      return catalogs.nextIndex(cursor, result);
    }
    if (plan.explainResult()) {
      return nextExplainStep(cursor, result);
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
    if (plan.join()) {
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
    projectedValues[2] = plan.actualRows();
    long nullMask = plan.explainAnalyzed() && step == 0 ? 0 : 1L << 2;
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
    projectedValues[0] = activeScan.aggregateValue();
    result.set(
        0,
        projectedValues,
        activeScan.aggregateNull() ? 1 : 0,
        scanProjectionTypeDescriptors(cursor),
        1);
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
      if (nestedExecution.rejectsOuterRow()
          || !predicates.matches(primaryKey, source)) {
        continue;
      }
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
    long nullMask = projectScanRow(
        primaryKey, source, cursor, projectedValues);
    result.set(
        primaryKey,
        projectedValues,
        nullMask,
        scanProjectionTypeDescriptors(cursor),
        cursor.projectedColumnCount());
    StatusCode status = setProjectedText(
        result, source, bound.table, cursor, nullMask);
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
      return StatusCode.OK;
    }
    if (plan.aggregate()) {
      result.setTransaction(
          activeScan.aggregateTransactionActive(),
          activeScan.aggregateCommitSequence());
      cursor.complete();
      activeScan.complete();
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
    if (status.isOk() && activeScan.joinInnerRelational().isActive()) {
      status = session.closeScan(activeScan.joinInnerRelational());
      if (status.isOk()) {
        activeScan.completeJoinInnerScan();
      }
    }
    if (status.isOk() && activeScan.relational().isActive()) {
      status = session.closeScan(activeScan.relational());
    }
    if (status.isOk() && sorts.hasResources()) {
      status = sorts.close();
    }
    if (status.isOk()) {
      status = nestedExecution.close();
    }
    return status;
  }

  StatusCode executePointQuery(SqlExecutionResult result) {
    if (pointAggregates.accepts(command.type())) {
      return pointAggregates.execute(result);
    }
    return executeUniquePointSelect(result);
  }

  private StatusCode executeUniquePointSelect(SqlExecutionResult result) {
    if (command.type() != SqlCommandType.SELECT) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!accessEquality()
        || bound.predicateColumn > 0
            && !bound.table.hasUniqueIndexOn(bound.predicateColumn)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (bound.predicateColumn > 0
        && bound.table.isVarchar(bound.predicateColumn)) {
      return executeTextPointSelect(result);
    }
    StatusCode status;
    long primaryKey;
    HeapRowResult source;
    if (bound.predicateColumn == 0) {
      primaryKey = accessValue();
      status = session.fetch(bound.table, primaryKey, fetched);
      source = fetched;
    } else {
      status = session.fetchByUniqueValue(
          bound.table, bound.predicateColumn, accessValue(), indexed);
      primaryKey = indexed.key();
      source = indexed.row();
    }
    if (status.isOk()) {
      status = validateRow(source);
    }
    if (status.isOk() && !predicates.matches(primaryKey, source)) {
      status = StatusCode.CONFLICT;
    }
    if (status.isOk()) {
      status = projectRow(
          primaryKey,
          source,
          bound.projectedColumns,
          bound.projectedColumnCount,
          projectedValues);
    }
    if (status.isOk()) {
      result.setProjection(
          primaryKey,
          projectedValues,
          projectionNullMask(
              source,
              bound.table,
              bound.projectedColumns,
              bound.projectedColumnCount),
            projectionTypeDescriptors(
                bound.projectedColumns, bound.projectedColumnCount),
          bound.projectedColumnCount,
          0);
      status = setExecutionText(
          result,
          source,
          bound.table,
          bound.projectedColumns,
          bound.projectedColumnCount,
          projectionNullMask(
              source,
              bound.table,
              bound.projectedColumns,
              bound.projectedColumnCount));
    }
    return status;
  }


  private StatusCode executeTextPointSelect(SqlExecutionResult result) {
    StatusCode status = session.beginScan(bound.table, aggregateCursor);
    boolean active = status.isOk();
    boolean found = false;
    while (status.isOk()) {
      status = session.nextScan(aggregateCursor, aggregateRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      HeapRowResult source = aggregateRow.row();
      status = status.isOk() ? validateRow(source) : status;
      if (status.isOk() && predicates.matches(aggregateRow.key(), source)) {
        status = setTextPointResult(result, source);
        found = status.isOk();
        break;
      }
    }
    status = closePointScan(active, status);
    return status.isOk() && !found ? StatusCode.CONFLICT : status;
  }

  private StatusCode closePointScan(boolean active, StatusCode bodyStatus) {
    if (!active) {
      return bodyStatus;
    }
    StatusCode close = session.closeScan(aggregateCursor);
    if (close.isOk()) {
      aggregateCursor.reset();
    }
    return bodyStatus.isOk() ? close : bodyStatus;
  }

  private StatusCode setTextPointResult(
      SqlExecutionResult result, HeapRowResult source) {
    long primaryKey = aggregateRow.key();
    StatusCode status = projectRow(
        primaryKey,
        source,
        bound.projectedColumns,
        bound.projectedColumnCount,
        projectedValues);
    long nullMask = projectionNullMask(
        source,
        bound.table,
        bound.projectedColumns,
        bound.projectedColumnCount);
    if (!status.isOk()) {
      return status;
    }
    result.setProjection(
        primaryKey,
        projectedValues,
        nullMask,
        projectionTypeDescriptors(bound.projectedColumns, bound.projectedColumnCount),
        bound.projectedColumnCount,
        0);
    return setExecutionText(
        result,
        source,
        bound.table,
        bound.projectedColumns,
        bound.projectedColumnCount,
        nullMask);
  }

  boolean hasPointResources() {
    return aggregateCursor.isActive();
  }

  StatusCode closePointResources() {
    if (!aggregateCursor.isActive()) {
      return StatusCode.OK;
    }
    StatusCode status = session.closeScan(aggregateCursor);
    if (status.isOk()) {
      aggregateCursor.reset();
    }
    return status;
  }

  private static StatusCode setExecutionText(
      SqlExecutionResult result,
      HeapRowResult source,
      TableDefinition definition,
      int[] columns,
      int columnCount,
      long nullMask) {
    for (int index = 0; index < columnCount; index++) {
      int column = columns[index];
      if (column <= 0
          || !definition.isVarchar(column)
          || (nullMask & 1L << index) != 0) {
        continue;
      }
      long handle = source.getLong((column - 1) * Long.BYTES);
      StatusCode status = result.setUtf8At(
          index, source, (int) (handle >>> 32), (int) handle);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private int[] projectionTypeDescriptors(int[] projections, int count) {
    for (int index = 0; index < count; index++) {
      int column = projections[index];
      bound.projectedTypeDescriptors[index] = column >= 0
          ? bound.table.typeDescriptor(column) : SqlTypeDescriptor.BIGINT;
    }
    return bound.projectedTypeDescriptors;
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

  private long readColumn(long primaryKey, HeapRowResult source, int column) {
    return expressions.readColumn(primaryKey, source, column);
  }

  private boolean isNull(
      HeapRowResult source,
      TableDefinition definition,
      int column) {
    return expressions.isNull(source, definition, column);
  }

  private boolean accessEquality() {
    return bound.accessPredicate >= 0
        && command.isEqualityPredicate(bound.accessPredicate);
  }

  private boolean matchesComparison(
      long actual,
      SqlComparison comparison,
      long expected) {
    return expressions.matchesComparison(actual, comparison, expected);
  }

  private long accessValue() {
    return bound.accessValue;
  }

  private long accessLowerInclusive() {
    return bound.accessLowerInclusive;
  }

  private long accessUpperExclusive() {
    return bound.accessUpperExclusive;
  }

  private StatusCode projectRow(
      long primaryKey,
      HeapRowResult source,
      int[] columns,
      int columnCount,
      long[] destination) {
    StatusCode status = validateRow(source);
    if (status.isOk()) {
      for (int index = 0; index < columnCount; index++) {
        int column = columns[index];
        destination[index] = column == NULL_PROJECTION
            ? 0 : readColumn(primaryKey, source, column);
      }
    }
    return status;
  }

  private long projectScanRow(
      long primaryKey,
      HeapRowResult source,
      SqlScanCursor cursor,
      long[] destination) {
    long nullMask = 0;
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int column = cursor.projectedColumn(index);
      if (column == NULL_PROJECTION) {
        destination[index] = 0;
        nullMask |= 1L << index;
      } else {
        destination[index] = readColumn(primaryKey, source, column);
        if (isNull(source, bound.table, column)) {
          nullMask |= 1L << index;
        }
      }
    }
    return nullMask;
  }

  private static StatusCode setProjectedText(
      SqlScanRowResult result,
      HeapRowResult source,
      TableDefinition definition,
      SqlScanCursor cursor,
      long nullMask) {
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int column = cursor.projectedColumn(index);
      if (column <= 0
          || !definition.isVarchar(column)
          || (nullMask & 1L << index) != 0) {
        continue;
      }
      long handle = source.getLong((column - 1) * Long.BYTES);
      StatusCode status = result.setUtf8At(
          index, source, (int) (handle >>> 32), (int) handle);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private long projectionNullMask(
      HeapRowResult source,
      TableDefinition definition,
      int[] columns,
      int columnCount) {
    long nullMask = 0;
    for (int index = 0; index < columnCount; index++) {
      if (columns[index] == NULL_PROJECTION
          || isNull(source, definition, columns[index])) {
        nullMask |= 1L << index;
      }
    }
    return nullMask;
  }

}
