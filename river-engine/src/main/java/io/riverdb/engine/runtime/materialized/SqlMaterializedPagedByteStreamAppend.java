package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.ByteBuffer;

/** Validates and publishes one append without retaining caller buffers. */
final class SqlMaterializedPagedByteStreamAppend {
  private SqlMaterializedPagedByteStreamAppend() {}

  static StatusCode one(
      SqlMaterializedPagedByteStreamState state, ByteBuffer source,
      SqlMaterializedPagedByteStream.AppendResult target, StatusDetail detail) {
    return bytes(state, source, 1, target, detail);
  }

  static StatusCode bytes(
      SqlMaterializedPagedByteStreamState state, ByteBuffer source, long recordIncrement,
      SqlMaterializedPagedByteStream.AppendResult target, StatusDetail detail) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.reset();
    if (detail != null) detail.reset();
    if (!SqlMaterializedPagedByteStreamLifecycle.usable(state, detail)
        || source == null || recordIncrement < 0) {
      return SqlMaterializedPagedByteStreamLifecycle.invalidInput(detail);
    }
    int bytes = source.remaining();
    if (recordIncrement > Long.MAX_VALUE - state.publishedCount
        || bytes > Long.MAX_VALUE - state.logicalLength) {
      return SqlMaterializedPagedByteStreamLifecycle.fail(
          detail, StatusCode.RESOURCE_EXHAUSTED, "materialized stream length overflow");
    }
    if (state.fixedRecordBytes != 0
        && (bytes != state.fixedRecordBytes || recordIncrement != 1)) {
      return SqlMaterializedPagedByteStreamLifecycle.fail(
          detail, StatusCode.INVALID_EXTERNAL_INPUT, "materialized record length mismatch");
    }
    int sourceStart = source.position();
    state.copied = 0;
    state.position = state.logicalLength;
    state.mutated = false;
    StatusCode status = SqlMaterializedPagedByteStreamTransfer.append(
        state, source, sourceStart, bytes);
    if (!status.isOk()) {
      return !state.mutated
          ? SqlMaterializedPagedByteStreamFailure.terminal(state, status, detail)
          : SqlMaterializedPagedByteStreamFailure.terminalAfterMutation(
              state, status, detail);
    }
    long newCount = state.publishedCount + recordIncrement;
    long newLength = state.logicalLength + bytes;
    state.publishedCount = newCount;
    state.logicalLength = newLength;
    source.position(sourceStart + bytes);
    target.set(state.position - bytes, bytes, newLength, newCount);
    return StatusCode.OK;
  }
}
