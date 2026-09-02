package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.io.IOException;
import java.nio.channels.FileChannel;

/** Exact positioned I/O for the unpaged 64-byte stream header. */
final class SqlMaterializedPagedByteStreamHeader {
  private SqlMaterializedPagedByteStreamHeader() {}

  static StatusCode read(SqlMaterializedPagedByteStreamState state, StatusDetail detail) {
    StatusCode status = transfer(state, false);
    if (!status.isOk()) return SqlMaterializedPagedByteStreamLifecycle.fail(
        detail, status, "cannot read materialized stream header");
    return SqlMaterializedScratchFileCodec.validateFileHeader(
        state.headerBuffer, state.kind, state.pageBytes, state.file.fileIdentity(),
        state.header, detail);
  }

  static StatusCode write(SqlMaterializedPagedByteStreamState state, StatusDetail detail) {
    StatusCode status = transfer(state, true);
    return status.isOk() ? status : SqlMaterializedPagedByteStreamLifecycle.fail(
        detail, status, "cannot write materialized stream header");
  }

  private static StatusCode transfer(SqlMaterializedPagedByteStreamState state, boolean write) {
    FileChannel channel = state.file.channel();
    if (channel == null) return StatusCode.CLOSED;
    state.headerBuffer.clear();
    try {
      long position = 0;
      while (state.headerBuffer.hasRemaining()) {
        int count = write
            ? channel.write(state.headerBuffer, position)
            : channel.read(state.headerBuffer, position);
        if (count < 0) return StatusCode.CORRUPTION;
        if (count == 0) return StatusCode.IO_FAILURE;
        position += count;
      }
      if (write) state.headerBuffer.clear();
      else state.headerBuffer.flip();
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }
}
