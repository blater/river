package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
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
  private final SqlNestedQueryExecution nested;
  private final SqlBoundPredicateEvaluator predicates;
  private final SqlSortWorkspace workspace = new SqlSortWorkspace();
  private final RelationalScanResult row = new RelationalScanResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final long[] values = new long[TableSchema.MAXIMUM_COLUMNS];
  private long outputNullMask;

  SqlSortExecution(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlPhysicalPlan physicalPlan,
      SqlActiveScanState activeScan,
      SqlExpressionEvaluator evaluator,
      SqlNestedQueryExecution nestedExecution,
      SqlBoundPredicateEvaluator predicateEvaluator) {
    session = relationalSession;
    bound = statement;
    plan = physicalPlan;
    scan = activeScan;
    expressions = evaluator;
    nested = nestedExecution;
    predicates = predicateEvaluator;
  }

  StatusCode materialize(boolean valueIndex, int orderColumn) {
    BoundSqlQuery.Block command = bound.executableQuery.root();
    StatusCode status = workspace.begin(
        bound.table,
        command.isDescendingOrder(),
        orderColumn,
        bound.projectedColumnCount,
        containsText(orderColumn));
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
      status = setProjectedText(
          result,
          workspace.isSpilled() ? workspace.spilledRow() : workspace.rowAt(sortedRow),
          bound.table,
          cursor,
          outputNullMask);
    }
    if (status.isOk()) {
      scan.advanceSortedRow();
      cursor.rowReturned();
    }
    return status;
  }

  StatusCode nextGroupValue(long[] destination) {
    int sortedRow = scan.currentSortedRow();
    if (sortedRow < 0) {
      return StatusCode.CONFLICT;
    }
    StatusCode status;
    if (workspace.isSpilled()) {
      status = workspace.nextSpilled(2, destination);
      outputNullMask = workspace.outputNullMask();
    } else {
      workspace.copyValuesAt(sortedRow, 2, destination);
      outputNullMask = workspace.nullMaskAt(sortedRow);
      status = StatusCode.OK;
    }
    if (status.isOk()) {
      scan.advanceSortedRow();
    }
    return status;
  }

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
    return workspace.close();
  }

  private boolean containsText(int orderColumn) {
    if (bound.table.isVarchar(orderColumn)) {
      return true;
    }
    for (int index = 0; index < bound.projectedColumnCount; index++) {
      int projection = bound.projectedColumns[index];
      if (projection > 0 && bound.table.isVarchar(projection)) {
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
    if (status.isOk()) {
      status = nested.evaluateBeforePredicates(primaryKey, source);
      source = nested.evaluatedRow(source);
    }
    if (!status.isOk() || nested.rejectsOuterRow()) {
      return status;
    }
    if (!predicates.matches(primaryKey, source)) {
      return StatusCode.OK;
    }
    status = nested.evaluateAfterPredicates(primaryKey, source);
    source = nested.evaluatedRow(source);
    if (!status.isOk() || nested.rejectsOuterRow()) {
      return status;
    }
    long nullMask = 0;
    for (int index = 0; index < bound.projectedColumnCount; index++) {
      int projection = bound.projectedColumns[index];
      values[index] = projection == NULL_PROJECTION
          ? 0 : expressions.readColumn(primaryKey, source, projection);
      if (projection == NULL_PROJECTION
          || expressions.isNull(source, bound.table, projection)) {
        nullMask |= 1L << index;
      }
    }
    return workspace.append(
        expressions.readColumn(primaryKey, source, orderColumn),
        expressions.isNull(source, bound.table, orderColumn),
        primaryKey,
        values,
        nullMask,
        source);
  }

  private StatusCode finish(StatusCode status) {
    StatusCode close = session.closeScan(scan.relational());
    if (!close.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      status = workspace.finish();
    }
    if (status.isOk()) {
      return StatusCode.OK;
    }
    StatusCode cleanup = workspace.close();
    return cleanup.isOk() ? status : cleanup;
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
