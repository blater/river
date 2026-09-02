package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;

/** Reusable descriptor or mixed JOIN source for one nested graph depth. */
final class SqlSubqueryUniversalJoinFrame {
  private final SqlUniversalJoinRows rows;
  private final SqlUniversalJoinPredicates predicates;
  private final SqlUniversalJoinedRowProvider provider;
  private final SqlUniversalJoinSource source;
  private SqlBoundJoinContext context;
  private boolean active;

  SqlSubqueryUniversalJoinFrame(
      RelationalSession session,
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporal,
      int block,
      SqlSubqueryLeafEvaluator leaves,
      SqlNestedRowProvider ancestors,
      SqlSubqueryPlan plan,
      SqlSessionShapeBudget shapeBudget) {
    rows = new SqlUniversalJoinRows(session);
    source = new SqlUniversalJoinSource(session, shapeBudget);
    provider = new SqlUniversalJoinedRowProvider(block, ancestors);
    predicates = new SqlUniversalJoinPredicates(
        expressions, temporal, block, leaves, ancestors, plan, shapeBudget);
  }

  StatusCode prepare(
      SqlCommand command,
      SqlBoundJoinContext joinContext,
      SqlBoundBooleanPredicateProgram where) {
    context = joinContext;
    StatusCode status = rows.resolveBound(command, context);
    if (status.isOk()) status = predicates.prepare(command, context, where);
    if (status.isOk()) {
      rows.configureAccess(command, context, where);
      source.configure(command, context, where, rows, predicates);
    }
    return status;
  }

  StatusCode begin() {
    if (active) return StatusCode.CONFLICT;
    StatusCode status = source.begin();
    if (status.isOk()) active = true;
    return status;
  }

  StatusCode next() {
    StatusCode status = active ? source.next() : StatusCode.CONFLICT;
    if (status.isOk()) provider.activate(rows);
    return status;
  }

  StatusCode finish() {
    StatusCode status = source.close();
    if (status.isOk()) status = rows.closeScans();
    if (status.isOk()) {
      source.resetProgress();
      provider.clear();
      active = false;
    }
    return status;
  }

  StatusCode reset() {
    StatusCode status = finish();
    if (status.isOk()) status = rows.reset(null);
    if (status.isOk()) {
      predicates.reset();
      context = null;
    }
    return status;
  }

  SqlUniversalJoinRows rows() { return rows; }
  void publishMetrics(SqlBlockSource target) { target.publishJoinMetrics(source); }
  SqlNestedRowProvider provider() { return provider; }
  boolean active() { return active; }
}
