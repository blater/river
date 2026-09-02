package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Coordinates cache, cursor, candidate, and outcome phases for one invocation. */
final class SqlDescriptorSubqueryInvocation {
  private final SqlDescriptorSubqueryCursor cursor = new SqlDescriptorSubqueryCursor();
  private final SqlDescriptorSubqueryCandidates candidates =
      new SqlDescriptorSubqueryCandidates();

  StatusCode evaluate(
      SqlDescriptorSubqueryFrameState state,
      boolean leftNull, long leftHigh, long left, SqlDescriptorValueSource outer) {
    state.plan.invoke(state.edge);
    prepareLeft(state, leftNull, leftHigh, left);
    if (state.cache.enabled(state.edge) && state.cache.available(state.edge)) {
      state.outcome.cached(state.cache.truth(state.edge, state.leftOperand));
      state.plan.result(state.edge);
      return StatusCode.OK;
    }
    state.plan.execute(state.edge);
    state.outcome.begin(leftNull, leftHigh, left);
    state.caching = state.cache.enabled(state.edge);
    if (state.caching && state.kind != io.riverdb.sql.SqlQuery.SUBQUERY_EXISTS) {
      state.cache.start(state.edge);
    }
    StatusCode status = cursor.open(state, outer);
    if (status.isOk()) status = candidates.scan(state, outer);
    StatusCode closed = state.finishScan();
    if (status.isOk()) status = closed;
    if (!status.isOk()) return status;
    status = state.outcome.finish();
    if (status.isOk() && state.caching) completeCache(state);
    if (status.isOk()) state.plan.result(state.edge);
    return status;
  }

  private static void prepareLeft(
      SqlDescriptorSubqueryFrameState state,
      boolean nullValue, long high, long value) {
    if (nullValue) state.leftOperand.setNull(state.leftDescriptor);
    else state.leftOperand.setValue(high, value, state.leftDescriptor, false);
  }

  private static void completeCache(SqlDescriptorSubqueryFrameState state) {
    if (state.kind == io.riverdb.sql.SqlQuery.SUBQUERY_EXISTS) {
      state.cache.completeTruth(state.edge, state.outcome.truth());
    } else {
      state.cache.completeValues(state.edge, (int) state.outcome.rows());
    }
  }
}
