package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.sql.SqlCommand;
import io.riverdb.storage.heap.HeapRowResult;

/** Resumable left-deep executor for one bounded canonical JOIN chain. */
final class SqlJoinChainSource {
  private final SqlExpressionEvaluator expressions;
  private final SqlJoinChainCursors cursors;
  private final SqlJoinStageCandidates physical;
  private final SqlJoinRoleRows rows;
  private SqlBoundJoinContext context;
  private SqlCommand command;
  private SqlJoinPredicateCallback predicates;
  private final boolean[] opened =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] matched =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] finished =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] nullEmitted =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] uniqueTried =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] candidates =
      new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] onTrue =
      new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] nullExtensions =
      new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private long rootCandidates;
  private long whereTrue;
  private int stage = -1;
  private boolean beginAccepted;

  SqlJoinChainSource(
      io.riverdb.engine.relational.RelationalSession session,
      SqlExpressionEvaluator evaluator) {
    expressions = evaluator;
    cursors = new SqlJoinChainCursors(session);
    physical = new SqlJoinStageCandidates(session);
    rows = new SqlJoinRoleRows();
  }

  StatusCode configure(
      SqlBoundJoinContext joinContext,
      SqlCommand canonicalCommand,
      SqlBoundBooleanPredicateProgram where,
      SqlJoinPredicateCallback predicateCallback) {
    if (hasResources() || joinContext == null || canonicalCommand == null
        || predicateCallback == null) {
      return StatusCode.CONFLICT;
    }
    context = joinContext;
    command = canonicalCommand;
    predicates = predicateCallback;
    cursors.configure(context, command);
    physical.configure(context, command);
    rows.configure(context);
    return predicates.configureJoin(command, context, where);
  }

  StatusCode begin() {
    if (context == null || command == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    resetProgress();
    resetMetrics();
    StatusCode status = physical.begin();
    return status.isOk() ? cursors.beginRoot() : status;
  }

  void resetMetrics() { resetCounters(); }

  StatusCode next() {
    while (true) {
      if (stage < 0) {
        StatusCode status = nextRoot();
        if (!status.isOk()) return status;
      }
      StatusCode status = nextStage();
      if (status != StatusCode.CONFLICT) return status;
    }
  }

  private StatusCode nextRoot() {
    StatusCode status = cursors.nextRoot();
    if (!status.isOk()) return status;
    rootCandidates++;
    rows.clearFrom(0);
    rows.borrow(0, cursors.key(0), cursors.row(0));
    resetStages();
    stage = 0;
    return StatusCode.OK;
  }

  private StatusCode nextStage() {
    if (finished[stage]) return finishStage();
    if (!opened[stage] && !uniqueTried[stage]) {
      StatusCode status = beginStage();
      if (!status.isOk()) return status;
      if (beginAccepted) {
        beginAccepted = false;
        return StatusCode.OK;
      }
      if (finished[stage]) return finishStage();
    }
    if (uniqueTried[stage]) {
      finished[stage] = true;
      return finishStage();
    }
    return nextScannedCandidate();
  }

  private StatusCode beginStage() {
    StatusCode status = physical.beginStage(stage, cursors, rows, expressions);
    if (!status.isOk()) return status;
    opened[stage] = physical.opened();
    finished[stage] = physical.finished();
    uniqueTried[stage] = physical.unique();
    if (!physical.candidate()) return StatusCode.OK;
    candidates[stage]++;
    status = acceptCandidate();
    if (status.isOk()) beginAccepted = true;
    return status;
  }

  private StatusCode nextScannedCandidate() {
    StatusCode status = physical.nextCandidate(stage, cursors, rows);
    if (status == StatusCode.CONFLICT) {
      opened[stage] = false;
      finished[stage] = true;
      return finishStage();
    }
    if (!status.isOk()) return status;
    candidates[stage]++;
    if (!physical.matchesAccess(stage, cursors, rows, expressions)) {
      return StatusCode.CONFLICT;
    }
    return acceptCandidate();
  }

  private StatusCode acceptCandidate() {
    if (!predicates.matchesJoinOn(stage, rows)) {
      return predicates.joinStatus().isOk()
          ? StatusCode.CONFLICT : predicates.joinStatus();
    }
    matched[stage] = true;
    onTrue[stage]++;
    if (stage + 1 == command.joinChain().stageCount()) {
      boolean where = predicates.matchesJoinWhere(rows);
      if (!predicates.joinStatus().isOk()) return predicates.joinStatus();
      if (where) whereTrue++;
      return where ? StatusCode.OK : StatusCode.CONFLICT;
    }
    StatusCode status = rows.ownThrough(stage + 1);
    if (!status.isOk()) return status;
    stage++;
    resetStage(stage);
    return StatusCode.CONFLICT;
  }

  private StatusCode finishStage() {
    SqlJoinChain joins = command.joinChain();
    int rightRole = stage + 1;
    if (!matched[stage] && joins.isLeft(stage) && !nullEmitted[stage]) {
      nullEmitted[stage] = true;
      nullExtensions[stage]++;
      rows.setNull(rightRole);
      if (stage + 1 == joins.stageCount()) {
        boolean where = predicates.matchesJoinWhere(rows);
        if (!predicates.joinStatus().isOk()) return predicates.joinStatus();
        if (where) whereTrue++;
        return where ? StatusCode.OK : StatusCode.CONFLICT;
      }
      stage++;
      resetStage(stage);
      return StatusCode.CONFLICT;
    }
    rows.clearFrom(rightRole);
    resetStage(stage);
    stage--;
    return StatusCode.CONFLICT;
  }

  private void resetStages() {
    for (int current = 0; current < opened.length; current++) resetStage(current);
  }

  private void resetStage(int current) {
    opened[current] = false;
    matched[current] = false;
    finished[current] = false;
    nullEmitted[current] = false;
    uniqueTried[current] = false;
  }

  SqlJoinRoleRows rows() { return rows; }
  boolean innerIndexed() { return cursors.rightIndexed(0); }
  boolean innerUnique() { return cursors.rightUnique(0); }
  boolean outerValueIndex() { return cursors.rootValueIndex(); }
  long rootCandidates() { return rootCandidates; }
  long stageAccessRows(int current) {
    return candidates[current];
  }
  long stageOnTrue(int current) { return onTrue[current]; }
  long stageNullExtensions(int current) { return nullExtensions[current]; }
  long stagePublished(int current) {
    return onTrue[current] + nullExtensions[current];
  }
  long whereTrue() { return whereTrue; }
  boolean stageFallback(int current) {
    return physical.fallback(current);
  }
  boolean hasResources() {
    return physical.hasResources() || cursors.hasResources();
  }

  StatusCode close() {
    StatusCode status = physical.close();
    if (!status.isOk()) return status;
    status = cursors.closeAll();
    if (!status.isOk()) return status;
    rows.reset();
    resetProgress();
    return StatusCode.OK;
  }

  private void resetProgress() {
    stage = -1;
    beginAccepted = false;
    resetStages();
  }

  private void resetCounters() {
    rootCandidates = 0;
    whereTrue = 0;
    for (int current = 0; current < candidates.length; current++) {
      candidates[current] = 0;
      onTrue[current] = 0;
      nullExtensions[current] = 0;
    }
    physical.resetMetrics();
  }
}
