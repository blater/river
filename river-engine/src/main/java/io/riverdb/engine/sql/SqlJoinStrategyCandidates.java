package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.storage.heap.HeapRowResult;

/** Lazy subordinate HASH/MERGE candidate workspaces for one selected stage. */
final class SqlJoinStrategyCandidates {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private SqlJoinHashWorkspace hash;
  private SqlJoinMergeWorkspace merge;
  private int stage = -1;
  private int strategy = SqlJoinStrategy.NESTED_LOOP;
  private boolean fallback;

  SqlJoinStrategyCandidates(
      RelationalSession relationalSession, BoundSqlStatement statement) {
    session = relationalSession;
    bound = statement;
  }

  StatusCode begin() {
    resetMetrics();
    stage = selectedStage();
    if (stage < 0) return StatusCode.OK;
    strategy = bound.joinStrategy(stage);
    StatusCode status;
    if (strategy == SqlJoinStrategy.HASH) {
      if (hash == null) hash = new SqlJoinHashWorkspace(session);
      status = hash.begin(bound);
      if (status.isOk()) fallback = hash.fallback();
    } else {
      if (merge == null) merge = new SqlJoinMergeWorkspace(session);
      status = merge.begin(bound);
    }
    return status;
  }

  StatusCode beginProbe(
      SqlJoinRoleRows rows, SqlExpressionEvaluator expressions) {
    return strategy == SqlJoinStrategy.HASH
        ? hash.beginProbe(rows, bound) : merge.beginProbe(rows, expressions);
  }

  StatusCode nextCandidate() {
    return strategy == SqlJoinStrategy.HASH
        ? hash.nextCandidate() : merge.nextCandidate();
  }

  long key() { return strategy == SqlJoinStrategy.HASH ? hash.key() : merge.key(); }

  HeapRowResult row() {
    return strategy == SqlJoinStrategy.HASH ? hash.row() : merge.row();
  }

  boolean handles(int current) { return current == stage; }
  boolean fallback(int current) { return current == stage && fallback; }

  boolean hasResources() {
    return hash != null && hash.hasResources()
        || merge != null && merge.hasResources();
  }

  StatusCode close() {
    StatusCode status;
    if (strategy == SqlJoinStrategy.MERGE) {
      status = merge == null ? StatusCode.OK : merge.close();
      if (status.isOk() && hash != null) status = hash.close();
    } else {
      status = hash == null ? StatusCode.OK : hash.close();
      if (status.isOk() && merge != null) status = merge.close();
    }
    return status;
  }

  void resetMetrics() {
    stage = -1;
    strategy = SqlJoinStrategy.NESTED_LOOP;
    fallback = false;
  }

  private int selectedStage() {
    for (int current = 0;
        current < bound.command.joinChain().stageCount(); current++) {
      if (bound.joinStrategy(current) != SqlJoinStrategy.NESTED_LOOP) return current;
    }
    return -1;
  }
}
