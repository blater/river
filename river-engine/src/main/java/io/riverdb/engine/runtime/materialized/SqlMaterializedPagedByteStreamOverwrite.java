package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.ByteBuffer;

/** Checked in-place update of bytes already published by a materialized stream. */
final class SqlMaterializedPagedByteStreamOverwrite {
  private SqlMaterializedPagedByteStreamOverwrite() {}

  static StatusCode write(
      SqlMaterializedPagedByteStreamState state,
      long offset,
      ByteBuffer source,
      StatusDetail detail) {
    if (detail != null) detail.reset();
    if (!SqlMaterializedPagedByteStreamLifecycle.usable(state, detail)
        || source == null || offset < 0 || offset > state.logicalLength) {
      return SqlMaterializedPagedByteStreamLifecycle.invalidInput(detail);
    }
    int bytes = source.remaining();
    if (bytes > state.logicalLength - offset) {
      return SqlMaterializedPagedByteStreamLifecycle.fail(
          detail, StatusCode.INVALID_EXTERNAL_INPUT,
          "materialized overwrite exceeds published length");
    }
    int sourceStart = source.position();
    state.copied = 0;
    state.position = offset;
    state.mutated = false;
    StatusCode status = SqlMaterializedPagedByteStreamTransfer.overwrite(
        state, source, sourceStart, bytes);
    if (!status.isOk()) {
      return !state.mutated
          ? SqlMaterializedPagedByteStreamFailure.terminal(state, status, detail)
          : SqlMaterializedPagedByteStreamFailure.terminalAfterMutation(
              state, status, detail);
    }
    source.position(sourceStart + bytes);
    return StatusCode.OK;
  }
}
