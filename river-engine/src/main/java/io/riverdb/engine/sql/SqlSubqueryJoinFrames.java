package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlQuery;

/** Lazily owns one reusable joined child source per active graph depth. */
final class SqlSubqueryJoinFrames {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlExpressionEvaluator expressions;
  private final SqlTemporalContext temporal;
  private final SqlSubqueryLeafEvaluator leaves;
  private final SqlSessionShapeBudget budget;
  private SqlSubqueryPlan plan;
  private SqlJoinChainSource[] sources;
  private SqlSubqueryUniversalJoinFrame[] universal;
  private SqlJoinPredicateCallback[] predicates;
  private int[] blocks;
  private SqlJoinChainSource externalSource;
  private int externalBlock = -1;

  SqlSubqueryJoinFrames(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlTemporalContext temporalContext,
      SqlSubqueryLeafEvaluator leafEvaluator,
      SqlSessionShapeBudget shapeBudget) {
    session = relationalSession;
    bound = statement;
    query = statement.executableQuery;
    expressions = evaluator;
    temporal = temporalContext;
    leaves = leafEvaluator;
    budget = shapeBudget;
  }

  void plan(SqlSubqueryPlan subqueryPlan) { plan = subqueryPlan; }

  void prepareGraph() {
    if (predicates == null) return;
    for (int block = 0; block < predicates.length; block++) {
      predicates[block] = null;
    }
  }

  StatusCode prepare(
      int block,
      SqlJoinPredicateCallback callback,
      SqlNestedRowProvider ancestors) {
    prepareBank();
    predicates[block] = callback;
    int frame = frame(block);
    if (descriptor(block)) {
      if (universal[block] == null) {
        universal[block] = new SqlSubqueryUniversalJoinFrame(
            session, expressions, temporal, block, leaves, ancestors, plan, budget);
      }
      return universal[block].prepare(
          bound.query.block(block),
          bound.existingJoinContext(block),
          bound.nestedBoolean(block),
          -1);
    }
    if (block != externalBlock && sources[frame] == null) {
      sources[frame] = new SqlJoinChainSource(session, expressions, budget);
    }
    return StatusCode.OK;
  }

  void registerExternal(int block, SqlJoinChainSource source) {
    if (!valid(block) || source == null || query.block(block) == null
        || query.block(block).joinChain() == null) {
      clearExternal();
      return;
    }
    externalBlock = block;
    externalSource = source;
  }

  void clearExternal() {
    externalBlock = -1;
    externalSource = null;
  }

  StatusCode begin(int block) {
    if (descriptor(block)) {
      int frame = frame(block);
      if (blocks[frame] >= 0) return StatusCode.CONFLICT;
      StatusCode status = universal[block].begin();
      if (status.isOk()) blocks[frame] = block;
      return status;
    }
    SqlJoinPredicateCallback callback = predicate(block);
    int frame = frame(block);
    SqlJoinChainSource source = sources[frame];
    if (source == null) return StatusCode.CORRUPTION;
    if (source.hasResources()) return StatusCode.CONFLICT;
    StatusCode status = source.configure(
        bound.existingJoinContext(block),
        bound.query.block(block),
        bound.nestedBoolean(block),
        callback);
    if (status.isOk()) {
      blocks[frame] = block;
      status = source.begin();
    }
    return status;
  }

  SqlJoinPredicateCallback predicate(int block) {
    return predicates == null || !valid(block)
        || query.block(block) == null || query.block(block).joinChain() == null
        ? null : predicates[block];
  }

  SqlJoinChainSource source(int block) {
    if (block == externalBlock) return externalSource;
    if (sources == null || !valid(block)) return null;
    int frame = frame(block);
    return blocks[frame] == block ? sources[frame] : null;
  }

  boolean universal(int block) {
    if (universal == null || !valid(block)) return false;
    int frame = frame(block);
    return blocks[frame] == block && universal[block] != null;
  }

  SqlUniversalJoinRows universalRows(int block) {
    return universal(block) ? universal[block].rows() : null;
  }

  StatusCode nextUniversal(int block) {
    return universal(block)
        ? universal[block].next() : StatusCode.CORRUPTION;
  }

  StatusCode closeFrame(int frame) {
    if (sources == null || blocks[frame] < 0) {
      return StatusCode.OK;
    }
    int block = blocks[frame];
    StatusCode status = universal[block] == null
        ? sources[frame].close() : universal[block].finish();
    if (status.isOk()) blocks[frame] = -1;
    return status;
  }

  boolean deeperResources(int frame) {
    if (sources == null) return false;
    for (int deeper = frame + 1; deeper < sources.length; deeper++) {
      if (sources[deeper] != null && sources[deeper].hasResources()) return true;
    }
    for (int block = 0; block < universal.length; block++) {
      if (universal[block] != null && universal[block].active()
          && frame(block) > frame) return true;
    }
    return false;
  }

  boolean hasResources() {
    if (sources == null) return false;
    for (SqlJoinChainSource source : sources) {
      if (source != null && source.hasResources()) return true;
    }
    for (SqlSubqueryUniversalJoinFrame candidate : universal) {
      if (candidate != null && candidate.active()) return true;
    }
    return false;
  }

  StatusCode reset() {
    if (universal == null) return StatusCode.OK;
    for (int frame = universal.length - 1; frame >= 0; frame--) {
      if (universal[frame] == null) continue;
      StatusCode status = universal[frame].reset();
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private void prepareBank() {
    if (sources != null) return;
    sources = new SqlJoinChainSource[SqlQuery.MAXIMUM_QUERY_BLOCKS];
    universal = new SqlSubqueryUniversalJoinFrame[SqlQuery.MAXIMUM_QUERY_BLOCKS];
    predicates = new SqlJoinPredicateCallback[SqlQuery.MAXIMUM_QUERY_BLOCKS];
    blocks = new int[SqlQuery.MAXIMUM_QUERY_BLOCKS];
    for (int frame = 0; frame < blocks.length; frame++) blocks[frame] = -1;
  }

  private int frame(int block) { return query.blockDepth(block) - 1; }
  private boolean descriptor(int block) {
    BoundSqlQuery.Block source = query.block(block);
    for (int role = 0; role < source.roleCount(); role++) {
      if (source.descriptorRole(role)) return true;
    }
    return false;
  }
  private static boolean valid(int block) {
    return block >= 0 && block < SqlQuery.MAXIMUM_QUERY_BLOCKS;
  }
}
