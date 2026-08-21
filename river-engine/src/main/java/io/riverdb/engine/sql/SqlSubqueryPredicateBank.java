package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlQuery;
import io.riverdb.storage.heap.HeapRowResult;

/** Owns the independent Boolean evaluator and workspace for each graph block. */
final class SqlSubqueryPredicateBank {
  private final SqlBooleanPredicateWorkspace[] workspaces =
      new SqlBooleanPredicateWorkspace[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlBooleanPredicateEvaluator[] evaluators =
      new SqlBooleanPredicateEvaluator[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlBooleanPredicateEvaluator.Match[] matches =
      new SqlBooleanPredicateEvaluator.Match[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlExpressionEvaluator expressions;
  private final SqlTemporalContext temporal;
  private final SqlSubqueryLeafEvaluator leaves;
  private final SqlNestedRowProvider rows;
  private SqlJoinedPredicateBank joined;

  SqlSubqueryPredicateBank(
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlTemporalContext temporalContext,
      SqlSubqueryLeafEvaluator leafEvaluator,
      SqlNestedRowProvider rowProvider) {
    bound = statement;
    query = statement.executableQuery;
    expressions = evaluator;
    temporal = temporalContext;
    leaves = leafEvaluator;
    rows = rowProvider;
  }

  StatusCode prepare(int block) {
    return bound.query.block(block).joinChain() == null
        ? prepareTable(block) : prepareJoin(block);
  }

  StatusCode matches(
      int block,
      long key,
      HeapRowResult row,
      SqlBooleanPredicateEvaluator.Match result) {
    return evaluators[block].matchesNested(
        bound.query.block(block),
        bound.nestedBoolean(block),
        key,
        row,
        query.block(block).table(),
        leaves,
        rows,
        result);
  }

  StatusCode accept(int block) {
    return evaluators[block].matchesNested(
        bound.query.block(block),
        bound.nestedBoolean(block),
        rows.key(block, 0),
        rows.row(block, 0),
        rows.table(block, 0),
        leaves,
        rows,
        matches[block]);
  }

  boolean accepted(int block) { return matches[block].matched(); }

  SqlJoinPredicateCallback joinPredicates(int block) {
    return joined == null || bound.query.block(block).joinChain() == null
        ? null : joined.evaluator(block);
  }

  void reset() {
    for (SqlBooleanPredicateEvaluator evaluator : evaluators) {
      if (evaluator != null) evaluator.reset();
    }
    if (joined != null) joined.reset();
  }

  private StatusCode prepareTable(int block) {
    int frame = query.blockDepth(block) - 1;
    if (workspaces[frame] == null) {
      workspaces[frame] = new SqlBooleanPredicateWorkspace(expressions, temporal);
    }
    if (evaluators[block] == null) {
      evaluators[block] = new SqlBooleanPredicateEvaluator(
          workspaces[frame], temporal);
      matches[block] = new SqlBooleanPredicateEvaluator.Match();
    }
    return evaluators[block].prepare(
        bound.query.block(block), bound.nestedBoolean(block));
  }

  private StatusCode prepareJoin(int block) {
    if (joined == null) {
      joined = new SqlJoinedPredicateBank(
          bound, expressions, temporal, leaves, rows);
    }
    return joined.prepare(block);
  }
}
