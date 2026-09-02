package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Checked long-offset traversal; one page operation owns each pin lifetime. */
final class SqlMaterializedPagedByteStreamTransfer {
  private SqlMaterializedPagedByteStreamTransfer() {}

  static StatusCode append(
      SqlMaterializedPagedByteStreamState state, ByteBuffer source, int sourceStart, int bytes) {
    while (state.copied < bytes) {
      StatusCode status = SqlMaterializedPageMapping.map(
          state.position, state.pageBytes, state.location);
      if (!status.isOk()) return status;
      boolean fresh = state.position == 0 || state.position == pageStart(state);
      status = SqlMaterializedPagedByteStreamPageTransfer.append(
          state, source, sourceStart, bytes, fresh);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  static StatusCode read(
      SqlMaterializedPagedByteStreamState state, ByteBuffer target, int targetStart, int bytes) {
    while (state.copied < bytes) {
      StatusCode status = SqlMaterializedPageMapping.map(
          state.position, state.pageBytes, state.location);
      if (!status.isOk()) return status;
      status = SqlMaterializedPagedByteStreamPageTransfer.read(state, target, targetStart, bytes);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  static StatusCode overwrite(
      SqlMaterializedPagedByteStreamState state, ByteBuffer source, int sourceStart, int bytes) {
    while (state.copied < bytes) {
      StatusCode status = SqlMaterializedPageMapping.map(
          state.position, state.pageBytes, state.location);
      if (!status.isOk()) return status;
      status = SqlMaterializedPagedByteStreamPageTransfer.overwrite(
          state, source, sourceStart, bytes);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static long pageStart(SqlMaterializedPagedByteStreamState state) {
    long page = state.position / state.payloadBytes;
    return page * state.payloadBytes;
  }
}
