package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reusable exact eight-byte I/O state for external row ordinals. */
final class SqlBlockRowOrdinalStream {
  private final ByteBuffer bytes = ByteBuffer.allocateDirect(Long.BYTES)
      .order(ByteOrder.BIG_ENDIAN);
  private final SqlMaterializedPagedByteStream.AppendResult append =
      new SqlMaterializedPagedByteStream.AppendResult();
  private final StatusDetail detail = new StatusDetail(128);

  StatusCode append(SqlMaterializedPagedByteStream stream, long value) {
    put(value);
    return stream.append(bytes, append, detail);
  }

  StatusCode write(
      SqlMaterializedPagedByteStream stream,
      long position,
      long value,
      boolean appendTarget) {
    return appendTarget ? append(stream, value) : overwrite(stream, position, value);
  }

  StatusCode overwrite(SqlMaterializedPagedByteStream stream, long position, long value) {
    if (!addressable(position)) return StatusCode.RESOURCE_EXHAUSTED;
    put(value);
    return stream.overwrite(position * Long.BYTES, bytes, detail);
  }

  StatusCode read(
      SqlMaterializedPagedByteStream stream, long position, Result target) {
    if (target == null || !addressable(position)) return StatusCode.CORRUPTION;
    bytes.clear();
    StatusCode status = stream.read(position * Long.BYTES, bytes, detail);
    if (status.isOk()) {
      bytes.flip();
      target.value = bytes.getLong();
    }
    return status;
  }

  private void put(long value) {
    bytes.clear();
    bytes.putLong(value);
    bytes.flip();
  }

  private static boolean addressable(long position) {
    return position >= 0 && position <= Long.MAX_VALUE / Long.BYTES;
  }

  static final class Result { long value; long value() { return value; } }
}
