package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.storage.heap.HeapRowResult;

/** Materializes and advances the bounded sort operator for one SQL session. */
final class SqlSortExecution {
  private static final int NULL_PROJECTION = BoundSqlStatement.NULL_PROJECTION;
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlPhysicalPlan plan;
  private final SqlActiveScanState scan;
  private final SqlExpressionEvaluator expressions;
  private final SqlSubqueryGraphExecution subqueries;
  private final SqlBoundPredicateEvaluator predicates;
  private final SqlRowProjectionEvaluator projections;
  private final SqlCurrentRowProtection currentRows;
  private final SqlProjectionResultWriter projectionResults =
      new SqlProjectionResultWriter();
  private final SqlRetainedArrayAllocator allocator;
  private final SqlProjectedRow projected;
  private final SqlJoinChainSource joinSource;
  private final SqlSortWorkspace workspace;
  private final SqlJoinSortInput joinInput;
  private final RelationalScanResult row = new RelationalScanResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private long[] values = new long[0];
  private long[] highs = new long[0];
  private HeapRowResult groupSource;
  private boolean joinedRows;

  SqlSortExecution(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlPhysicalPlan physicalPlan,
      SqlActiveScanState activeScan,
      SqlExpressionEvaluator evaluator,
      SqlSubqueryGraphExecution graph,
      SqlBoundPredicateEvaluator predicateEvaluator,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlCurrentRowProtection currentRowProtection,
      SqlJoinChainSource chainSource,
      SqlSessionShapeBudget shapeBudget) {
    session = relationalSession;
    bound = statement;
    plan = physicalPlan;
    scan = activeScan;
    expressions = evaluator;
    subqueries = graph;
    predicates = predicateEvaluator;
    projections = projectionEvaluator;
    currentRows = currentRowProtection;
    joinSource = chainSource;
    allocator = SqlRetainedArrayAllocator.STANDARD;
    projected = new SqlProjectedRow(allocator);
    workspace = new SqlSortWorkspace(allocator, shapeBudget);
    joinInput = new SqlJoinSortInput(
        statement, projectionEvaluator, chainSource, workspace, allocator);
  }

  StatusCode materializeJoin() {
    StatusCode status = reserveValues(bound.projectedColumnCount);
    if (status.isOk()) status = joinInput.begin();
    if (status.isOk()) joinedRows = true;
    while (status.isOk()) {
      status = joinSource.next();
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = joinInput.append();
    }
    StatusCode closed = subqueries.hasResources()
        ? StatusCode.CONFLICT : joinSource.close();
    return finishAfterSource(status, closed);
  }

