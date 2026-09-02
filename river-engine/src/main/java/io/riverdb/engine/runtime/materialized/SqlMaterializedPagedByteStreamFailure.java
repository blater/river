package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Applies the stream terminal-state policy while preserving page diagnostics. */
final class SqlMaterializedPagedByteStreamFailure {
  private SqlMaterializedPagedByteStreamFailure() {}

  static StatusCode terminal(SqlMaterializedPagedByteStreamState state, StatusCode status,
      StatusDetail detail) {
    if (status == StatusCode.RESOURCE_EXHAUSTED || status == StatusCode.RETRY
        || status == StatusCode.INVALID_EXTERNAL_INPUT) {
      return SqlMaterializedPagedByteStreamLifecycle.fail(
          detail, status, "materialized stream append/read failed");
    }
    state.failed = true;
    state.failureStatus = status;
    if (detail != null && state.internalDetail.code() == status
        && state.internalDetail.length() != 0) {
      detail.copyFrom(state.internalDetail);
      return status;
    }
    return SqlMaterializedPagedByteStreamLifecycle.fail(
        detail, status, "materialized stream became terminal");
  }

  static StatusCode terminalAfterMutation(
      SqlMaterializedPagedByteStreamState state,
      StatusCode status,
      StatusDetail detail) {
    state.failed = true;
    state.failureStatus = status;
    return SqlMaterializedPagedByteStreamLifecycle.fail(
        detail, status, "materialized stream failed after a partial mutation");
  }
}
