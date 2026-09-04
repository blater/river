package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;

/** Opens the validated descriptor cursor selected for one subquery invocation. */
final class SqlDescriptorSubqueryCursor {
  StatusCode open(
      SqlDescriptorSubqueryFrameState state, SqlDescriptorValueSource outer) {
    StatusCode status = state.cursor.reset();
    if (status.isOk()) status = state.session.resolveDescriptor(
        state.command.tableName(), state.pin, state.detail);
    if (status.isOk() && !state.index.matches(state.pin.descriptor())) {
      status = StatusCode.RETRY;
    }
    if (status.isOk()) status = state.index.bind(outer);
    long limit = state.command.rowLimit();
    if (status.isOk() && !state.index.empty() && limit > 0) {
      status = state.index.active()
          ? state.session.descriptorRows().beginIndexScan(
              state.pin, state.index.bounds(), LockMode.SHARED, state.cursor)
          : state.session.descriptorRows().beginScan(state.pin, state.cursor);
    }
    return status;
  }
}
