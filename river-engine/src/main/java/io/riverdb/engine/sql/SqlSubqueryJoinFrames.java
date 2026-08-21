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
  private SqlJoinChainSource[] sources;
  private SqlJoinPredicateCallback[] predicates;
  private int[] blocks;
  private SqlJoinChainSource externalSource;
  private int externalBlock = -1;

  SqlSubqueryJoinFrames(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator) {
    session = relationalSession;
    bound = statement;
    query = statement.executableQuery;
    expressions = evaluator;
  }

  void prepareGraph() {
    if (predicates == null) return;
    for (int block = 0; block < predicates.length; block++) {
      predicates[block] = null;
    }
  }

  void prepare(int block, SqlJoinPredicateCallback callback) {
    prepareBank();
    predicates[block] = callback;
    int frame = frame(block);
    if (block != externalBlock && sources[frame] == null) {
      sources[frame] = new SqlJoinChainSource(session, expressions);
    }
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

  StatusCode closeFrame(int frame) {
    if (sources == null || sources[frame] == null || blocks[frame] < 0) {
      return StatusCode.OK;
    }
    StatusCode status = sources[frame].close();
    if (status.isOk()) blocks[frame] = -1;
    return status;
  }

  boolean deeperResources(int frame) {
    if (sources == null) return false;
    for (int deeper = frame + 1; deeper < sources.length; deeper++) {
      if (sources[deeper] != null && sources[deeper].hasResources()) return true;
    }
    return false;
  }

  boolean hasResources() {
    if (sources == null) return false;
    for (SqlJoinChainSource source : sources) {
      if (source != null && source.hasResources()) return true;
    }
    return false;
  }

  private void prepareBank() {
    if (sources != null) return;
    sources = new SqlJoinChainSource[SqlQuery.MAXIMUM_QUERY_BLOCKS];
    predicates = new SqlJoinPredicateCallback[SqlQuery.MAXIMUM_QUERY_BLOCKS];
    blocks = new int[SqlQuery.MAXIMUM_QUERY_BLOCKS];
    for (int frame = 0; frame < blocks.length; frame++) blocks[frame] = -1;
  }

  private int frame(int block) { return query.blockDepth(block) - 1; }
  private static boolean valid(int block) {
    return block >= 0 && block < SqlQuery.MAXIMUM_QUERY_BLOCKS;
  }
}
