package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;

/** Resumable left-deep nested-loop fallback over descriptor and legacy role scans. */
final class SqlUniversalJoinSource {
  private final boolean[] opened = new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] matched = new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] nullEmitted = new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] finished = new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final boolean[] runtimeFallback =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final SqlUniversalJoinAccess access = new SqlUniversalJoinAccess();
  private final SqlUniversalJoinMetrics metrics = new SqlUniversalJoinMetrics();
  private final SqlUniversalJoinHash hash;
  private final SqlUniversalJoinMerge merge;
  private final SqlUniversalJoinOrderedRoot orderedRoot;
  private SqlUniversalJoinRows rows;
  private SqlUniversalJoinPredicates predicates;
  private SqlBoundJoinContext context;
  private SqlBoundBooleanPredicateProgram where;
  private SqlCommand command;
  private int orderedInnerColumn = -1;
  private int stage = -1;

  SqlUniversalJoinSource(RelationalSession session, SqlSessionShapeBudget budget) {
    hash = new SqlUniversalJoinHash(session, budget);
    merge = new SqlUniversalJoinMerge(budget);
    orderedRoot = new SqlUniversalJoinOrderedRoot(budget);
  }

  void configure(
      SqlCommand source,
      SqlBoundJoinContext joinContext,
      SqlBoundBooleanPredicateProgram whereProgram,
      SqlUniversalJoinRows joinRows,
      SqlUniversalJoinPredicates predicateEvaluator,
      int canonicalInnerColumn) {
    command = source;
    context = joinContext;
    access.configure(joinContext);
    where = whereProgram;
    rows = joinRows;
    predicates = predicateEvaluator;
    orderedInnerColumn = canonicalInnerColumn;
    resetProgress();
    resetFallback();
  }

  StatusCode begin() {
    resetProgress();
    metrics.reset();
    resetFallback();
    if (command.rowLimit() == 0) return StatusCode.OK;
    StatusCode status = hash.begin(command, context, rows);
    if (status.isOk()) status = merge.begin(command, context, rows);
    if (status.isOk() && hash.stage() < 0) {
      status = orderedRoot.begin(
          command, context, where, rows, orderedInnerColumn);
    }
    if (status.isOk() && hash.stage() >= 0) {
      runtimeFallback[hash.stage()] = hash.fallback(hash.stage());
    }
    return !status.isOk() || orderedRoot.active() ? status : rows.open(0);
  }

  StatusCode next() {
    while (true) {
      if (stage < 0) {
        StatusCode status = orderedRoot.active()
            ? orderedRoot.next(rows) : rows.next(0);
        if (!status.isOk()) return status;
        metrics.root();
        resetStages();
        stage = 0;
      }
      StatusCode status = nextStage();
      if (status != StatusCode.CONFLICT) return status;
    }
  }

  private StatusCode nextStage() {
    if (finished[stage]) return completeStage();
    if (!opened[stage]) {
      StatusCode status = hash.handles(stage) ? hash.beginProbe(rows)
          : merge.handles(stage) ? merge.beginProbe(rows) : rows.open(stage + 1);
      if (!status.isOk()) return status;
      opened[stage] = true;
    }
    StatusCode status = hash.handles(stage) ? hash.nextCandidate(rows)
        : merge.handles(stage) ? merge.nextCandidate(rows) : rows.next(stage + 1);
    if (status == StatusCode.CONFLICT) return finishStage();
    if (!status.isOk()) return status;
    metrics.candidate(stage);
    if (!access.matches(rows, stage)) return StatusCode.CONFLICT;
    status = predicates.matchesOn(stage, rows);
    if (!status.isOk() || !predicates.matched()) return status.isOk()
        ? StatusCode.CONFLICT : status;
    matched[stage] = true;
    metrics.onTrue(stage);
    return descendOrPublish();
  }

  private StatusCode descendOrPublish() {
    if (stage + 1 < command.joinChain().stageCount()) {
      stage++;
      resetStage(stage);
      return StatusCode.CONFLICT;
    }
    StatusCode status = predicates.matchesWhere(where, rows);
    if (status.isOk() && predicates.matched()) metrics.whereTrue();
    return !status.isOk() || predicates.matched() ? status : StatusCode.CONFLICT;
  }

  private StatusCode finishStage() {
    int rightRole = stage + 1;
    StatusCode status = hash.handles(stage) || merge.handles(stage)
        ? StatusCode.OK : rows.closeScan(rightRole);
    if (!status.isOk()) return status;
    rows.clearCandidate(rightRole);
    opened[stage] = false;
    finished[stage] = true;
    if (!matched[stage] && command.joinChain().isLeft(stage)
        && !nullEmitted[stage]) {
      nullEmitted[stage] = true;
      metrics.nullExtension(stage);
      rows.setNull(rightRole);
      return descendOrPublish();
    }
    return completeStage();
  }

  private StatusCode completeStage() {
    resetStage(stage);
    stage--;
    return StatusCode.CONFLICT;
  }

  void resetProgress() {
    stage = -1;
    resetStages();
  }

  long rootCandidates() { return metrics.roots(); }
  long stageAccessRows(int current) { return metrics.candidates(current); }
  long stageOnTrue(int current) { return metrics.onTrueCount(current); }
  long stageNullExtensions(int current) { return metrics.nullExtensions(current); }
  long stagePublished(int current) {
    return metrics.onTrueCount(current) + metrics.nullExtensions(current);
  }
  long whereTrue() { return metrics.whereTrueCount(); }
  boolean indexedRole(int role) { return rows.indexed(role); }
  boolean exactRole(int role) { return rows.exact(role); }
  boolean uniqueRole(int role) { return rows.unique(role); }
  int accessColumn(int role) { return rows.accessColumn(role); }
  int strategy(int current) {
    int planned = context.strategy(current);
    return planned == SqlJoinStrategy.HASH || planned == SqlJoinStrategy.MERGE
        ? planned : SqlJoinStrategy.NESTED_LOOP;
  }
  boolean stageFallback(int current) { return runtimeFallback[current]; }
  boolean hasResources() {
    return stage >= 0 || hash.hasResources() || merge.hasResources()
        || orderedRoot.hasResources() || rows != null && rows.hasResources();
  }

  StatusCode close() {
    resetProgress();
    StatusCode status = hash.close();
    StatusCode mergeStatus = merge.close();
    StatusCode orderedStatus = orderedRoot.close();
    if (status.isOk()) status = mergeStatus;
    return status.isOk() ? orderedStatus : status;
  }

  private void resetStages() {
    for (int current = 0; current < opened.length; current++) resetStage(current);
  }

  private void resetFallback() {
    for (int current = 0; current < runtimeFallback.length; current++) {
      runtimeFallback[current] = false;
    }
  }

  private void resetStage(int current) {
    if (current >= 0 && rows != null) rows.clearCandidate(current + 1);
    opened[current] = false;
    matched[current] = false;
    nullEmitted[current] = false;
    finished[current] = false;
  }
}
