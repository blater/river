package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.storage.heap.HeapRowResult;

/** Physical candidate dispatcher with one active subordinate strategy workspace. */
final class SqlJoinStageCandidates {
  private static final int OPENED = 1;
  private static final int FINISHED = 2;
  private static final int CANDIDATE = 3;
  private final RelationalSession session;
  private final SqlJoinStrategyCandidates strategies;
  private SqlBoundJoinContext context;
  private int outcome;
  private boolean unique;

  SqlJoinStageCandidates(RelationalSession relationalSession) {
    session = relationalSession;
    strategies = new SqlJoinStrategyCandidates(relationalSession);
  }

  void configure(SqlBoundJoinContext joinContext, SqlCommand command) {
    context = joinContext;
    strategies.configure(joinContext, command);
  }

  StatusCode begin() {
    return strategies.begin();
  }

  boolean handles(int current) { return strategies.handles(current); }

  StatusCode beginStage(
      int current,
      SqlJoinChainCursors cursors,
      SqlJoinRoleRows rows,
      SqlExpressionEvaluator expressions) {
    outcome = 0;
    unique = false;
    if (handles(current)) {
      StatusCode status = rows.ownThrough(current);
      if (status.isOk()) status = strategies.beginProbe(rows, expressions);
      if (status.isOk()) outcome = OPENED;
      return status;
    }
    int rightRole = current + 1;
    int outerRole = context.accessOuterRole(current);
    int outerColumn = context.accessOuterColumn(current);
    if (outerColumn < 0) return beginTable(current, rightRole, cursors, rows);
    HeapRowResult outerRow = rows.row(outerRole);
    if (outerRow == null
        || expressions.isNull(outerRow, context.table(outerRole), outerColumn)) {
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
        ? strategies.nextCandidate()
        : cursors.nextRole(rightRole);
    if (status == StatusCode.CONFLICT && !handles(current)) {
      StatusCode closed = cursors.closeRole(rightRole);
      return closed.isOk() ? StatusCode.CONFLICT : closed;
    }
    if (!status.isOk()) return status;
    rows.borrow(
        rightRole,
        handles(current) ? strategies.key() : cursors.key(rightRole),
        handles(current) ? strategies.row() : cursors.row(rightRole));
    return StatusCode.OK;
  }

  boolean matchesAccess(
      int current,
      SqlJoinChainCursors cursors,
      SqlJoinRoleRows rows,
      SqlExpressionEvaluator expressions) {
    int innerColumn = context.accessInnerColumn(current);
    if (innerColumn < 0 || cursors.rightIndexed(current)) return true;
    int rightRole = current + 1;
    HeapRowResult right = rows.row(rightRole);
    TableDefinition rightTable = context.table(rightRole);
    if (expressions.isNull(right, rightTable, innerColumn)) return false;
    int outerRole = context.accessOuterRole(current);
    int outerColumn = context.accessOuterColumn(current);
    long leftValue = expressions.readColumn(
        rows.key(outerRole), rows.row(outerRole), outerColumn);
    long rightValue = expressions.readColumn(
        rows.key(rightRole), right, innerColumn);
    return expressions.compareExact(
        rightValue,
        rightTable.typeDescriptor(innerColumn),
        leftValue,
        context.table(outerRole).typeDescriptor(outerColumn)) == 0;
  }

  boolean fallback(int current) { return strategies.fallback(current); }
  boolean hasResources() { return strategies.hasResources(); }

  StatusCode close() { return strategies.close(); }

  void resetMetrics() {
    strategies.resetMetrics();
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