  StatusCode materialize(boolean valueIndex, int orderColumn) {
    joinedRows = false;
    BoundSqlQuery.Block command = bound.executableQuery.root();
    boolean textKey = bound.sortKeyProjection < 0
        && bound.table.isVarchar(orderColumn);
    int storedProjections = SqlBinder.isGroupAggregate(command.type())
        ? bound.projectionPrograms.count() : bound.projectedColumnCount;
    int groupKeys = SqlBinder.isGroupAggregate(command.type())
        ? bound.command.groupExpressionCount()
        : command.type() == io.riverdb.sql.SqlCommandType.DISTINCT_SCAN
            ? bound.projectedColumnCount
            : bound.command.orderExpressionCount() > 1
                ? bound.command.orderExpressionCount() : 0;
    StatusCode status = reserveValues(storedProjections);
    if (status.isOk()) status = workspace.begin(
        bound.table,
        command.isDescendingOrder(),
        storedProjections,
        containsText(textKey),
        hasGeneratedText(),
        textKey,
        sortKeyDescriptor(orderColumn),
        bound.command,
        bound,
        groupKeys,
        SqlBinder.isGroupAggregate(command.type()));
    while (status.isOk()) {
      status = nextInput(valueIndex);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        long primaryKey = valueIndex ? indexed.key() : row.key();
        HeapRowResult source = valueIndex ? indexed.row() : row.row();
        status = append(primaryKey, source, orderColumn);
      }
    }
    return finish(status);
  }

  private StatusCode reserveValues(int count) {
    int capacity = BoundedArrayGrowth.capacity(
        values.length, count, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      long[] nextValues = capacity == values.length
          ? values : allocator.longs(capacity);
      long[] nextHighs = capacity == highs.length
          ? highs : allocator.longs(capacity);
      StatusCode status = projected.reserve(count);
      if (!status.isOk()) return status;
      for (int projection = 0; projection < count; projection++) {
        if (SqlTypeDescriptor.typeId(bound.projectionPrograms.resultDescriptor(projection))
            == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
          status = projected.prepareText(projection);
          if (!status.isOk()) return status;
        }
      }
      values = nextValues;
      highs = nextHighs;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode next(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      long sortedOrdinal = scan.currentSortedOrdinal();
      if (sortedOrdinal < 0) return StatusCode.CONFLICT;
      int sortedRow = workspace.isSpilled() ? 0 : (int) sortedOrdinal;
      StatusCode status = readSortedRow(cursor, sortedRow);
      if (!status.isOk()) return status;
      long primaryKey = workspace.isSpilled()
          ? workspace.outputPrimaryKey() : workspace.primaryKeyAt(sortedRow);
      if (!joinedRows && bound.command.isSelectForUpdate()) {
        status = currentRows.lockAndRecheck(primaryKey);
        if (status == StatusCode.CONFLICT) {
          scan.advanceSortedRow();
          continue;
        }
        if (!status.isOk()) return status;
        status = projections.project(primaryKey, currentRows.row(), projected);
        if (status.isOk()) status = projectionResults.writeScan(
            result, primaryKey, currentRows.row(), bound.table, cursor,
            projectionTypes(cursor), projected);
        status = currentRows.finish(status);
      } else {
        status = publishSortedRow(cursor, result, sortedRow, primaryKey);
      }
      if (!status.isOk()) return status;
      scan.advanceSortedRow();
      cursor.rowReturned();
      return StatusCode.OK;
    }
  }

  private StatusCode readSortedRow(SqlScanCursor cursor, int sortedRow) {
    if (workspace.isSpilled()) {
      return workspace.nextSpilled(cursor.projectedColumnCount(), highs, values);
    }
    workspace.copyValuesAt(sortedRow, cursor.projectedColumnCount(), values);
    workspace.copyHighsAt(sortedRow, cursor.projectedColumnCount(), highs);
    workspace.selectNullWordsAt(sortedRow);
    return StatusCode.OK;
  }

  private StatusCode publishSortedRow(
      SqlScanCursor cursor, SqlScanRowResult result, int sortedRow, long primaryKey) {
    StatusCode status = result.setWords(
        primaryKey, highs, values, workspace,
        projectionTypes(cursor), cursor.projectedColumnCount());
    if (status.isOk() && workspace.containsText()) {
      HeapRowResult source = workspace.isSpilled()
          ? workspace.spilledRow() : workspace.rowAt(sortedRow);
      status = joinedRows
          ? joinInput.setText(result, source, cursor, workspace)
          : setProjectedText(result, source, bound.table, cursor, workspace);
    }
    if (status.isOk()) status = workspace.setGeneratedText(result, sortedRow);
    if (!status.isOk()) result.reset();
    return status;
  }

  StatusCode nextGroupValue(long[] destination, SqlProjectedRow projected) {
    long sortedOrdinal = scan.currentSortedOrdinal();
    if (sortedOrdinal < 0) {
      return StatusCode.CONFLICT;
    }
    int sortedRow = workspace.isSpilled() ? 0 : (int) sortedOrdinal;
    StatusCode status;
    int count = bound.projectionPrograms.count();
    if (workspace.isSpilled()) {
      status = workspace.nextSpilled(count, highs, destination);
      groupSource = workspace.spilledRow();
    } else {
      workspace.copyValuesAt(sortedRow, count, destination);
      workspace.copyHighsAt(sortedRow, count, highs);
      workspace.selectNullWordsAt(sortedRow);
      groupSource = workspace.rowAt(sortedRow);
      status = StatusCode.OK;
    }
    if (status.isOk()) {
      projected.reset(count);
      for (int lane = 0; lane < count; lane++) {
        if (workspace.nullAt(lane)) projected.setNull(lane);
        else projected.setDecimal128(lane, highs[lane], destination[lane]);
      }
      workspace.copyGeneratedText(projected, sortedRow);
      scan.advanceSortedRow();
    }
    return status;
  }

  HeapRowResult groupSource() { return groupSource; }

  int outputNullWordCount() { return workspace.nullWordCount(); }

  long outputNullWord(int word) { return workspace.nullWord(word); }

  long totalRows() {
    return workspace.totalRows();
  }

  boolean hasResources() {
    return workspace.hasResources();
  }

  StatusCode close() {
    StatusCode status = workspace.close();
    if (status.isOk()) {
      joinedRows = false;
      joinInput.clear();
    }
    return status;
  }

  private boolean containsText(boolean textKey) {
    if (textKey) return true;
    for (int index = 0; index < bound.projectionPrograms.count(); index++) {
      int projection = bound.projectionPrograms.rawColumn(index);
      if (projection >= 0 && bound.table.isVarchar(projection)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasGeneratedText() {
    for (int index = 0; index < bound.projectionPrograms.count(); index++) {
      if (bound.projectionPrograms.rawColumn(index) < 0
          && SqlTypeDescriptor.typeId(
              bound.projectionPrograms.resultDescriptor(index))
              == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        return true;
      }
    }
    return false;
  }

  private int sortKeyDescriptor(int orderColumn) {
    return bound.sortKeyProjection >= 0
        ? bound.projectionPrograms.resultDescriptor(bound.sortKeyProjection)
        : bound.table.typeDescriptor(orderColumn);
  }

  private StatusCode nextInput(boolean valueIndex) {
    return valueIndex
        ? session.nextValueScan(bound.table, scan.relational(), row, indexed)
        : session.nextScan(scan.relational(), row);
  }

  private StatusCode append(
      long primaryKey, HeapRowResult source, int orderColumn) {
    StatusCode status = validate(source, bound.table);
    if (!status.isOk()) return status;
    status = predicates.evaluate(primaryKey, source);
    if (!status.isOk()) return status;
    int root = bound.executableQuery.sourceBlockCount() - 1;
    source = subqueries.evaluatedRow(root, source);
    if (!predicates.matched()) {
      subqueries.releaseRow(root);
      return StatusCode.OK;
    }
    if (bound.projectionPrograms.count() > 0) {
      status = projections.project(primaryKey, source, projected);
      if (!status.isOk()) {
        subqueries.releaseRow(root);
        return status;
      }
    } else status = projectRaw(primaryKey, source);
    if (!status.isOk()) {
      subqueries.releaseRow(root);
      return status;
    }
    status = workspace.append(
        sortKeyHigh(primaryKey, source, orderColumn),
        sortKey(primaryKey, source, orderColumn),
        sortKeyNull(source, orderColumn),
        primaryKey,
        projected.highs(),
        projected.values(),
        projected,
        source,
        projected);
    subqueries.releaseRow(root);
    return status;
  }

  private long sortKey(
      long primaryKey, HeapRowResult source, int orderColumn) {
    return bound.sortKeyProjection >= 0
        ? projected.value(bound.sortKeyProjection)
        : expressions.readColumn(primaryKey, source, bound.table, orderColumn);
  }

  private long sortKeyHigh(
      long primaryKey, HeapRowResult source, int orderColumn) {
    if (bound.sortKeyProjection >= 0) {
      return projected.highValue(bound.sortKeyProjection);
    }
    long value = expressions.readColumn(primaryKey, source, bound.table, orderColumn);
    return SqlTypeDescriptor.isWideDecimal(bound.table.typeDescriptor(orderColumn))
        ? expressions.readColumnHigh(primaryKey, source, bound.table, orderColumn)
        : value >> 63;
  }

  private boolean sortKeyNull(HeapRowResult source, int orderColumn) {
    return bound.sortKeyProjection >= 0
        ? projected.isNull(bound.sortKeyProjection)
        : expressions.isNull(source, bound.table, orderColumn);
  }

  private StatusCode projectRaw(long primaryKey, HeapRowResult source) {
    projected.reset(bound.projectedColumnCount);
    if (!projected.status().isOk()) return projected.status();
    for (int index = 0; index < bound.projectedColumnCount; index++) {
      int projection = bound.projectedColumns[index];
      long value = projection == NULL_PROJECTION
          ? 0 : expressions.readColumn(primaryKey, source, bound.table, projection);
      if (projection == NULL_PROJECTION
          || expressions.isNull(source, bound.table, projection)) {
        projected.setNull(index);
      } else if (SqlTypeDescriptor.isWideDecimal(bound.table.typeDescriptor(projection))) {
        projected.setDecimal128(
            index,
            expressions.readColumnHigh(primaryKey, source, bound.table, projection),
            value);
      } else projected.setValue(index, value);
    }
    return StatusCode.OK;
  }

  private StatusCode finish(StatusCode status) {
    if (subqueries.hasResources()) {
      return status.isOk() ? StatusCode.CONFLICT : status;
    }
    StatusCode close = session.closeScan(scan.relational());
    return finishAfterSource(status, close);
  }

  private StatusCode finishAfterSource(
      StatusCode runtime, StatusCode sourceClose) {
    if (!sourceClose.isOk()) {
      return runtime.isOk() ? sourceClose : runtime;
    }
    if (runtime.isOk()) runtime = workspace.finish();
    if (runtime.isOk()) return StatusCode.OK;
    if (workspace.close().isOk()) clearJoinMode();
    return runtime;
  }

  private void clearJoinMode() {
    joinedRows = false;
    joinInput.clear();
  }

  private int[] projectionTypes(SqlScanCursor cursor) {
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      bound.projectedTypeDescriptors[index] = plan.resultType(index);
    }
    return bound.projectedTypeDescriptors;
  }

  private static StatusCode setProjectedText(
      SqlScanRowResult result,
      HeapRowResult source,
      TableDefinition definition,
      SqlScanCursor cursor,
      SqlNullWords nulls) {
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int column = cursor.projectedColumn(index);
      if (column <= 0
          || !definition.isVarchar(column)
          || nulls.nullAt(index)) {
        continue;
      }
      long handle = source.getLong(definition.valueOffset(column));
      StatusCode status = result.setUtf8At(
          index, source, (int) (handle >>> 32), (int) handle);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private static StatusCode validate(
      HeapRowResult source, TableDefinition definition) {
    return source.length() >= definition.fixedRowBytes()
            && source.length() <= definition.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
