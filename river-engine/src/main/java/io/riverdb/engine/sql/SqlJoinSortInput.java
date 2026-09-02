package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable projected JOIN tuple adapter for the common sort workspace. */
final class SqlJoinSortInput {
  private final BoundSqlStatement bound;
  private final SqlRowProjectionEvaluator projections;
  private final SqlJoinChainSource source;
  private final SqlSortWorkspace workspace;
  private final SqlRetainedArrayAllocator allocator;
  private final SqlBlockRow row;
  private final SqlJoinSortRow encoded;
  private long[] values = new long[0];
  private long[] highs = new long[0];

  SqlJoinSortInput(
      BoundSqlStatement statement,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlJoinChainSource chainSource,
      SqlSortWorkspace sortWorkspace,
      SqlRetainedArrayAllocator retainedAllocator) {
    bound = statement;
    projections = projectionEvaluator;
    source = chainSource;
    workspace = sortWorkspace;
    allocator = retainedAllocator;
    row = new SqlBlockRow(allocator);
    encoded = new SqlJoinSortRow(allocator);
  }

  StatusCode begin() {
    int columns = bound.projectedColumnCount;
    int capacity = BoundedArrayGrowth.capacity(
        values.length, columns, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long[] nextValues;
    long[] nextHighs;
    try {
      nextValues = capacity == values.length ? values : allocator.longs(capacity);
      nextHighs = capacity == highs.length ? highs : allocator.longs(capacity);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = row.reset(columns);
    if (!status.isOk()) return status;
    status = encoded.prepare();
    if (!status.isOk()) return status;
    for (int column = 0; column < columns; column++) {
      if (isText(column)) {
        status = row.prepareText(column);
        if (!status.isOk()) return status;
      }
    }
    int keyColumn = bound.sortKeyProjection;
    status = workspace.begin(
        bound.table,
        bound.command.isDescendingOrder(),
        columns,
        containsText(),
        false,
        isText(keyColumn),
        bound.projectedTypeDescriptors[keyColumn]);
    if (status.isOk()) {
      values = nextValues;
      highs = nextHighs;
    }
    return status;
  }

  StatusCode append() {
    int columns = bound.projectedColumnCount;
    StatusCode status = projections.projectJoin(source.rows(), row);
    if (status.isOk()) {
      status = encoded.encode(row, bound.projectedTypeDescriptors, columns);
    }
    if (!status.isOk()) return status;
    int keyColumn = bound.sortKeyProjection;
    long key = isText(keyColumn)
        ? encoded.row().getLong(keyColumn * Long.BYTES)
        : row.value(keyColumn);
    for (int column = 0; column < columns; column++) {
      highs[column] = row.highValue(column);
      values[column] = row.value(column);
    }
    return workspace.append(
        row.highValue(keyColumn),
        key,
        row.nullValue(keyColumn),
        source.rows().key(0),
        highs,
        values,
        row,
        encoded.row(),
        null);
  }

  StatusCode setText(
      SqlScanRowResult result,
      HeapRowResult sourceRow,
      SqlScanCursor cursor,
      SqlNullWords nulls) {
    for (int column = 0; column < cursor.projectedColumnCount(); column++) {
      if (!isText(column) || nulls.nullAt(column)) continue;
      long handle = sourceRow.getLong(column * Long.BYTES);
      StatusCode status = result.setUtf8At(
          column, sourceRow, (int) (handle >>> 32), (int) handle);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  void clear() { row.reset(0); }

  private boolean containsText() {
    for (int column = 0; column < bound.projectedColumnCount; column++) {
      if (isText(column)) return true;
    }
    return false;
  }

  private boolean isText(int column) {
    return column >= 0 && column < bound.projectedColumnCount
        && SqlTypeDescriptor.typeId(bound.projectedTypeDescriptors[column])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
}
