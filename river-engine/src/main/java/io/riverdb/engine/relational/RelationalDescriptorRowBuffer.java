package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.row.StoredTableRowCodec;
import io.riverdb.engine.row.StoredTableRowEncodeResult;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.format.row.StoredTableRowHeaderCodec;
import java.nio.ByteBuffer;

/** Reusable direct encoding buffer for one descriptor-row access session. */
final class RelationalDescriptorRowBuffer {
  private static final int INITIAL_BYTES = 256;
  private final StoredTableRowCodec codec = new StoredTableRowCodec();
  private final StoredTableRowEncodeResult encoded = new StoredTableRowEncodeResult();
  private ByteBuffer bytes = ByteBuffer.allocateDirect(INITIAL_BYTES);

  StatusCode reserve(int requested) {
    if (requested <= bytes.capacity()) return StatusCode.OK;
    if (requested <= 0 || requested > TableSchema.MAXIMUM_ROW_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int capacity = bytes.capacity();
    while (capacity < requested) capacity = Math.min(
        TableSchema.MAXIMUM_ROW_BYTES, capacity << 1);
    try {
      bytes = ByteBuffer.allocateDirect(capacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode encode(
      TableDescriptor table, long logicalRowId, SqlValueBuffer values) {
    bytes.clear();
    StatusCode status = codec.encode(
        table, logicalRowId, values, bytes, 0, encoded);
    if (status.isOk()) bytes.position(0).limit(encoded.length());
    return status;
  }

  StatusCode decode(
      TableDescriptor table,
      long logicalRowId,
      HeapRowResult source,
      SqlValueBuffer destination) {
    bytes.clear();
    StatusCode status = source.copyTo(bytes);
    if (!status.isOk()) return StatusCode.CORRUPTION;
    bytes.flip();
    return codec.decode(table, logicalRowId, bytes, 0, source.length(), destination);
  }

  ByteBuffer bytes() { return bytes; }
  int length() { return encoded.length(); }

  long contentFingerprint() {
    long hash = 0xcbf29ce484222325L;
    int logicalIdStart = StoredTableRowHeaderCodec.HEADER_BYTES - Long.BYTES;
    for (int index = 0; index < encoded.length(); index++) {
      if (index >= logicalIdStart && index < StoredTableRowHeaderCodec.HEADER_BYTES) continue;
      hash ^= Byte.toUnsignedLong(bytes.get(index));
      hash *= 0x100000001b3L;
    }
    return hash;
  }
}
