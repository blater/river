package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;

/** Reusable physical frame for one descriptor predicate-subquery edge. */
final class SqlDescriptorSubqueryFrame {
  private final SqlDescriptorSubqueryFrameState state;
  private final SqlDescriptorSubqueryPreparation preparation =
      new SqlDescriptorSubqueryPreparation();
  private final SqlDescriptorSubqueryInvocation invocation =
      new SqlDescriptorSubqueryInvocation();

  SqlDescriptorSubqueryFrame(
      RelationalSession relationalSession,
      SqlSessionShapeBudget budget,
      SqlSubqueryPlan subqueryPlan,
      SqlSubqueryResultCache resultCache,
      int edgeIndex) {
    state = new SqlDescriptorSubqueryFrameState(
        relationalSession, budget, subqueryPlan, resultCache, edgeIndex);
  }

  StatusCode prepare(
      SqlCommand child,
      SqlCommand outerCommand,
      TableDescriptor outer,
      int edgeKind,
      boolean edgeNegated,
      SqlComparison edgeComparison,
      int leftDescriptor) {
    return preparation.prepare(
        state, child, outerCommand, outer, edgeKind,
        edgeNegated, edgeComparison, leftDescriptor);
  }

  StatusCode evaluate(
      boolean leftNull, long leftHigh, long left, SqlDescriptorValueSource outer) {
    return invocation.evaluate(state, leftNull, leftHigh, left, outer);
  }

  int truth() { return state.outcome.truth(); }

  boolean hasResources() { return state.hasResources(); }

  StatusCode close() { return state.close(); }
}
