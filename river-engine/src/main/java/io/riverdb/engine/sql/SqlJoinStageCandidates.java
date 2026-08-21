package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Physical candidate dispatcher with one subordinate lazy HASH workspace. */
final class SqlJoinStageCandidates {
  private static final int OPENED = 1;
  private static final int FINISHED = 2;
  private static final int CANDIDATE = 3;
  private final BoundSqlStatement bound;
  private final RelationalSession session;
  private SqlJoinHashWorkspace workspace;
  private int stage = -1;
  private int outcome;
  private boolean unique;
  private boolean fallback;

  SqlJoinStageCandidates(
      RelationalSession relationalSession, BoundSqlStatement statement) {
    session = relationalSession;
    bound = statement;
  }

  StatusCode begin() {
    resetMetrics();
    stage = SqlJoinHashWorkspace.selectedStage(bound);
    if (stage < 0) return StatusCode.OK;
    if (workspace == null) workspace = new SqlJoinHashWorkspace(session);
    StatusCode status = workspace.begin(bound);
    if (status.isOk()) {
      fallback = workspace.fallback();
    }
    return status;
  }

  boolean handles(int current) { return current == stage; }

  StatusCode beginStage(
      int current,
      SqlJoinChainCursors cursors,
      SqlJoinRoleRows rows,
      SqlExpressionEvaluator expressions) {
    outcome = 0;
    unique = false;
    if (handles(current)) {
      StatusCode status = rows.ownThrough(current);
      if (status.isOk()) status = workspace.beginProbe(rows, bound);
      if (status.isOk()) outcome = OPENED;
      return status;
    }
    int rightRole = current + 1;
    int outerRole = bound.joinAccessOuterRole(current);
    int outerColumn = bound.joinAccessOuterColumn(current);
    if (outerColumn < 0) return beginTable(current, rightRole, cursors, rows);
    HeapRowResult outerRow = rows.row(outerRole);
    if (outerRow == null
        || expressions.isNull(outerRow, bound.joinRole(outerRole), outerColumn)) {
      outcome = FINISHED;
      return StatusCode.OK;
    }
    long value = expressions.readColumn(rows.key(outerRole), outerRow, outerColumn);
    StatusCode status = rows.ownThrough(current);
    if (!status.isOk()) return status;
    if (cursors.rightUnique(current)) {
      unique = true;
      status = cursors.fetchRole(rightRole, value);
      if (status == StatusCode.CONFLICT) {
        outcome = FINISHED;
        return StatusCode.OK;
      }
      if (status.isOk()) {
        rows.borrow(rightRole, cursors.key(rightRole), cursors.row(rightRole));
        outcome = CANDIDATE;
      }
      return status;
    }
    status = cursors.beginRole(rightRole, value);
    if (status == StatusCode.CONFLICT && cursors.rightIndexed(current)) {
      outcome = FINISHED;
      return StatusCode.OK;
    }
    if (status.isOk()) outcome = OPENED;
    return status;
  }

  boolean opened() { return outcome == OPENED; }
  boolean finished() { return outcome == FINISHED; }
  boolean candidate() { return outcome == CANDIDATE; }
  boolean unique() { return unique; }

  StatusCode nextCandidate(
      int current, SqlJoinChainCursors cursors, SqlJoinRoleRows rows) {
    int rightRole = current + 1;
    StatusCode status = handles(current)
        ? workspace.nextCandidate() : cursors.nextRole(rightRole);
    if (status == StatusCode.CONFLICT && !handles(current)) {
      StatusCode closed = cursors.closeRole(rightRole);
      return closed.isOk() ? StatusCode.CONFLICT : closed;
    }
    if (!status.isOk()) return status;
    rows.borrow(
        rightRole,
        handles(current) ? workspace.key() : cursors.key(rightRole),
        handles(current) ? workspace.row() : cursors.row(rightRole));
    return StatusCode.OK;
  }

  boolean matchesAccess(
      int current,
      SqlJoinChainCursors cursors,
      SqlJoinRoleRows rows,
      SqlExpressionEvaluator expressions) {
    int innerColumn = bound.joinAccessInnerColumn(current);
    if (innerColumn < 0 || cursors.rightIndexed(current)) return true;
    int rightRole = current + 1;
    HeapRowResult right = rows.row(rightRole);
    TableDefinition rightTable = bound.joinRole(rightRole);
    if (expressions.isNull(right, rightTable, innerColumn)) return false;
    int outerRole = bound.joinAccessOuterRole(current);
    int outerColumn = bound.joinAccessOuterColumn(current);
    long leftValue = expressions.readColumn(
        rows.key(outerRole), rows.row(outerRole), outerColumn);
    long rightValue = expressions.readColumn(
        rows.key(rightRole), right, innerColumn);
    return expressions.compareExact(
        rightValue,
        rightTable.typeDescriptor(innerColumn),
        leftValue,
        bound.joinRole(outerRole).typeDescriptor(outerColumn)) == 0;
  }

  boolean fallback(int current) { return current == stage && fallback; }
  boolean hasResources() { return workspace != null && workspace.hasResources(); }

  StatusCode close() {
    return workspace == null ? StatusCode.OK : workspace.close();
  }

  void resetMetrics() {
    stage = -1;
    fallback = false;
  }

  private StatusCode beginTable(
      int current,
      int rightRole,
      SqlJoinChainCursors cursors,
      SqlJoinRoleRows rows) {
    StatusCode status = rows.ownThrough(current);
    if (status.isOk()) status = cursors.beginRole(rightRole, 0);
    if (status.isOk()) outcome = OPENED;
    return status;
  }
}
