package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Exact positioned I/O adapter for one retained materialized scratch channel. */
final class SqlMaterializedFilePageIo implements SqlMaterializedPageIo {
  private final long identity;
  private final FileChannel channel;

  SqlMaterializedFilePageIo(long fileIdentity, FileChannel fileChannel) {
    identity = fileIdentity;
    channel = fileChannel;
  }

  @Override
  public long fileIdentity() {
    return identity;
  }

  @Override
  public StatusCode read(long filePosition, ByteBuffer target) {
    target.clear();
    long position = filePosition;
    try {
      while (target.hasRemaining()) {
        int count = channel.read(target, position);
        if (count < 0) return StatusCode.CORRUPTION;
        if (count == 0) return StatusCode.IO_FAILURE;
        position += count;
      }
      target.flip();
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  @Override
  public StatusCode write(long filePosition, ByteBuffer source) {
    source.clear();
    long position = filePosition;
    try {
      while (source.hasRemaining()) {
        int count = channel.write(source, position);
        if (count <= 0) return StatusCode.IO_FAILURE;
        position += count;
      }
      source.clear();
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }
}
