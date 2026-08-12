package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.CatalogObjectResult;
import io.riverdb.engine.relational.CatalogIndexResult;
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
  private static final String TABLE_TYPE = "TABLE";
  private static final String VIEW_TYPE = "VIEW";
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
  private final CatalogObjectResult catalogObject = new CatalogObjectResult();
  private final CatalogIndexResult catalogIndex = new CatalogIndexResult();
  private final SqlExecutionResult aggregateExecution = new SqlExecutionResult();
  private final BoundSqlStatement bound;
  private final SqlNestedQueryExecution nestedExecution;
  private final SqlPhysicalPlan plan = new SqlPhysicalPlan();
  private final long[] projectedValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final SqlSortWorkspace sortWorkspace = new SqlSortWorkspace();
  private final SqlActiveScanState activeScan = new SqlActiveScanState();
  private final SqlExpressionEvaluator expressions;
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final ValueIndexLookupResult joinOuterIndexed = new ValueIndexLookupResult();
  private final RelationalScanCursor aggregateCursor = new RelationalScanCursor();
  private final RelationalScanResult aggregateRow = new RelationalScanResult();
  private final SqlScanRowResult explainRow = new SqlScanRowResult();
  private final ByteBuffer aggregateText = ByteBuffer.allocateDirect(
      io.riverdb.base.text.Utf8Text.MAXIMUM_BYTES);
  private boolean groupInputNull;
  private boolean groupAggregateInputNull;
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
    plan.setNestedDepth(query.blockCount());
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
        && command.type() == SqlCommandType.NEXT_SEQUENCE_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  StatusCode prepareNested() {
    return nestedExecution.prepare(explainOnly);
  }

  boolean correlatedScalar() {
    return nestedExecution.correlatedScalar();
  }

  boolean correlatedNestedChain() {
    return nestedExecution.correlatedNestedChain();
  }

  boolean recursiveNestedChain() {
    return nestedExecution.recursiveNestedChain();
  }

  boolean recursiveRootCorrelated() {
    return nestedExecution.recursiveRootCorrelated();
  }

  boolean explainOnly() {
    return explainOnly;
  }

  void refreshPreparedCommand() {
    command = query.root();
    plan.setCommand(command);
    plan.setNestedDepth(query.blockCount());
  }

  void setPreparedOrderColumn(int column) {
    plan.setOrderColumn(column);
  }

  StatusCode beginScan(SqlScanCursor cursor) {
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (command.type() == SqlCommandType.SHOW_TABLES) {
      return beginCatalogObjectScan(cursor);
    }
    if (command.type() == SqlCommandType.SHOW_INDEXES) {
      return beginCatalogIndexScan(cursor);
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
    StatusCode status = StatusCode.OK;
    if (status.isOk()
        && (command.type() == SqlCommandType.COUNT
            || command.type() == SqlCommandType.COUNT_VALUE
            || command.type() == SqlCommandType.NEXT_SEQUENCE_VALUE
            || isValueAggregate(command.type()))) {
      status = StatusCode.OK;
      if (status.isOk()) {
        plan.setAccessColumn(
            bound.accessPredicate >= 0
                ? bound.predicateColumn == 0
                    || bound.table.hasIndexOn(bound.predicateColumn)
                    ? bound.predicateColumn : -1
                : -1);
      }
      if (status.isOk()) {
        plan.setAggregate(
            command.type() == SqlCommandType.COUNT
                ? -1 : bound.projectedColumns[0]);
        plan.setResultColumn(
            0,
            bound.projectedColumnCount > 0 ? bound.projectedColumns[0] : -1,
            aggregateExecution.typeDescriptorAt(0),
            aggregateColumnName());
        status = activeScan.claimAggregate(
            aggregateExecution.value(),
            aggregateExecution.isNull(0),
            aggregateExecution.transactionActive(),
            aggregateExecution.commitSequence());
        status = claimCursor(cursor, status);
      }
      return status;
    }
    if (status.isOk() && isGroupAggregate(command.type())) {
      plan.setFilterCount(bound.predicateCount);
      if (status.isOk()
          && command.columnTableName(0).length() > 0
          && !matchesTableQualifier(command, command.columnTableName(0))) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int groupColumn = status.isOk()
          ? bound.table.findColumn(command.firstColumnName()) : -1;
      int aggregateColumn = -1;
      if (status.isOk() && command.type() != SqlCommandType.GROUP_COUNT) {
        if (command.columnCount() != 2
            || command.columnTableName(1).length() > 0
                && !matchesTableQualifier(command, command.columnTableName(1))) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
        } else {
          aggregateColumn = bound.table.findColumn(command.columnName(1));
          if (aggregateColumn < 0) {
            status = StatusCode.INVALID_EXTERNAL_INPUT;
          } else if (command.type() == SqlCommandType.GROUP_SUM
              && bound.table.typeDescriptor(aggregateColumn) != SqlTypeDescriptor.BIGINT) {
            status = StatusCode.DATATYPE_MISMATCH;
          } else if (command.hasGroupHaving()
              && (command.type() == SqlCommandType.GROUP_MIN
                  || command.type() == SqlCommandType.GROUP_MAX)
              && bound.table.typeDescriptor(aggregateColumn)
                  != SqlTypeDescriptor.BIGINT) {
            status = StatusCode.DATATYPE_MISMATCH;
          }
        }
      }
      if (status.isOk() && groupColumn < 0) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      boolean orderedInput = groupColumn == 0
          || groupColumn > 0
              && bound.table.hasIndexOn(groupColumn)
              && !bound.table.isNullable(groupColumn);
      boolean inputValueIndex = orderedInput && groupColumn > 0;
      plan.setSort(!orderedInput);
      plan.setAccessColumn(orderedInput ? groupColumn : -1);
      int sortedInputRows = -1;
      if (status.isOk() && orderedInput) {
        status = beginOrderedAggregateScan(
            cursor, groupColumn, inputValueIndex);
      } else if (status.isOk()) {
        boolean bounded = bound.accessPredicate >= 0;
        boolean equality = bounded && accessEquality();
        int scanIndexColumn = bounded
                && bound.predicateColumn > 0
                && bound.table.hasIndexOn(bound.predicateColumn)
            ? bound.predicateColumn : -1;
        inputValueIndex = scanIndexColumn > 0;
        plan.setAccessColumn(inputValueIndex
            ? scanIndexColumn : bounded && bound.predicateColumn == 0 ? 0 : -1);
        if (equality
            && (bound.predicateColumn == 0 || inputValueIndex)
            && accessValue() == Long.MAX_VALUE) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
        } else if (inputValueIndex) {
          status = session.beginValueScan(
              bound.table,
              scanIndexColumn,
              equality ? accessValue() : accessLowerInclusive(),
              equality ? accessValue() + 1 : accessUpperExclusive(),
              activeScan.relational());
        } else if (bounded && bound.predicateColumn == 0) {
          status = session.beginScan(
              bound.table,
              equality ? accessValue() : accessLowerInclusive(),
              equality ? accessValue() + 1 : accessUpperExclusive(),
              activeScan.relational());
        } else {
          status = session.beginScan(bound.table, activeScan.relational());
        }
        if (status.isOk() && !explainOnly) {
          bound.projectedColumns[0] = groupColumn;
          bound.projectedColumns[1] = aggregateColumn < 0
              ? NULL_PROJECTION : aggregateColumn;
          bound.projectedColumnCount = 2;
          status = materializeSortedScan(
              cursor, inputValueIndex, groupColumn);
          sortedInputRows = status.isOk() ? sortWorkspace.totalRows() : -1;
        }
      }
      if (status.isOk()) {
        plan.setGroupAggregate(groupColumn, aggregateColumn);
        plan.setResultColumn(
            0,
            groupColumn,
            bound.table.typeDescriptor(groupColumn),
            command.columnOutputName(0));
        plan.setResultColumn(
            1,
            aggregateColumn,
            command.type() == SqlCommandType.GROUP_MIN
                    || command.type() == SqlCommandType.GROUP_MAX
                ? bound.table.typeDescriptor(aggregateColumn)
                : SqlTypeDescriptor.BIGINT,
            groupAggregateColumnName());
        status = activeScan.claimSortedInput(sortedInputRows);
        status = claimCursor(cursor, status);
      }
      return status;
    }
    if (status.isOk() && command.type() == SqlCommandType.DISTINCT_SCAN) {
      plan.setFilterCount(bound.predicateCount);
      if (status.isOk()
          && command.columnTableName(0).length() > 0
          && !matchesTableQualifier(command, command.columnTableName(0))) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int distinctColumn = status.isOk()
          ? bound.table.findColumn(command.firstColumnName()) : -1;
      if (status.isOk() && distinctColumn < 0) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      boolean orderedInput = distinctColumn == 0
          || distinctColumn > 0
              && bound.table.hasIndexOn(distinctColumn)
              && !bound.table.isNullable(distinctColumn);
      boolean inputValueIndex = orderedInput && distinctColumn > 0;
      plan.setSort(!orderedInput);
      plan.setAccessColumn(orderedInput ? distinctColumn : -1);
      int sortedInputRows = -1;
      if (status.isOk() && orderedInput) {
        status = beginOrderedAggregateScan(
            cursor, distinctColumn, inputValueIndex);
      } else if (status.isOk()) {
        boolean bounded = bound.accessPredicate >= 0;
        boolean equality = bounded && accessEquality();
        int scanIndexColumn = bounded
                && bound.predicateColumn > 0
                && bound.table.hasIndexOn(bound.predicateColumn)
            ? bound.predicateColumn : -1;
        inputValueIndex = scanIndexColumn > 0;
        plan.setAccessColumn(inputValueIndex
            ? scanIndexColumn : bounded && bound.predicateColumn == 0 ? 0 : -1);
        if (equality
            && (bound.predicateColumn == 0 || inputValueIndex)
            && accessValue() == Long.MAX_VALUE) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
        } else if (inputValueIndex) {
          status = session.beginValueScan(
              bound.table,
              scanIndexColumn,
              equality ? accessValue() : accessLowerInclusive(),
              equality ? accessValue() + 1 : accessUpperExclusive(),
              activeScan.relational());
        } else if (bounded && bound.predicateColumn == 0) {
          status = session.beginScan(
              bound.table,
              equality ? accessValue() : accessLowerInclusive(),
              equality ? accessValue() + 1 : accessUpperExclusive(),
              activeScan.relational());
        } else {
          status = session.beginScan(bound.table, activeScan.relational());
        }
        if (status.isOk() && !explainOnly) {
          bound.projectedColumns[0] = distinctColumn;
          bound.projectedColumnCount = 1;
          status = materializeSortedScan(
              cursor, inputValueIndex, distinctColumn);
          sortedInputRows = status.isOk() ? sortWorkspace.totalRows() : -1;
        }
      }
      if (status.isOk()) {
        plan.setDistinct(distinctColumn);
        plan.setResultColumn(
            0,
            distinctColumn,
            bound.table.typeDescriptor(distinctColumn),
            command.columnOutputName(0));
        status = activeScan.claimSortedInput(sortedInputRows);
        status = claimCursor(cursor, status);
      }
      return status;
    }
    if (status.isOk() && command.type() == SqlCommandType.JOIN_SCAN) {
      plan.setFilterCount(bound.predicateCount);
      boolean predicate = status.isOk() && bound.accessPredicate >= 0;
      boolean equality = predicate && accessEquality();
      boolean indexedOuter = predicate
          && bound.predicateColumn > 0
          && bound.table.hasIndexOn(bound.predicateColumn);
      boolean primaryRange = predicate && bound.predicateColumn == 0;
      plan.setAccessColumn(
          indexedOuter ? bound.predicateColumn : primaryRange ? 0 : -1);
      int outerJoinColumn = status.isOk()
          ? bound.table.findColumn(command.joinOuterColumnName()) : -1;
      int innerJoinColumn = status.isOk()
          ? bound.joinTable.findColumn(command.joinInnerColumnName()) : -1;
      if (status.isOk()
          && predicate
          && equality
          && (indexedOuter || primaryRange)
          && accessValue() == Long.MAX_VALUE) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      long lower = !predicate ? 0
          : equality ? accessValue() : accessLowerInclusive();
      long upper = !predicate ? 0
          : equality ? accessValue() + 1 : accessUpperExclusive();
      if (status.isOk()) {
        status = indexedOuter
            ? session.beginValueScan(
                bound.table,
                bound.predicateColumn,
                lower,
                upper,
                activeScan.relational())
            : primaryRange
                ? session.beginScan(bound.table, lower, upper, activeScan.relational())
                : session.beginScan(bound.table, activeScan.relational());
      }
      if (status.isOk()) {
        plan.setJoin(
            outerJoinColumn,
            innerJoinColumn,
            command.isLeftJoin(),
            innerJoinColumn == 0 || bound.joinTable.hasIndexOn(innerJoinColumn),
            innerJoinColumn == 0
                || bound.joinTable.hasUniqueIndexOn(innerJoinColumn));
        for (int index = 0; index < bound.projectedColumnCount; index++) {
          int projection = bound.projectedColumns[index];
          plan.setResultColumn(
              index,
              projection,
              projection >= 0
                  ? bound.table.typeDescriptor(projection)
                  : bound.joinTable.typeDescriptor(-projection - 1),
              command.columnOutputName(index));
        }
        status = activeScan.claim();
        status = claimCursor(cursor, status);
      }
      return status;
    }
    if (!status.isOk()
        || command.type() != SqlCommandType.SCAN
            && command.type() != SqlCommandType.SELECT) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    plan.setCommand(command);
    plan.setNestedDepth(query.blockCount());
    plan.setFilterCount(bound.predicateCount);
    if (status.isOk()) {
      plan.setResultShape(
          bound.projectedColumns,
          projectionTypeDescriptors(
              bound.projectedColumns, bound.projectedColumnCount),
          bound.projectedColumnCount,
          command);
      for (int index = 0; index < bound.projectedColumnCount; index++) {
        if (plan.resultNameLength(index) == 0) {
          int projection = bound.projectedColumns[index];
          plan.setResultColumn(
              index,
              projection,
              plan.resultType(index),
              projection == NULL_PROJECTION
                  ? "null" : bound.table.columnName(projection));
        }
      }
    }
    if (status.isOk() && explainOnly && query.blockCount() > 1) {
      bound.accessPredicate = -1;
      bound.predicateColumn = -1;
    }
    int orderColumn = command.isOrdered() ? plan.orderColumn() : -1;
    if (status.isOk()
        && command.isOrdered()
        && orderColumn < 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean materializedSort = status.isOk()
        && command.isOrdered()
        && (bound.table.isVarchar(orderColumn)
            || command.isDescendingOrder()
            || orderColumn > 0
                && (!bound.table.hasIndexOn(orderColumn)
                    || bound.table.isNullable(orderColumn)));
    plan.setSort(materializedSort);
    boolean bounded = status.isOk() && bound.accessPredicate >= 0;
    boolean equality = bounded && accessEquality();
    int scanIndexColumn = status.isOk() && command.isOrdered() && !materializedSort
        ? orderColumn > 0 ? orderColumn : -1
        : status.isOk()
            && bound.predicateColumn > 0
            && bound.table.hasIndexOn(bound.predicateColumn)
            && !bound.table.isVarchar(bound.predicateColumn)
            ? bound.predicateColumn : -1;
    boolean valueIndex = scanIndexColumn > 0;
    plan.setAccessColumn(valueIndex
        ? scanIndexColumn
        : bounded && bound.predicateColumn == 0 ? 0 : -1);
    if (status.isOk()
        && equality
        && bound.predicateColumn == 0
        && scanIndexColumn < 0
        && accessValue() == Long.MAX_VALUE) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      if (valueIndex) {
        boolean boundedByScanIndex =
            bounded && bound.predicateColumn == scanIndexColumn;
        if (boundedByScanIndex) {
          long lower = equality ? accessValue() : accessLowerInclusive();
          long upper = equality ? accessValue() + 1 : accessUpperExclusive();
          status = accessValue() == Long.MAX_VALUE && equality
              ? StatusCode.INVALID_EXTERNAL_INPUT
              : session.beginValueScan(
                  bound.table, scanIndexColumn, lower, upper, activeScan.relational());
        } else {
          status = session.beginValueScan(
              bound.table, scanIndexColumn, activeScan.relational());
        }
      } else {
        status = bounded && bound.predicateColumn == 0
            ? session.beginScan(
                bound.table,
                equality ? accessValue() : accessLowerInclusive(),
                equality ? accessValue() + 1 : accessUpperExclusive(),
                activeScan.relational())
            : session.beginScan(bound.table, activeScan.relational());
      }
    }
    if (status.isOk()) {
      if (materializedSort && !explainOnly) {
        status = materializeSortedScan(cursor, valueIndex, orderColumn);
        if (status.isOk()) {
          status = activeScan.claimSorted(sortWorkspace.totalRows());
        }
      } else {
        status = activeScan.claim();
      }
      if (status.isOk()) {
        status = claimCursor(cursor, status);
      }
    }
    return status;
  }

  private StatusCode beginCatalogObjectScan(SqlScanCursor cursor) {
    StatusCode status = session.beginCatalogObjectScan(activeScan.catalogObjects());
    if (status.isOk()) {
      plan.setResultColumn(
          0, 0, SqlTypeDescriptor.varchar(64), "table_name");
      plan.setResultColumn(
          1, 1, SqlTypeDescriptor.varchar(64), "table_type");
      status = activeScan.claim();
      status = claimCursor(cursor, status);
    }
    return status;
  }

  private StatusCode beginCatalogIndexScan(SqlScanCursor cursor) {
    StatusCode status = session.beginCatalogIndexScan(
        command.tableName(), activeScan.catalogIndexes());
    if (status.isOk()) {
      plan.setResultColumn(
          0, 0, SqlTypeDescriptor.varchar(64), "index_name");
      plan.setResultColumn(
          1, 1, SqlTypeDescriptor.varchar(64), "column_name");
      plan.setResultColumn(2, 2, SqlTypeDescriptor.BOOLEAN, "is_unique");
      plan.setResultColumn(3, 3, SqlTypeDescriptor.BOOLEAN, "is_primary");
      plan.setResultColumn(4, 4, SqlTypeDescriptor.BOOLEAN, "is_constraint");
      status = activeScan.claim();
      status = claimCursor(cursor, status);
    }
    return status;
  }

  private void describePlan(SqlScanCursor cursor) {
    plan.resetSteps();
    if (plan.rowLimit() != Long.MAX_VALUE) {
      addPlanStep(PLAN_LIMIT, plan.rowLimit());
    }
    if (plan.aggregate()) {
      addPlanStep(
          PLAN_AGGREGATE,
          plan.aggregateColumn());
    } else if (plan.groupAggregate()) {
      addPlanStep(PLAN_GROUP, plan.groupAggregateColumn());
    } else if (plan.distinct()) {
      addPlanStep(PLAN_DISTINCT, plan.groupColumn());
    } else if (plan.join()) {
      addPlanStep(
          plan.leftJoin() ? PLAN_LEFT : PLAN_JOIN,
          plan.joinOuterColumn());
    }
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
      StatusCode status = session.nextCatalogObject(
          activeScan.catalogObjects(), catalogObject);
      if (!status.isOk()) {
        return status;
      }
      if (!catalogObject.isAvailable()) {
        return StatusCode.CONFLICT;
      }
      projectedValues[0] = 0;
      projectedValues[1] = 0;
      result.set(
          0,
          projectedValues,
          0,
          scanProjectionTypeDescriptors(cursor),
          2);
      status = result.setTextAt(0, catalogObject.name());
      if (status.isOk()) {
        status = result.setTextAt(
            1,
            catalogObject.type() == CatalogObjectResult.TABLE
                ? TABLE_TYPE : VIEW_TYPE);
      }
      if (status.isOk()) {
        cursor.rowReturned();
      }
      return status;
    }
    if (plan.catalogIndexScan()) {
      StatusCode status = session.nextCatalogIndex(
          activeScan.catalogIndexes(), catalogIndex);
      if (!status.isOk()) {
        return status;
      }
      if (!catalogIndex.isAvailable()) {
        return StatusCode.CONFLICT;
      }
      projectedValues[0] = 0;
      projectedValues[1] = 0;
      projectedValues[2] = catalogIndex.isUnique() ? 1 : 0;
      projectedValues[3] = catalogIndex.isPrimary() ? 1 : 0;
      projectedValues[4] = catalogIndex.isConstraint() ? 1 : 0;
      long nullMask = catalogIndex.isPrimary() ? 1 : 0;
      result.set(
          0,
          projectedValues,
          nullMask,
          scanProjectionTypeDescriptors(cursor),
          5);
      if (!catalogIndex.isPrimary()) {
        status = result.setTextAt(0, catalogIndex.indexName());
      }
      if (status.isOk()) {
        status = result.setTextAt(1, catalogIndex.columnName());
      }
      if (status.isOk()) {
        cursor.rowReturned();
      }
      return status;
    }
    if (plan.explainResult()) {
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
      StatusCode status = result.setPackedTextAt(
          0, plan.operator(step));
      if (status.isOk()) {
        activeScan.advancePlanStep();
        cursor.rowReturned();
      }
      return status;
    }
    if (!plan.aggregate() && cursor.limitReached()) {
      return StatusCode.CONFLICT;
    }
    if (plan.aggregate()) {
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
    if (plan.groupAggregate()) {
      return nextGroupAggregate(cursor, result);
    }
    if (plan.distinct()) {
      return nextDistinct(cursor, result);
    }
    if (plan.sorts()) {
      int sortedRow = activeScan.currentSortedRow();
      if (sortedRow < 0) {
        return StatusCode.CONFLICT;
      }
      StatusCode status = StatusCode.OK;
      long primaryKey;
      if (sortWorkspace.isSpilled()) {
        status = sortWorkspace.nextSpilled(
            cursor.projectedColumnCount(), projectedValues);
        primaryKey = sortWorkspace.outputPrimaryKey();
      } else {
        sortWorkspace.copyValuesAt(
            sortedRow, cursor.projectedColumnCount(), projectedValues);
        primaryKey = sortWorkspace.primaryKeyAt(sortedRow);
      }
      if (status.isOk()) {
        long nullMask = sortWorkspace.isSpilled()
            ? sortWorkspace.outputNullMask() : sortWorkspace.nullMaskAt(sortedRow);
        result.set(
            primaryKey,
            projectedValues,
            nullMask,
            scanProjectionTypeDescriptors(cursor),
            cursor.projectedColumnCount());
        if (!sortWorkspace.isSpilled() && sortWorkspace.containsText()) {
          status = setProjectedText(
              result, sortWorkspace.rowAt(sortedRow), bound.table, cursor, nullMask);
        }
        if (status.isOk()) {
          activeScan.advanceSortedRow();
          cursor.rowReturned();
        }
      }
      return status;
    }
    if (plan.join()) {
      return nextJoin(cursor, result);
    }
    StatusCode status = StatusCode.OK;
    while (status.isOk()) {
      long primaryKey;
      HeapRowResult source;
      if (plan.valueIndex()) {
        status = session.nextValueScan(
            bound.table, activeScan.relational(), result.relational(), indexed);
        primaryKey = indexed.key();
        source = indexed.row();
      } else {
        status = session.nextScan(
            activeScan.relational(), result.relational());
        primaryKey = result.relational().key();
        source = result.relational().row();
      }
      if (!status.isOk()) {
        return status;
      }
      status = validateRow(source);
      if (status.isOk()) {
        status = nestedExecution.evaluateBeforePredicates(primaryKey, source);
        source = nestedExecution.evaluatedRow(source);
      }
      if (status.isOk() && nestedExecution.rejectsOuterRow()) {
        continue;
      }
      if (status.isOk() && !matchesPredicates(primaryKey, source)) {
        continue;
      }
      if (status.isOk()) {
        status = nestedExecution.evaluateAfterPredicates(primaryKey, source);
        source = nestedExecution.evaluatedRow(source);
      }
      if (status.isOk() && nestedExecution.rejectsOuterRow()) {
        continue;
      }
      if (status.isOk()) {
        long nullMask = projectScanRow(
            primaryKey, source, cursor, projectedValues);
        result.set(
            primaryKey,
            projectedValues,
            nullMask,
            scanProjectionTypeDescriptors(cursor),
            cursor.projectedColumnCount());
        status = setProjectedText(result, source, bound.table, cursor, nullMask);
        if (status.isOk()) {
          cursor.rowReturned();
        }
      }
      return status;
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
    if (activeScan.catalogObjects().isActive()) {
      status = session.closeCatalogObjectScan(activeScan.catalogObjects());
    }
    if (status.isOk() && activeScan.catalogIndexes().isActive()) {
      status = session.closeCatalogIndexScan(activeScan.catalogIndexes());
    }
    if (status.isOk() && activeScan.joinInnerRelational().isActive()) {
      status = session.closeScan(activeScan.joinInnerRelational());
      if (status.isOk()) {
        activeScan.completeJoinInnerScan();
      }
    }
    if (status.isOk() && activeScan.relational().isActive()) {
      status = session.closeScan(activeScan.relational());
    }
    if (status.isOk() && sortWorkspace.hasResources()) {
      status = sortWorkspace.close();
    }
    if (status.isOk()) {
      status = nestedExecution.close();
    }
    return status;
  }

  StatusCode executePointQuery(SqlExecutionResult result) {
    if (command.type() == SqlCommandType.COUNT
        || command.type() == SqlCommandType.COUNT_VALUE
        || isValueAggregate(command.type())) {
      boolean sum = command.type() == SqlCommandType.SUM;
      boolean minimum = command.type() == SqlCommandType.MIN;
      boolean valueAggregate = isValueAggregate(command.type());
      boolean countValue = command.type() == SqlCommandType.COUNT_VALUE;
      long aggregate = 0;
      long aggregateHigh = 0;
      int aggregateTextLength = 0;
      boolean aggregatePresent = false;
      int aggregateColumn = valueAggregate ? bound.projectedColumns[0] : -1;
      boolean textAggregate = valueAggregate && bound.table.isVarchar(aggregateColumn);
      boolean filtered = bound.predicateCount > 0;
      boolean bounded = bound.accessPredicate >= 0;
      boolean equality = bounded && accessEquality();
      boolean indexed = bounded
          && bound.predicateColumn > 0
          && bound.table.hasIndexOn(bound.predicateColumn)
          && !bound.table.isVarchar(bound.predicateColumn);
      boolean boundedPrimaryKey = bounded && bound.predicateColumn == 0;
      if ((indexed || boundedPrimaryKey)
          && equality
          && accessValue() == Long.MAX_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      long lower = bounded
          ? equality ? accessValue() : accessLowerInclusive() : 0;
      long upper = bounded
          ? equality ? accessValue() + 1 : accessUpperExclusive() : 0;
      StatusCode status = indexed
          ? session.beginValueScan(
              bound.table, bound.predicateColumn, lower, upper, aggregateCursor)
          : boundedPrimaryKey
              ? session.beginScan(bound.table, lower, upper, aggregateCursor)
              : session.beginScan(bound.table, aggregateCursor);
      boolean aggregateActive = status.isOk();
      while (status.isOk()) {
        HeapRowResult source;
        long primaryKey;
        if (indexed) {
          status = session.nextValueScan(
              bound.table, aggregateCursor, aggregateRow, this.indexed);
          source = this.indexed.row();
          primaryKey = this.indexed.key();
        } else {
          status = session.nextScan(aggregateCursor, aggregateRow);
          source = aggregateRow.row();
          primaryKey = aggregateRow.key();
        }
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.OK;
          break;
        }
        if (status.isOk() && (filtered || valueAggregate || countValue)) {
          status = validateRow(source);
        }
        if (status.isOk() && filtered && !matchesPredicates(primaryKey, source)) {
          continue;
        }
        if (status.isOk()) {
          if (valueAggregate) {
            int column = aggregateColumn;
            if (!isNull(source, bound.table, column)) {
              long value = readColumn(primaryKey, source, column);
              if (textAggregate) {
                long handle = source.getLong((column - 1) * Long.BYTES);
                int textOffset = (int) (handle >>> 32);
                int textLength = (int) handle;
                int comparison = aggregatePresent
                    ? compareText(source, textOffset, textLength, aggregateText, aggregateTextLength)
                    : 0;
                if (!aggregatePresent
                    || minimum && comparison < 0
                    || !minimum && comparison > 0) {
                  aggregateText.clear();
                  for (int index = 0; index < textLength; index++) {
                    aggregateText.put(source.getByte(textOffset + index));
                  }
                  aggregateTextLength = textLength;
                }
              } else if (sum) {
                long previous = aggregate;
                aggregate += value;
                aggregateHigh += (value < 0 ? -1 : 0)
                    + (Long.compareUnsigned(aggregate, previous) < 0 ? 1 : 0);
              } else if (!aggregatePresent
                  || minimum && value < aggregate
                  || !minimum && value > aggregate) {
                aggregate = value;
              }
              aggregatePresent = true;
            }
          } else {
            int column = countValue ? bound.projectedColumns[0] : -1;
            if (!countValue || !isNull(source, bound.table, column)) {
              if (aggregate == Long.MAX_VALUE) {
                status = StatusCode.RESOURCE_EXHAUSTED;
              } else {
                aggregate++;
                aggregatePresent = true;
              }
            }
          }
        }
      }
      if (aggregateActive) {
        StatusCode close = session.closeScan(aggregateCursor);
        if (close.isOk()) {
          aggregateCursor.reset();
        }
        if (status.isOk()) {
          status = close;
        }
      }
      if (status.isOk()
          && sum
          && aggregatePresent
          && aggregateHigh != (aggregate < 0 ? -1 : 0)) {
        status = StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      }
      if (status.isOk()) {
        projectedValues[0] = aggregate;
        result.setProjection(
            0,
            projectedValues,
            valueAggregate && !aggregatePresent ? 1 : 0,
            aggregateProjectionTypeDescriptors(),
            1,
            0);
        if (textAggregate && aggregatePresent) {
          status = result.setUtf8At(0, aggregateText, 0, aggregateTextLength);
        }
      }
      return status;
    }
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
    if (status.isOk() && !matchesPredicates(primaryKey, source)) {
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
      if (status.isOk()) {
        status = validateRow(source);
      }
      if (status.isOk() && matchesPredicates(aggregateRow.key(), source)) {
        status = projectRow(
            aggregateRow.key(),
            source,
            bound.projectedColumns,
            bound.projectedColumnCount,
            projectedValues);
        long nullMask = projectionNullMask(
            source,
            bound.table,
            bound.projectedColumns,
            bound.projectedColumnCount);
        if (status.isOk()) {
          result.setProjection(
              aggregateRow.key(),
              projectedValues,
              nullMask,
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
              nullMask);
        }
        found = status.isOk();
        break;
      }
    }
    if (active) {
      StatusCode close = session.closeScan(aggregateCursor);
      if (close.isOk()) {
        aggregateCursor.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    return status.isOk() && !found ? StatusCode.CONFLICT : status;
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

  private static boolean isValueAggregate(SqlCommandType type) {
    return type == SqlCommandType.SUM
        || type == SqlCommandType.MIN
        || type == SqlCommandType.MAX;
  }

  private static boolean isScalarAggregate(SqlCommandType type) {
    return type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || isValueAggregate(type);
  }

  private static boolean isGroupAggregate(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

  private CharSequence aggregateColumnName() {
    CharSequence alias = command.columnAlias(0);
    if (alias.length() > 0) {
      return alias;
    }
    return command.type() == SqlCommandType.SUM ? "sum"
        : command.type() == SqlCommandType.MIN ? "min"
            : command.type() == SqlCommandType.MAX ? "max" : "count";
  }

  private CharSequence groupAggregateColumnName() {
    CharSequence alias = command.columnAlias(1);
    if (command.type() != SqlCommandType.GROUP_COUNT && alias.length() > 0) {
      return alias;
    }
    return command.type() == SqlCommandType.GROUP_SUM ? "sum"
        : command.type() == SqlCommandType.GROUP_MIN ? "min"
            : command.type() == SqlCommandType.GROUP_MAX ? "max" : "count";
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

  private int[] aggregateProjectionTypeDescriptors() {
    bound.projectedTypeDescriptors[0] = (command.type() == SqlCommandType.MIN
            || command.type() == SqlCommandType.MAX)
        && bound.projectedColumnCount > 0
        ? bound.table.typeDescriptor(bound.projectedColumns[0])
        : SqlTypeDescriptor.BIGINT;
    return bound.projectedTypeDescriptors;
  }

  private int[] groupProjectionTypeDescriptors(SqlScanCursor cursor) {
    bound.projectedTypeDescriptors[0] =
        bound.table.typeDescriptor(plan.groupColumn());
    bound.projectedTypeDescriptors[1] =
        (plan.commandType() == SqlCommandType.GROUP_MIN
            || plan.commandType() == SqlCommandType.GROUP_MAX)
        ? bound.table.typeDescriptor(plan.groupAggregateColumn())
        : SqlTypeDescriptor.BIGINT;
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

  private StatusCode nextGroupAggregate(
      SqlScanCursor cursor,
      SqlScanRowResult result) {
    while (true) {
      StatusCode status = nextGroupAggregateCandidate(cursor, result);
      if (!status.isOk()) {
        result.reset();
        return status;
      }
      if (!command.hasGroupHaving()
          || !result.isNull(1)
              && matchesComparison(
                  result.valueAt(1),
                  command.groupHavingComparison(),
                  command.groupHavingValue())) {
        cursor.rowReturned();
        return StatusCode.OK;
      }
    }
  }

  private StatusCode nextGroupAggregateCandidate(
      SqlScanCursor cursor,
      SqlScanRowResult result) {
    if (activeScan.groupInputExhausted() && !activeScan.hasGroupLookahead()) {
      return StatusCode.CONFLICT;
    }
    long groupValue;
    boolean groupNull;
    long inputValue;
    boolean inputNull;
    if (activeScan.hasGroupLookahead()) {
      groupValue = activeScan.takeGroupLookahead();
      groupNull = activeScan.groupLookaheadNull();
      inputValue = activeScan.groupLookaheadAggregateValue();
      inputNull = activeScan.groupLookaheadAggregateNull();
    } else {
      StatusCode first = nextGroupValue(cursor);
      if (first == StatusCode.CONFLICT) {
        activeScan.exhaustGroupInput();
        return StatusCode.CONFLICT;
      }
      if (!first.isOk()) {
        return first;
      }
      groupValue = projectedValues[0];
      groupNull = groupInputNull;
      inputValue = projectedValues[1];
      inputNull = groupAggregateInputNull;
    }
    SqlCommandType aggregateType = plan.commandType();
    long aggregate = aggregateType == SqlCommandType.GROUP_COUNT
        ? 1 : aggregateType == SqlCommandType.GROUP_COUNT_VALUE
            ? inputNull ? 0 : 1 : inputValue;
    boolean aggregateNull = aggregateType != SqlCommandType.GROUP_COUNT
        && aggregateType != SqlCommandType.GROUP_COUNT_VALUE
        && inputNull;
    while (true) {
      StatusCode status = nextGroupValue(cursor);
      if (status == StatusCode.CONFLICT) {
        activeScan.exhaustGroupInput();
        break;
      }
      if (!status.isOk()) {
        return status;
      }
      long value = projectedValues[0];
      if (groupInputNull != groupNull || !groupNull && value != groupValue) {
        activeScan.setGroupLookahead(
            value,
            groupInputNull,
            projectedValues[1],
            groupAggregateInputNull);
        break;
      }
      inputValue = projectedValues[1];
      inputNull = groupAggregateInputNull;
      if (aggregateType == SqlCommandType.GROUP_COUNT
          || aggregateType == SqlCommandType.GROUP_COUNT_VALUE && !inputNull) {
        if (aggregate == Long.MAX_VALUE) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        aggregate++;
      } else if (!inputNull && aggregateNull) {
        aggregate = inputValue;
        aggregateNull = false;
      } else if (!inputNull && aggregateType == SqlCommandType.GROUP_SUM) {
        long sum = aggregate + inputValue;
        if (expressions.arithmeticOverflow(aggregate, inputValue, sum, false)) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        aggregate = sum;
      } else if (!inputNull
          && aggregateType == SqlCommandType.GROUP_MIN
          && inputValue < aggregate) {
        aggregate = inputValue;
      } else if (!inputNull
          && aggregateType == SqlCommandType.GROUP_MAX
          && inputValue > aggregate) {
        aggregate = inputValue;
      }
    }
    projectedValues[0] = groupValue;
    projectedValues[1] = aggregate;
    long nullMask = groupNull ? 1 : 0;
    if (aggregateNull) {
      nullMask |= 1L << 1;
    }
    result.set(
        groupValue,
        projectedValues,
        nullMask,
        groupProjectionTypeDescriptors(cursor),
        2);
    return StatusCode.OK;
  }

  private StatusCode nextDistinct(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status = nextGroupValue(cursor);
      if (!status.isOk()) {
        return status;
      }
      long value = projectedValues[0];
      boolean nullValue = groupInputNull;
      if (activeScan.hasDistinctValue()
          && activeScan.distinctValueNull() == nullValue
          && (nullValue || activeScan.distinctValue() == value)) {
        continue;
      }
      activeScan.setDistinctValue(value, nullValue);
      bound.projectedTypeDescriptors[0] =
          bound.table.typeDescriptor(plan.groupColumn());
      result.set(
          value,
          projectedValues,
          nullValue ? 1 : 0,
          bound.projectedTypeDescriptors,
          1);
      cursor.rowReturned();
      return StatusCode.OK;
    }
  }

  private StatusCode nextJoin(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      if (activeScan.joinInnerScanActive()) {
        StatusCode inner;
        HeapRowResult innerRow;
        long innerKey;
        if (plan.joinInnerIndexed()) {
          inner = session.nextNonUniqueValueLookup(
              bound.joinTable, activeScan.joinInnerRelational(), indexed);
          innerRow = indexed.row();
          innerKey = indexed.key();
        } else {
          inner = session.nextScan(
              activeScan.joinInnerRelational(), aggregateRow);
          innerRow = aggregateRow.row();
          innerKey = aggregateRow.key();
        }
        if (inner == StatusCode.CONFLICT) {
          boolean unmatched = plan.leftJoin() && !activeScan.joinMatched();
          inner = session.closeScan(activeScan.joinInnerRelational());
          if (inner.isOk()) {
            activeScan.completeJoinInnerScan();
            inner = activeScan.joinInnerRelational().reset();
          }
          if (!inner.isOk()) {
            return inner;
          }
          if (unmatched && matchesNullExtendedJoinPredicates()) {
            return setUnmatchedJoinRow(cursor, result);
          }
          continue;
        }
        if (!inner.isOk()) {
          return inner;
        }
        inner = validateRow(innerRow, bound.joinTable);
        if (!inner.isOk()) {
          return inner;
        }
        if (!plan.joinInnerIndexed()
            && (isNull(innerRow, bound.joinTable, plan.joinInnerColumn())
                || readColumn(innerKey, innerRow, plan.joinInnerColumn())
                    != activeScan.joinMatchValue())) {
          continue;
        }
        activeScan.matchJoin();
        if (!matchesJoinPredicates(innerKey, innerRow, false)) {
          continue;
        }
        long nullMask = 0;
        for (int index = 0; index < cursor.projectedColumnCount(); index++) {
          int projection = cursor.projectedColumn(index);
          if (projection >= 0) {
            projectedValues[index] = activeScan.joinOuterProjectedValue(index);
            if (activeScan.joinOuterProjectedNull(index)) {
              nullMask |= 1L << index;
            }
          } else {
            int column = -projection - 1;
            projectedValues[index] = readColumn(innerKey, innerRow, column);
            if (isNull(innerRow, bound.joinTable, column)) {
              nullMask |= 1L << index;
            }
          }
        }
        result.set(
            activeScan.joinOuterKey(),
            projectedValues,
            nullMask,
            scanProjectionTypeDescriptors(cursor),
            cursor.projectedColumnCount());
        cursor.rowReturned();
        return StatusCode.OK;
      }
      StatusCode status;
      long outerKey;
      HeapRowResult outerRow;
      if (plan.valueIndex()) {
        status = session.nextValueScan(
            bound.table, activeScan.relational(), aggregateRow, joinOuterIndexed);
        outerKey = joinOuterIndexed.key();
        outerRow = joinOuterIndexed.row();
      } else {
        status = session.nextScan(activeScan.relational(), aggregateRow);
        outerKey = aggregateRow.key();
        outerRow = aggregateRow.row();
      }
      if (!status.isOk()) {
        return status;
      }
      status = validateRow(outerRow, bound.table);
      if (!status.isOk()) {
        return status;
      }
      if (!matchesJoinPredicates(outerKey, outerRow, true)) {
        continue;
      }
      if (plan.leftJoin() || !plan.joinInnerUnique()) {
        activeScan.rememberJoinOuter(outerKey);
        for (int index = 0; index < cursor.projectedColumnCount(); index++) {
          int projection = cursor.projectedColumn(index);
          if (projection >= 0) {
            activeScan.setJoinOuterProjectedValue(
                index,
                readColumn(outerKey, outerRow, projection),
                isNull(outerRow, bound.table, projection));
          }
        }
      }
      if (isNull(outerRow, bound.table, plan.joinOuterColumn())) {
        if (plan.leftJoin() && matchesNullExtendedJoinPredicates()) {
          return setUnmatchedJoinRow(cursor, result);
        }
        continue;
      }
      long joinValue = readColumn(
          outerKey, outerRow, plan.joinOuterColumn());
      if (!plan.joinInnerUnique()) {
        status = plan.joinInnerIndexed()
            ? joinValue == Long.MAX_VALUE
                ? StatusCode.INVALID_EXTERNAL_INPUT
                : session.beginNonUniqueValueLookup(
                    bound.joinTable,
                    plan.joinInnerColumn(),
                    joinValue,
                    activeScan.joinInnerRelational())
            : session.beginScan(bound.joinTable, activeScan.joinInnerRelational());
        if (status == StatusCode.CONFLICT
            || status == StatusCode.INVALID_EXTERNAL_INPUT) {
          if (plan.leftJoin() && matchesNullExtendedJoinPredicates()) {
            return setUnmatchedJoinRow(cursor, result);
          }
          continue;
        }
        if (!status.isOk()) {
          return status;
        }
        activeScan.beginJoinInnerScan(outerKey, joinValue);
        continue;
      }
      long innerKey = joinValue;
      HeapRowResult innerRow = fetched;
      if (plan.joinInnerColumn() == 0) {
        status = session.fetch(bound.joinTable, joinValue, fetched);
      } else {
        status = session.fetchByUniqueValue(
            bound.joinTable, plan.joinInnerColumn(), joinValue, indexed);
        innerKey = indexed.key();
        innerRow = indexed.row();
      }
      if (status == StatusCode.CONFLICT
          || status == StatusCode.INVALID_EXTERNAL_INPUT) {
        if (plan.leftJoin() && matchesNullExtendedJoinPredicates()) {
          return setUnmatchedJoinRow(cursor, result);
        }
        continue;
      }
      if (!status.isOk()) {
        return status;
      }
      status = validateRow(innerRow, bound.joinTable);
      if (!status.isOk()) {
        return status;
      }
      if (!matchesJoinPredicates(innerKey, innerRow, false)) {
        continue;
      }
      long nullMask = 0;
      for (int index = 0; index < cursor.projectedColumnCount(); index++) {
        int projection = cursor.projectedColumn(index);
        if (projection >= 0) {
          projectedValues[index] = readColumn(outerKey, outerRow, projection);
          if (isNull(outerRow, bound.table, projection)) {
            nullMask |= 1L << index;
          }
        } else {
          int column = -projection - 1;
          projectedValues[index] = readColumn(innerKey, innerRow, column);
          if (isNull(innerRow, bound.joinTable, column)) {
            nullMask |= 1L << index;
          }
        }
      }
      result.set(
          outerKey,
          projectedValues,
          nullMask,
          scanProjectionTypeDescriptors(cursor),
          cursor.projectedColumnCount());
      cursor.rowReturned();
      return StatusCode.OK;
    }
  }

  private StatusCode setUnmatchedJoinRow(
      SqlScanCursor cursor,
      SqlScanRowResult result) {
    long nullMask = 0;
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      if (cursor.projectedColumn(index) >= 0) {
        projectedValues[index] = activeScan.joinOuterProjectedValue(index);
        if (activeScan.joinOuterProjectedNull(index)) {
          nullMask |= 1L << index;
        }
      } else {
        projectedValues[index] = 0;
        nullMask |= 1L << index;
      }
    }
    result.set(
        activeScan.joinOuterKey(),
        projectedValues,
        nullMask,
        scanProjectionTypeDescriptors(cursor),
        cursor.projectedColumnCount());
    cursor.rowReturned();
    return StatusCode.OK;
  }

  private boolean matchesNullExtendedJoinPredicates() {
    for (int index = 0; index < bound.predicateCount; index++) {
      if (bound.predicateColumns[index] >= 0) {
        continue;
      }
      if (!command.isNullPredicate(index)
          || command.isNullPredicateNegated(index)) {
        return false;
      }
    }
    return true;
  }

  private StatusCode nextGroupValue(SqlScanCursor cursor) {
    if (plan.sorts()) {
      int sortedRow = activeScan.currentSortedRow();
      if (sortedRow < 0) {
        return StatusCode.CONFLICT;
      }
      StatusCode status = StatusCode.OK;
      long nullMask;
      if (sortWorkspace.isSpilled()) {
        status = sortWorkspace.nextSpilled(2, projectedValues);
        nullMask = sortWorkspace.outputNullMask();
      } else {
        sortWorkspace.copyValuesAt(sortedRow, 2, projectedValues);
        nullMask = sortWorkspace.nullMaskAt(sortedRow);
      }
      if (status.isOk()) {
        activeScan.advanceSortedRow();
        groupInputNull = (nullMask & 1) != 0;
        groupAggregateInputNull = plan.groupAggregateColumn() >= 0
            && (nullMask & 1L << 1) != 0;
      }
      return status;
    }
    while (true) {
      StatusCode status;
      long primaryKey;
      HeapRowResult source;
      if (plan.valueIndex()) {
        status = session.nextValueScan(
            bound.table, activeScan.relational(), aggregateRow, indexed);
        primaryKey = indexed.key();
        source = indexed.row();
      } else {
        status = session.nextScan(activeScan.relational(), aggregateRow);
        primaryKey = aggregateRow.key();
        source = aggregateRow.row();
      }
      if (status.isOk()) {
        status = validateRow(source, bound.table);
      }
      if (!status.isOk()) {
        return status;
      }
      if (!matchesPredicates(primaryKey, source)) {
        continue;
      }
      int column = plan.groupColumn();
      projectedValues[0] = column == 0
          ? primaryKey : source.getLong((column - 1) * Long.BYTES);
      groupInputNull = isNull(source, bound.table, column);
      int aggregateColumn = plan.groupAggregateColumn();
      groupAggregateInputNull = aggregateColumn >= 0
          && isNull(source, bound.table, aggregateColumn);
      projectedValues[1] = aggregateColumn < 0
          ? 0 : readColumn(primaryKey, source, aggregateColumn);
      return StatusCode.OK;
    }
  }

  private StatusCode beginOrderedAggregateScan(
      SqlScanCursor cursor,
      int orderedColumn,
      boolean valueIndex) {
    int boundedPredicate = -1;
    for (int index = 0;
        !command.hasDisjunction() && index < bound.predicateCount;
        index++) {
      if (bound.predicateColumns[index] == orderedColumn
          && (command.isEqualityPredicate(index)
              || command.isRangePredicate(index))
          && (boundedPredicate < 0 || command.isEqualityPredicate(index))) {
        boundedPredicate = index;
        if (command.isEqualityPredicate(index)) {
          break;
        }
      }
    }
    if (boundedPredicate < 0) {
      return valueIndex
          ? session.beginValueScan(
              bound.table, orderedColumn, activeScan.relational())
          : session.beginScan(bound.table, activeScan.relational());
    }
    boolean equality = command.isEqualityPredicate(boundedPredicate);
    long lower = equality
        ? command.predicateValue(boundedPredicate)
        : command.predicateLowerInclusive(boundedPredicate);
    if (equality && lower == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long upper = equality
        ? lower + 1
        : command.predicateUpperExclusive(boundedPredicate);
    return valueIndex
        ? session.beginValueScan(
            bound.table, orderedColumn, lower, upper, activeScan.relational())
        : session.beginScan(bound.table, lower, upper, activeScan.relational());
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

  private boolean matchesPredicates(long primaryKey, HeapRowResult source) {
    if (nestedExecution.rejectsOuterRow()) {
      return false;
    }
    boolean conjunction = true;
    for (int index = 0; index < bound.predicateCount; index++) {
      if (command.predicateStartsDisjunction(index)) {
        if (conjunction) {
          return true;
        }
        conjunction = true;
      }
      if (!conjunction) {
        continue;
      }
      long value = readColumn(
          primaryKey, source, bound.predicateColumns[index]);
      boolean nullValue = isNull(
          source, bound.table, bound.predicateColumns[index]);
      if (command.isNullPredicate(index)) {
        if (nullValue == command.isNullPredicateNegated(index)) {
          conjunction = false;
        }
        continue;
      }
      if (nullValue) {
        conjunction = false;
        continue;
      }
      if (query.hasMembershipPredicate()
          && query.membershipPredicate() == index) {
        if (!nestedExecution.matchesMembership(
            value, source, bound.predicateColumns[index])) {
          conjunction = false;
        }
        continue;
      }
      if (query.hasScalarPredicate()
          && query.scalarPredicate() == index) {
        if (!nestedExecution.matchesScalar(value)) {
          conjunction = false;
        }
        continue;
      }
      if (bound.table.isVarchar(bound.predicateColumns[index])
          ? !matchesTextComparison(
              source, bound.table, bound.predicateColumns[index], command, index)
          : !matchesComparison(value, command, index)) {
        conjunction = false;
      }
    }
    return conjunction;
  }

  private boolean matchesJoinPredicates(
      long primaryKey,
      HeapRowResult source,
      boolean outer) {
    for (int index = 0; index < bound.predicateCount; index++) {
      int descriptor = bound.predicateColumns[index];
      if (outer != (descriptor >= 0)) {
        continue;
      }
      int column = outer ? descriptor : -descriptor - 1;
      TableDefinition definition = outer ? bound.table : bound.joinTable;
      boolean nullValue = isNull(source, definition, column);
      if (command.isNullPredicate(index)) {
        if (nullValue == command.isNullPredicateNegated(index)) {
          return false;
        }
        continue;
      }
      if (nullValue) {
        return false;
      }
      long value = readColumn(primaryKey, source, column);
      if (definition.isVarchar(column)
          ? !matchesTextComparison(source, definition, column, command, index)
          : !matchesComparison(value, command, index)) {
        return false;
      }
    }
    return true;
  }

  private boolean accessEquality() {
    return bound.accessPredicate >= 0
        && command.isEqualityPredicate(bound.accessPredicate);
  }

  private boolean matchesComparison(
      long actual,
      BoundSqlQuery.Block source,
      int predicate) {
    SqlComparison comparison = source.comparison(predicate);
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return actual >= source.predicateLowerInclusive(predicate)
          && actual < source.predicateUpperExclusive(predicate);
    }
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = matchesLiteralMembership(actual, source, predicate);
      return comparison == SqlComparison.IN
          ? equal : !equal && !source.literalMembershipHasNull(predicate);
    }
    return expressions.matchesComparison(
        actual, comparison, source.predicateValue(predicate));
  }

  private boolean matchesTextComparison(
      HeapRowResult actual,
      TableDefinition definition,
      int column,
      BoundSqlQuery.Block expected,
      int predicate) {
    SqlComparison comparison = expected.comparison(predicate);
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = false;
      for (int index = 0; index < expected.literalMembershipCount(predicate); index++) {
        if (compareText(
            actual, definition, column, expected,
            expected.literalMembershipValue(predicate, index)) == 0) {
          equal = true;
          break;
        }
      }
      return comparison == SqlComparison.IN
          ? equal : !equal && !expected.literalMembershipHasNull(predicate);
    }
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return compareText(
              actual, definition, column, expected,
              expected.predicateLowerInclusive(predicate)) >= 0
          && compareText(
              actual, definition, column, expected,
              expected.predicateUpperExclusive(predicate)) < 0;
    }
    int compared = compareText(
        actual, definition, column, expected, expected.predicateValue(predicate));
    return switch (comparison) {
      case EQUAL -> compared == 0;
      case NOT_EQUAL -> compared != 0;
      case LESS_THAN -> compared < 0;
      case LESS_OR_EQUAL -> compared <= 0;
      case GREATER_THAN -> compared > 0;
      case GREATER_OR_EQUAL -> compared >= 0;
      case HALF_OPEN_RANGE, IN, NOT_IN -> false;
    };
  }

  private int compareText(
      HeapRowResult actual,
      TableDefinition definition,
      int column,
      BoundSqlQuery.Block expected,
      long expectedHandle) {
    long actualHandle = actual.getLong((column - 1) * Long.BYTES);
    int actualOffset = (int) (actualHandle >>> 32);
    int actualLength = (int) actualHandle;
    int expectedLength = expected.textByteLength(expectedHandle);
    if (actualOffset < 0 || actualLength < 0 || expectedLength < 0) {
      return Integer.MIN_VALUE;
    }
    int common = Math.min(actualLength, expectedLength);
    for (int index = 0; index < common; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(actual.getByte(actualOffset + index)),
          Byte.toUnsignedInt(expected.textByteAt(expectedHandle, index)));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(actualLength, expectedLength);
  }

  private int compareText(
      HeapRowResult left,
      int leftOffset,
      int leftLength,
      ByteBuffer right,
      int rightLength) {
    return expressions.compareText(
        left, leftOffset, leftLength, right, rightLength);
  }

  private boolean matchesComparison(
      long actual,
      SqlComparison comparison,
      long expected) {
    return expressions.matchesComparison(actual, comparison, expected);
  }

  private boolean matchesLiteralMembership(
      long actual,
      BoundSqlQuery.Block source,
      int predicate) {
    int lower = 0;
    int upper = source.literalMembershipCount(predicate);
    while (lower < upper) {
      int middle = (lower + upper) >>> 1;
      long candidate = source.literalMembershipValue(predicate, middle);
      if (candidate < actual) {
        lower = middle + 1;
      } else if (candidate > actual) {
        upper = middle;
      } else {
        return true;
      }
    }
    return false;
  }

  private long accessValue() {
    return command.predicateValue(bound.accessPredicate);
  }

  private long accessLowerInclusive() {
    return command.predicateLowerInclusive(bound.accessPredicate);
  }

  private long accessUpperExclusive() {
    return command.predicateUpperExclusive(bound.accessPredicate);
  }

  private static boolean matchesTableQualifier(
      BoundSqlQuery.Block qualified,
      CharSequence name) {
    return sameName(name, qualified.tableName())
        || qualified.tableAlias().length() > 0
            && sameName(name, qualified.tableAlias());
  }

  private static boolean matchesJoinTableQualifier(
      BoundSqlQuery.Block qualified,
      CharSequence name) {
    return sameName(name, qualified.joinTableName())
        || qualified.joinTableAlias().length() > 0
            && sameName(name, qualified.joinTableAlias());
  }

  private static boolean sameName(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) {
      return false;
    }
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) {
        return false;
      }
    }
    return true;
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

  private StatusCode materializeSortedScan(
      SqlScanCursor cursor,
      boolean valueIndex,
      int orderColumn) {
    boolean containsText = bound.table.isVarchar(orderColumn);
    for (int index = 0; index < bound.projectedColumnCount; index++) {
      int projection = bound.projectedColumns[index];
      containsText |= projection > 0 && bound.table.isVarchar(projection);
    }
    StatusCode status = sortWorkspace.begin(
        bound.table,
        command.isDescendingOrder(),
        orderColumn,
        bound.projectedColumnCount,
        containsText);
    while (status.isOk()) {
      long primaryKey;
      HeapRowResult source;
      if (valueIndex) {
        status = session.nextValueScan(
            bound.table, activeScan.relational(), aggregateRow, indexed);
        primaryKey = indexed.key();
        source = indexed.row();
      } else {
        status = session.nextScan(activeScan.relational(), aggregateRow);
        primaryKey = aggregateRow.key();
        source = aggregateRow.row();
      }
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(source);
      }
      if (status.isOk()) {
        status = nestedExecution.evaluateBeforePredicates(primaryKey, source);
        source = nestedExecution.evaluatedRow(source);
      }
      if (status.isOk() && nestedExecution.rejectsOuterRow()) {
        continue;
      }
      if (status.isOk() && !matchesPredicates(primaryKey, source)) {
        continue;
      }
      if (status.isOk()) {
        status = nestedExecution.evaluateAfterPredicates(primaryKey, source);
        source = nestedExecution.evaluatedRow(source);
      }
      if (status.isOk() && nestedExecution.rejectsOuterRow()) {
        continue;
      }
      if (status.isOk()) {
        long nullMask = 0;
        for (int index = 0; index < bound.projectedColumnCount; index++) {
          int projection = bound.projectedColumns[index];
          projectedValues[index] = projection == NULL_PROJECTION
              ? 0 : readColumn(primaryKey, source, projection);
          if (projection == NULL_PROJECTION || isNull(source, bound.table, projection)) {
            nullMask |= 1L << index;
          }
        }
        status = sortWorkspace.append(
            readColumn(primaryKey, source, orderColumn),
            isNull(source, bound.table, orderColumn),
            primaryKey,
            projectedValues,
            nullMask,
            source);
      }
    }
    StatusCode close = session.closeScan(activeScan.relational());
    if (!close.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      status = sortWorkspace.finish();
    }
    if (!status.isOk()) {
      StatusCode cleanup = sortWorkspace.close();
      if (!cleanup.isOk()) {
        return cleanup;
      }
    }
    return status;
  }

}
