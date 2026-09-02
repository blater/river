package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.format.row.StoredTableRowHeaderCodec;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Lazily grown bounded copy buffer separating borrowed page pins from row decoding. */
final class RelationalDescriptorRowBytesValidation {
  private ByteBuffer bytes;

  StatusCode copy(HeapRowResult row) {
    int length = row.length();
    if (length < StoredTableRowHeaderCodec.HEADER_BYTES
        || length > SqlShapeLimits.MAX_STORED_ROW_BYTES) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = reserve(length);
    if (!status.isOk()) return status;
    bytes.clear();
    status = row.copyTo(bytes);
    if (!status.isOk()) return StatusCode.CORRUPTION;
    bytes.flip();
    return StatusCode.OK;
  }

  ByteBuffer value() {
    return bytes;
  }

  private StatusCode reserve(int requested) {
    if (bytes != null && requested <= bytes.capacity()) return StatusCode.OK;
    int capacity = bytes == null ? 256 : bytes.capacity();
    while (capacity < requested) capacity = Math.min(
        SqlShapeLimits.MAX_STORED_ROW_BYTES, capacity << 1);
    try {
      bytes = ByteBuffer.allocateDirect(capacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
