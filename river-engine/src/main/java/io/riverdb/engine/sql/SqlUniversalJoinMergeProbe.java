package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;

/** Reusable merge-run probe state for one materialized universal join role. */
final class SqlUniversalJoinMergeProbe {
  private final SqlBlockRowOrdinalStream.Result storedPosition =
      new SqlBlockRowOrdinalStream.Result();
  private final SqlBlockRow candidate = new SqlBlockRow();
  private final SqlBlockRow priorOuter = new SqlBlockRow();
  private final SqlBlockRowValueComparator comparator = new SqlBlockRowValueComparator();
  private SqlBoundJoinContext context;
  private TableDefinition inner;
  private int innerColumn;
  private int outerRole;
  private int outerColumn;
  private long position;
  private long runStart;
  private long runEnd;
  private long runNext;
  private boolean priorAvailable;
  private boolean runAvailable;

  StatusCode prepare(
      SqlBoundJoinContext joinContext, TableDefinition innerTable,
      int innerKey, int outer, int outerKey) {
    context = joinContext;
    inner = innerTable;
    innerColumn = innerKey;
    outerRole = outer;
    outerColumn = outerKey;
    StatusCode status = candidate.reset(inner.columnCount());
    return status.isOk()
        ? priorOuter.reset(context.table(outerRole).columnCount()) : status;
  }

  StatusCode begin(SqlUniversalJoinRows source, SqlBlockRowStore store) {
    runNext = runEnd;
    SqlBlockRow outer = source.row(outerRole);
    if (outer == null || outer.nullValue(outerColumn)) return StatusCode.OK;
    if (priorAvailable) {
      int compared = compareOuter(priorOuter, outer);
      if (compared > 0) return StatusCode.INVARIANT_BROKEN;
      if (runAvailable && compared == 0) {
        runNext = runStart;
        return StatusCode.OK;
      }
    }
    StatusCode status = priorOuter.copyFrom(outer);
    if (!status.isOk()) return status;
    priorAvailable = true;
    runAvailable = false;
    return seek(store, outer);
  }

  private StatusCode seek(SqlBlockRowStore store, SqlBlockRow outer) {
    while (position < store.rowCount()) {
      StatusCode status = store.readAt(position, candidate);
      if (!status.isOk()) return status;
      if (candidate.nullValue(innerColumn)) {
        position++;
        continue;
      }
      int compared = compare(candidate, innerColumn, inner, outer, outerColumn,
          context.table(outerRole));
      if (compared < 0) {
        position++;
        continue;
      }
      if (compared > 0) return StatusCode.OK;
      return retainRun(store, outer);
    }
    return StatusCode.OK;
  }

  private StatusCode retainRun(SqlBlockRowStore store, SqlBlockRow outer) {
    runStart = position;
    StatusCode status = StatusCode.OK;
    do {
      position++;
      if (position >= store.rowCount()) break;
      status = store.readAt(position, candidate);
      if (!status.isOk()) return status;
    } while (!candidate.nullValue(innerColumn)
        && compare(candidate, innerColumn, inner, outer, outerColumn,
            context.table(outerRole)) == 0);
    runEnd = position;
    runNext = runStart;
    runAvailable = true;
    return status;
  }

  StatusCode next(
      int stage, SqlBlockRowStore store,
      SqlUniversalJoinIdentities identities, SqlUniversalJoinRows target) {
    target.clearCandidate(stage + 1);
    if (runNext >= runEnd) return StatusCode.CONFLICT;
    long sorted = runNext++;
    StatusCode status = store.readAt(sorted, candidate);
    if (status.isOk()) status = store.storedPosition(sorted, storedPosition);
    if (status.isOk()) status = identities.read(storedPosition.value());
    if (!status.isOk()) return status;
    target.borrowCandidate(stage + 1, candidate, identities.identity(), candidate.key());
    return StatusCode.OK;
  }

  private int compareOuter(SqlBlockRow left, SqlBlockRow right) {
    TableDefinition table = context.table(outerRole);
    return compare(left, outerColumn, table, right, outerColumn, table);
  }

  private int compare(
      SqlBlockRow left, int leftColumn, TableDefinition leftTable,
      SqlBlockRow right, int rightColumn, TableDefinition rightTable) {
    return comparator.compare(
        left, leftColumn, leftTable.typeDescriptor(leftColumn),
        right, rightColumn, rightTable.typeDescriptor(rightColumn));
  }

  void reset() {
    candidate.reset(0);
    priorOuter.reset(0);
    context = null;
    inner = null;
    innerColumn = -1;
    outerRole = -1;
    outerColumn = -1;
    position = 0;
    runStart = 0;
    runEnd = 0;
    runNext = 0;
    priorAvailable = false;
    runAvailable = false;
  }
}
