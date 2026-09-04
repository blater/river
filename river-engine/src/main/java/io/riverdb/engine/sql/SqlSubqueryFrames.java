package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlQuery;
import io.riverdb.storage.heap.HeapRowResult;

/** Routes nested graph frames between joins and mixed physical table sources. */
final class SqlSubqueryFrames implements SqlNestedRowProvider {
  private final BoundSqlQuery query;
  private final SqlSubqueryJoinFrames joined;
  private final SqlSubqueryTableFrames tables;
  private SqlUniversalJoinRows externalUniversal;
  private int externalUniversalBlock = -1;

  SqlSubqueryFrames(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlExpressionEvaluator evaluator,
      SqlTemporalContext temporal,
      SqlSubqueryJoinFrames joinFrames) {
    query = bound.executableQuery;
    joined = joinFrames;
    tables = new SqlSubqueryTableFrames(session, bound, evaluator, temporal);
  }

  void prepareAccess() { tables.prepareAccess(); }
  int accessColumn(int block) { return tables.accessColumn(block); }
  void prepareGraph() { joined.prepareGraph(); }

  StatusCode prepare(
      int block,
      boolean valueProjection,
      boolean textProjection,
      SqlJoinPredicateCallback joinPredicates) {
    boolean tableSource = joinPredicates == null;
    StatusCode status = tables.prepare(
        block, valueProjection, textProjection, tableSource, parent(block));
    if (status.isOk() && !tableSource) {
      status = joined.prepare(block, joinPredicates, this);
    }
    return status;
  }

  void registerExternalJoinSource(int block, SqlJoinChainSource source) {
    joined.registerExternal(block, source);
  }
  void clearExternalJoinSource() { joined.clearExternal(); }
  void registerExternalUniversal(int block, SqlUniversalJoinRows rows) {
    externalUniversalBlock = block;
    externalUniversal = rows;
  }
  void clearExternalUniversal() {
    externalUniversalBlock = -1;
    externalUniversal = null;
  }
  void activate(int block, long key, HeapRowResult row) { tables.activate(block, key, row); }

  StatusCode own(int block) {
    if (block == externalUniversalBlock && externalUniversal != null) {
      return StatusCode.OK;
    }
    if (joined.universal(block)) return StatusCode.OK;
    SqlJoinChainSource source = joinedSource(block);
    return source == null
        ? tables.own(block)
        : source.rows().ownThrough(query.block(block).roleCount() - 1);
  }

  StatusCode begin(int child) {
    return joinedPredicate(child) == null ? tables.begin(child, this) : joined.begin(child);
  }

  StatusCode next(int child) {
    if (joined.universal(child)) return joined.nextUniversal(child);
    SqlJoinChainSource source = joinedSource(child);
    return source == null ? tables.next(child) : source.next();
  }

  SqlPredicateOperand projected(int block) { return tables.projected(block); }

  StatusCode finish(int child, StatusCode body) {
    int frame = frame(child);
    if (deeperResources(frame)) return body.isOk() ? StatusCode.CONFLICT : body;
    StatusCode closed = joinedPredicate(child) == null
        ? tables.closeBlock(child) : joined.closeFrame(frame);
    if (closed.isOk()) release(child);
    return body.isOk() ? closed : body;
  }

  HeapRowResult evaluatedRow(int block, HeapRowResult original) {
    return tables.evaluatedRow(block, original);
  }
  void release(int block) {
    if (joinedPredicate(block) == null) tables.release(block);
  }

  @Override public long key(int block, int role) {
    if (block == externalUniversalBlock && externalUniversal != null) {
      return externalUniversal.key(role);
    }
    SqlUniversalJoinRows universal = joined.universalRows(block);
    if (universal != null) return universal.key(role);
    SqlJoinChainSource source = joinedSource(block);
    return source == null ? tables.key(block, role) : source.rows().key(role);
  }
  @Override public HeapRowResult row(int block, int role) {
    if (block == externalUniversalBlock && externalUniversal != null) return null;
    if (joined.universal(block)) return null;
    SqlJoinChainSource source = joinedSource(block);
    return source == null ? tables.row(block, role) : source.rows().row(role);
  }
  @Override public TableDefinition table(int block, int role) {
    if (block == externalUniversalBlock && externalUniversal != null) {
      return externalUniversal.table(role);
    }
    SqlUniversalJoinRows universal = joined.universalRows(block);
    if (universal != null) return universal.table(role);
    SqlJoinChainSource source = joinedSource(block);
    return source == null ? tables.table(block, role) : source.rows().table(role);
  }
  @Override public SqlBlockRow blockRow(int block, int role) {
    if (block == externalUniversalBlock && externalUniversal != null) {
      return externalUniversal.row(role);
    }
    SqlUniversalJoinRows universal = joined.universalRows(block);
    if (universal != null) return universal.row(role);
    return joinedSource(block) == null ? tables.blockRow(block, role) : null;
  }

  StatusCode close() {
    for (int frame = SqlQuery.MAXIMUM_QUERY_BLOCKS - 1; frame >= 0; frame--) {
      StatusCode status = joined.closeFrame(frame);
      if (!status.isOk()) return status;
    }
    StatusCode status = joined.reset();
    if (status.isOk()) status = tables.close();
    if (status.isOk()) clearExternalUniversal();
    return status;
  }

  boolean hasResources() { return joined.hasResources() || tables.hasResources(); }

  private boolean deeperResources(int frame) {
    return joined.deeperResources(frame) || tables.deeperResources(frame);
  }
  private SqlJoinChainSource joinedSource(int block) { return joined.source(block); }
  private SqlJoinPredicateCallback joinedPredicate(int block) { return joined.predicate(block); }
  private int frame(int block) { return query.blockDepth(block) - 1; }
  private boolean parent(int block) {
    for (int edge = 0; edge < query.edgeCount(); edge++) {
      if (query.edgeParent(edge) == block) return true;
    }
    return false;
  }
}
