package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;

/** Statement-owned replay of a serializable join root in dependent primary-key order. */
final class SqlUniversalJoinOrderedRoot {
  private final int[] keySourceColumns =
      new int[io.riverdb.engine.schema.KeyDescriptor.MAXIMUM_PARTS];
  private final SqlKeyOrderedLookupRootShape shape;
  private final SqlBlockRow stored;
  private final SqlBlockRow candidate;
  private final SqlBlockRowStore store;
  private boolean active;

  SqlUniversalJoinOrderedRoot(SqlSessionShapeBudget shapeBudget) {
    shape = new SqlKeyOrderedLookupRootShape(shapeBudget);
    stored = new SqlBlockRow(shapeBudget);
    candidate = new SqlBlockRow(shapeBudget);
    store = new SqlBlockRowStore(shapeBudget);
  }

  StatusCode begin(
      SqlCommand command, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where, SqlUniversalJoinRows rows,
      int projectedInnerColumn) {
    StatusCode status = close();
    if (!status.isOk() || !admits(command, context, rows, projectedInnerColumn)) {
      return status;
    }
    int keyCount = rows.exactUniqueOuterColumns(
        1, 0, projectedInnerColumn, keySourceColumns);
    if (keyCount <= 0) return StatusCode.OK;
    status = shape.prepare(
        context.table(0), context.onBoolean(0), where, keySourceColumns, keyCount);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (status.isOk()) status = stored.reset(shape.storedColumnCount());
    if (status.isOk()) status = candidate.reset(shape.rootColumnCount());
    if (status.isOk()) status = store.begin(
        shape.schema(), shape.sortColumns(), shape.descending(), shape.keyCount());
    if (!status.isOk()) return fail(status);

    status = rows.open(0);
    boolean opened = status.isOk();
    while (status.isOk()) {
      status = rows.next(0);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = append(rows);
    }
    if (opened) {
      StatusCode closed = rows.closeScan(0);
      if (!closed.isOk()) status = closed;
    }
    if (status.isOk()) status = store.finish();
    if (!status.isOk()) return fail(status);
    active = true;
    return StatusCode.OK;
  }

  StatusCode next(SqlUniversalJoinRows rows) {
    rows.clearCandidate(0);
    if (!active) return StatusCode.CONFLICT;
    StatusCode status = store.next(stored);
    if (!status.isOk()) return status;
    status = candidate.reset(shape.rootColumnCount());
    for (int column = 0;
        status.isOk() && column < shape.rootColumnCount(); column++) {
      candidate.setNull(column);
    }
    for (int lane = 0; status.isOk() && lane < shape.publicKeyColumn(); lane++) {
      int sourceColumn = shape.sourceColumn(lane);
      status = copy(stored, lane, candidate, sourceColumn, sourceColumn);
    }
    if (!status.isOk()) return status;
    long publicKey = stored.value(shape.publicKeyColumn());
    candidate.setKey(publicKey);
    rows.borrowCandidate(0, candidate, stored.key(), publicKey);
    return StatusCode.OK;
  }

  boolean active() { return active; }
  boolean hasResources() { return active || store.hasResources(); }

  StatusCode close() {
    StatusCode status = store.close();
    if (status.isOk()) {
      stored.reset(0);
      candidate.reset(0);
      shape.reset();
      active = false;
    }
    return status;
  }

  private StatusCode append(SqlUniversalJoinRows rows) {
    SqlBlockRow source = rows.row(0);
    if (source == null || source.count() != shape.rootColumnCount()) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = stored.reset(shape.storedColumnCount());
    for (int lane = 0; status.isOk() && lane < shape.publicKeyColumn(); lane++) {
      int sourceColumn = shape.sourceColumn(lane);
      status = copy(source, sourceColumn, stored, lane, sourceColumn);
    }
    if (!status.isOk()) return status;
    stored.setValue(shape.publicKeyColumn(), rows.publicKey(0));
    stored.setKey(rows.key(0));
    return store.append(stored);
  }

  private StatusCode copy(
      SqlBlockRow source, int sourceColumn,
      SqlBlockRow target, int targetColumn, int rootColumn) {
    if (source.nullValue(sourceColumn)) {
      target.setNull(targetColumn);
      return StatusCode.OK;
    }
    target.clearValue(targetColumn);
    int descriptor = shape.rootTypeDescriptor(rootColumn);
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return target.setText(
          targetColumn, source.text(sourceColumn), 0, source.textLength(sourceColumn));
    }
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      target.setDecimal128(
          targetColumn, source.highValue(sourceColumn), source.value(sourceColumn));
    } else {
      target.setValue(targetColumn, source.value(sourceColumn));
    }
    return StatusCode.OK;
  }

  private StatusCode fail(StatusCode failure) {
    StatusCode cleanup = close();
    return cleanup.isOk() ? failure : cleanup;
  }

  private static boolean admits(
      SqlCommand command, SqlBoundJoinContext context, SqlUniversalJoinRows rows,
      int projectedInnerColumn) {
    return command != null && command.joinChain() != null
        && command.joinChain().stageCount() == 1
        && context != null && context.strategy(0) == SqlJoinStrategy.NESTED_LOOP
        && rows.serializable() && projectedInnerColumn >= 0;
  }
}
