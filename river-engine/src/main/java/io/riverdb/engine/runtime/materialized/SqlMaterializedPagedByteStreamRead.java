package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.ByteBuffer;

/** Validates a published range before copying it into the caller-owned destination. */
final class SqlMaterializedPagedByteStreamRead {
  private SqlMaterializedPagedByteStreamRead() {}

  static StatusCode read(
      SqlMaterializedPagedByteStreamState state, long offset,
      ByteBuffer target, StatusDetail detail) {
    if (detail != null) detail.reset();
    if (!SqlMaterializedPagedByteStreamLifecycle.usable(state, detail)
        || target == null || offset < 0 || offset > state.logicalLength) {
      return SqlMaterializedPagedByteStreamLifecycle.invalidInput(detail);
    }
    int bytes = target.remaining();
    if (bytes > state.logicalLength - offset) {
      return SqlMaterializedPagedByteStreamLifecycle.fail(
          detail, StatusCode.INVALID_EXTERNAL_INPUT, "materialized read exceeds published length");
    }
    int targetStart = target.position();
    state.copied = 0;
    state.position = offset;
    StatusCode status = SqlMaterializedPagedByteStreamTransfer.read(
        state, target, targetStart, bytes);
    if (!status.isOk()) return SqlMaterializedPagedByteStreamFailure.terminal(state, status, detail);
    target.position(targetStart + bytes);
    return StatusCode.OK;
  }
}
