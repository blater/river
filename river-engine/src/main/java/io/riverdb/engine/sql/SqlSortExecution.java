package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
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
  private final SqlProjectedRow projected = new SqlProjectedRow();
  private final SqlJoinChainSource joinSource;
  private final SqlSortWorkspace workspace = new SqlSortWorkspace();
  private final SqlJoinSortInput joinInput;
  private final RelationalScanResult row = new RelationalScanResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final long[] values = new long[TableSchema.MAXIMUM_COLUMNS];
  private long outputNullMask;
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
      SqlJoinChainSource chainSource) {
    session = relationalSession;
    bound = statement;
    plan = physicalPlan;
    scan = activeScan;
    expressions = evaluator;
    subqueries = graph;
    predicates = predicateEvaluator;
    projections = projectionEvaluator;
    joinSource = chainSource;
    joinInput = new SqlJoinSortInput(
        statement, projectionEvaluator, chainSource, workspace);
  }

  StatusCode materializeJoin() {
    StatusCode status = joinInput.begin();
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
    StatusCode closed = joinSource.close();
    return finishAfterSource(status, closed);
  }

  StatusCode materialize(boolean valueIndex, int orderColumn) {
    joinedRows = false;
    BoundSqlQuery.Block command = bound.executableQuery.root();
    boolean textKey = bound.sortKeyProjection < 0
        && bound.table.isVarchar(orderColumn);
    int storedProjections = SqlBinder.isGroupAggregate(command.type())
        ? bound.projectionPrograms.count() : bound.projectedColumnCount;
    StatusCode status = workspace.begin(
        bound.table,
        command.isDescendingOrder(),
        storedProjections,
        containsText(textKey),
        hasGeneratedText(),
        textKey);
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

  StatusCode next(SqlScanCursor cursor, SqlScanRowResult result) {
    int sortedRow = scan.currentSortedRow();
    if (sortedRow < 0) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = StatusCode.OK;
    long primaryKey;
    if (workspace.isSpilled()) {
      status = workspace.nextSpilled(cursor.projectedColumnCount(), values);
      primaryKey = workspace.outputPrimaryKey();
      outputNullMask = workspace.outputNullMask();
    } else {
      workspace.copyValuesAt(sortedRow, cursor.projectedColumnCount(), values);
      primaryKey = workspace.primaryKeyAt(sortedRow);
      outputNullMask = workspace.nullMaskAt(sortedRow);
    }
    if (!status.isOk()) {
      return status;
    }
    result.set(
        primaryKey,
        values,
        outputNullMask,
        projectionTypes(cursor),
        cursor.projectedColumnCount());
    if (workspace.containsText()) {
      HeapRowResult source = workspace.isSpilled()
          ? workspace.spilledRow() : workspace.rowAt(sortedRow);
      status = joinedRows
          ? joinInput.setText(result, source, cursor, outputNullMask)
          : setProjectedText(
              result, source, bound.table, cursor, outputNullMask);
    }
    if (status.isOk()) status = workspace.setGeneratedText(result, sortedRow);
    if (!status.isOk()) result.reset();
    if (status.isOk()) {
      scan.advanceSortedRow();
      cursor.rowReturned();
    }
    return status;
  }

  StatusCode nextGroupValue(long[] destination, SqlProjectedRow projected) {
    int sortedRow = scan.currentSortedRow();
    if (sortedRow < 0) {
      return StatusCode.CONFLICT;
    }
    StatusCode status;
    int count = bound.projectionPrograms.count();
    if (workspace.isSpilled()) {
      status = workspace.nextSpilled(count, destination);
      outputNullMask = workspace.outputNullMask();
      groupSource = workspace.spilledRow();
    } else {
      workspace.copyValuesAt(sortedRow, count, destination);
      outputNullMask = workspace.nullMaskAt(sortedRow);
      groupSource = workspace.rowAt(sortedRow);
      status = StatusCode.OK;
    }
    if (status.isOk()) {
      workspace.copyGeneratedText(projected, sortedRow);
      scan.advanceSortedRow();
    }
    return status;
  }

  HeapRowResult groupSource() { return groupSource; }

  long outputNullMask() {
    return outputNullMask;
  }

  int totalRows() {
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
      if (projection > 0 && bound.table.isVarchar(projection)) {
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
    } else {
      projectRaw(primaryKey, source);
    }
    status = workspace.append(
        sortKey(primaryKey, source, orderColumn),
        sortKeyNull(source, orderColumn),
        primaryKey,
        bound.projectionPrograms.count() > 0 ? projected.values() : values,
        bound.projectionPrograms.count() > 0 ? projected.nullMask() : outputNullMask,
        source,
        projected);
    subqueries.releaseRow(root);
    return status;
  }

  private long sortKey(
      long primaryKey, HeapRowResult source, int orderColumn) {
    return bound.sortKeyProjection >= 0
        ? projected.value(bound.sortKeyProjection)
        : expressions.readColumn(primaryKey, source, orderColumn);
  }

  private boolean sortKeyNull(HeapRowResult source, int orderColumn) {
    return bound.sortKeyProjection >= 0
        ? (projected.nullMask() & 1L << bound.sortKeyProjection) != 0
        : expressions.isNull(source, bound.table, orderColumn);
  }

  private void projectRaw(long primaryKey, HeapRowResult source) {
    outputNullMask = 0;
    projected.reset(bound.projectedColumnCount);
    for (int index = 0; index < bound.projectedColumnCount; index++) {
      int projection = bound.projectedColumns[index];
      values[index] = projection == NULL_PROJECTION
          ? 0 : expressions.readColumn(primaryKey, source, projection);
      if (projection == NULL_PROJECTION
          || expressions.isNull(source, bound.table, projection)) {
        outputNullMask |= 1L << index;
      }
    }
  }

  private StatusCode finish(StatusCode status) {
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

  private static StatusCode validate(
      HeapRowResult source, TableDefinition definition) {
    return source.length() >= definition.fixedRowBytes()
            && source.length() <= definition.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
