package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Owns descriptor subquery frames used by one root descriptor scan. */
final class SqlDescriptorSubqueryExecution {
  private final RelationalSession session;
  private final SqlSessionShapeBudget budget;
  private final SqlSubqueryPlan plan;
  private final SqlSubqueryResultCache cache;
  private final SqlDescriptorSubqueryFrame[] frames =
      new SqlDescriptorSubqueryFrame[SqlQuery.MAXIMUM_EDGES];
  private int count;
  private int truth;

  SqlDescriptorSubqueryExecution(
      RelationalSession relationalSession, SqlSessionShapeBudget shapeBudget,
      SqlSubqueryPlan subqueryPlan, SqlSubqueryResultCache resultCache) {
    session = relationalSession;
    budget = shapeBudget;
    plan = subqueryPlan;
    cache = resultCache;
  }

  StatusCode prepare(SqlQuery query, SqlCommand command, TableDescriptor table) {
    StatusCode status = close();
    if (!status.isOk() || query == null || query.edgeCount() == 0) return status;
    plan.reset();
    cache.prepare();
    for (int edge = 0; status.isOk() && edge < query.edgeCount(); edge++) {
      status = prepareEdge(query, command, table, edge);
    }
    if (status.isOk()) count = query.edgeCount();
    return status.isOk() ? status : failPrepare(status);
  }

  private StatusCode prepareEdge(
      SqlQuery query, SqlCommand root, TableDescriptor table, int edge) {
    if (query.edgeParent(edge) != 0) return StatusCode.FEATURE_NOT_SUPPORTED;
    SqlCommand parent = root;
    SqlCommand child = query.block(query.edgeChild(edge));
    if (child == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (frames[edge] == null) {
      try {
        frames[edge] = new SqlDescriptorSubqueryFrame(session, budget, plan, cache, edge);
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    int leaf = query.edgeLeaf(edge);
    SqlBooleanPredicateProgram predicates = parent.wherePredicates();
    int descriptor = query.edgeKind(edge) == SqlQuery.SUBQUERY_EXISTS ? 0
        : SqlDescriptorSubqueryLeftType.resolve(predicates, leaf, parent, table);
    return frames[edge].prepare(
        child, parent, table, query.edgeKind(edge), predicates.leafNegated(leaf),
        predicates.comparison(leaf), descriptor);
  }

  StatusCode evaluate(
      int edge, boolean leftNull, long leftHigh, long left,
      SqlDescriptorValueSource outer) {
    if (edge < 0 || edge >= count || frames[edge] == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = frames[edge].evaluate(leftNull, leftHigh, left, outer);
    if (status.isOk()) truth = frames[edge].truth();
    return status;
  }

  void parentAccepted() { plan.parentAccepted(0); }
  int truth() { return truth; }
  boolean active() { return count > 0; }

  StatusCode close() {
    StatusCode status = StatusCode.OK;
    for (int edge = frames.length - 1; edge >= 0; edge--) {
      if (frames[edge] == null) continue;
      StatusCode closed = frames[edge].close();
      if (status.isOk()) status = closed;
    }
    if (status.isOk()) {
      count = 0;
      truth = 0;
    }
    return status;
  }

  private StatusCode failPrepare(StatusCode failure) {
    close();
    return failure;
  }
}
