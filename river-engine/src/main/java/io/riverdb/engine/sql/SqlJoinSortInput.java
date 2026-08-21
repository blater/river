package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable projected JOIN tuple adapter for the common sort workspace. */
final class SqlJoinSortInput {
  private final BoundSqlStatement bound;
  private final SqlRowProjectionEvaluator projections;
  private final SqlJoinChainSource source;
  private final SqlSortWorkspace workspace;
  private final SqlBlockRow row = new SqlBlockRow();
  private final SqlJoinSortRow encoded = new SqlJoinSortRow();
  private final long[] values = new long[TableSchema.MAXIMUM_COLUMNS];

  SqlJoinSortInput(
      BoundSqlStatement statement,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlJoinChainSource chainSource,
      SqlSortWorkspace sortWorkspace) {
    bound = statement;
    projections = projectionEvaluator;
    source = chainSource;
    workspace = sortWorkspace;
  }

  StatusCode begin() {
    int columns = bound.projectedColumnCount;
    encoded.prepare();
    for (int column = 0; column < columns; column++) {
      if (isText(column)) row.text(column);
    }
    int keyColumn = bound.sortKeyProjection;
    return workspace.begin(
        bound.table,
        bound.command.isDescendingOrder(),
        columns,
        containsText(),
        false,
        isText(keyColumn));
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
      values[column] = row.value(column);
    }
    return workspace.append(
        key,
        row.nullValue(keyColumn),
        source.rows().key(0),
        values,
        row.nullMask(),
        encoded.row(),
        null);
  }

  StatusCode setText(
      SqlScanRowResult result,
      HeapRowResult sourceRow,
      SqlScanCursor cursor,
      long nullMask) {
    for (int column = 0; column < cursor.projectedColumnCount(); column++) {
      if (!isText(column) || (nullMask & 1L << column) != 0) continue;
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
