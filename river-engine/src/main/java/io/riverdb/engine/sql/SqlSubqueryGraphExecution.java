package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.storage.heap.HeapRowResult;

/** Executes canonical predicate-subquery leaves with depth-owned cursor frames. */
final class SqlSubqueryGraphExecution
    implements SqlSubqueryLeafEvaluator, SqlSubqueryCandidateEvaluator {

  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlSubqueryPredicateBank predicates;
  private final SqlSubqueryFrames frames;
  private final SqlSubqueryResultCache cache;
  private final SqlSubqueryPlan plan;
  private final SqlNestedProjectionExecution projections;
  private final SqlSubqueryValueScanner scanner;

  SqlSubqueryGraphExecution(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlTemporalContext temporalContext) {
    bound = statement;
    query = statement.executableQuery;
    projections = new SqlNestedProjectionExecution(
        statement, evaluator, temporalContext);
    cache = new SqlSubqueryResultCache(query, evaluator);
    frames = new SqlSubqueryFrames(
        relationalSession, statement, evaluator, temporalContext);
    predicates = new SqlSubqueryPredicateBank(
        statement, evaluator, temporalContext, this, frames);
    plan = new SqlSubqueryPlan(statement, frames.access());
    scanner = new SqlSubqueryValueScanner(
        query, frames, projections, cache, evaluator, this, plan);
  }

  StatusCode prepare() {
    StatusCode status = close();
    if (!status.isOk() || query.edgeCount() == 0) return status;
    cache.prepare();
    frames.prepareAccess();
    plan.reset();
    int root = query.sourceBlockCount() - 1;
    for (int block = root; status.isOk() && block < query.blockCount(); block++) {
      status = predicates.prepare(block);
      if (status.isOk() && block > root) status = projections.prepare(block);
      if (status.isOk()) frames.prepare(
          block, block > root && text(query.block(block).projectionType()));
    }
    return status;
  }

  void describe() {
    frames.prepareAccess();
    plan.reset();
  }

  StatusCode matches(
      int block,
      long key,
      HeapRowResult row,
      SqlBooleanPredicateEvaluator.Match result) {
    frames.activate(block, key, row);
    return predicates.matches(block, key, row, result);
  }

  @Override
  public StatusCode evaluate(
      int edge, SqlPredicateOperand left, Truth truth) {
    if (edge < 0 || edge >= query.edgeCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return scanner.evaluate(edge, left, truth);
  }

  @Override
  public StatusCode accept(int child) {
    return predicates.accept(child);
  }

  @Override
  public boolean accepted(int child) { return predicates.accepted(child); }

  HeapRowResult evaluatedRow(int block, HeapRowResult original) {
    return frames.evaluatedRow(block, original);
  }

  void releaseRow(int block) {
    frames.release(block);
  }

  StatusCode close() {
    StatusCode status = frames.close();
    if (!status.isOk()) return status;
    clearValues();
    return status;
  }

  boolean hasResources() {
    return frames.hasResources();
  }

  SqlSubqueryPlan plan() { return plan; }

  SqlJoinPredicateCallback joinPredicates(int block) {
    return predicates.joinPredicates(block);
  }

  StatusCode reset() {
    StatusCode status = close();
    if (!status.isOk()) return status;
    predicates.reset();
    projections.reset();
    return StatusCode.OK;
  }

  private void clearValues() {
    cache.clear();
  }

  private static boolean text(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

}
