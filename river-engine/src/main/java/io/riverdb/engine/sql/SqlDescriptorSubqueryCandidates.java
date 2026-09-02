package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Consumes accepted descriptor rows and updates one subquery outcome/cache. */
final class SqlDescriptorSubqueryCandidates {
  StatusCode scan(
      SqlDescriptorSubqueryFrameState state, SqlDescriptorValueSource outer) {
    long accepted = 0;
    long limit = state.command.rowLimit();
    StatusCode status = StatusCode.OK;
    while (status.isOk() && !state.index.empty() && accepted < limit) {
      status = state.session.descriptorRows().nextScan(
          state.cursor, state.values.values(), state.identity);
      if (status == StatusCode.CONFLICT) return StatusCode.OK;
      if (!status.isOk()) return status;
      state.plan.candidate(state.edge);
      state.childSource.use(state.values.values());
      status = state.predicate.evaluate(state.childSource, outer);
      if (!status.isOk() || !state.predicate.matched()) continue;
      accepted++;
      accept(state);
      if (state.outcome.complete()
          && !(state.caching
              && state.kind == io.riverdb.sql.SqlQuery.SUBQUERY_MEMBERSHIP)) break;
    }
    return status;
  }

  private void accept(SqlDescriptorSubqueryFrameState state) {
    state.plan.accept(state.edge);
    state.outcome.accept(
        state.projection.isNull(state.values.values()),
        state.projection.highValue(state.values.values()),
        state.projection.value(state.values.values()));
    if (state.caching && state.kind != io.riverdb.sql.SqlQuery.SUBQUERY_EXISTS) {
      prepareCandidate(state);
      if (!state.cache.append(state.edge, state.candidateOperand)) {
        state.cache.abandon(state.edge);
        state.caching = false;
      }
    }
  }

  private void prepareCandidate(SqlDescriptorSubqueryFrameState state) {
    if (state.projection.isNull(state.values.values())) {
      state.candidateOperand.setNull(state.childDescriptor);
    } else {
      state.candidateOperand.setValue(
          state.projection.highValue(state.values.values()),
          state.projection.value(state.values.values()), state.childDescriptor, false);
    }
  }
}
