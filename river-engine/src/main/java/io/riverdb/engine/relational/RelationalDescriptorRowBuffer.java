package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable direct encoding buffer for one descriptor-row access session. */
final class RelationalDescriptorRowBuffer {
  private static final int INITIAL_BYTES = 256;
  private final StoredTableRowDecoder decoder = new StoredTableRowDecoder();
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
    StatusCode status = StoredTableRowEncoder.encode(
        table, logicalRowId, values, bytes, 0, encoded);
    if (status.isOk()) bytes.position(0).limit(encoded.length());
    return status;
  }

  StatusCode decode(
      TableDescriptor table,
      long logicalRowId,
      HeapRowResult source,
      SqlValueBuffer destination) {
    return decode(table, logicalRowId, source, destination, null);
  }

  StatusCode decode(
      TableDescriptor table,
      long logicalRowId,
      HeapRowResult source,
      SqlValueBuffer destination,
      StoredTableRowFilter filter) {
    return decode(table, logicalRowId, source, destination, filter, true);
  }

  StatusCode decode(
      TableDescriptor table,
      long logicalRowId,
      HeapRowResult source,
      SqlValueBuffer destination,
      StoredTableRowFilter filter,
      boolean publishText) {
    bytes.clear();
    StatusCode status = source.copyTo(bytes);
    if (!status.isOk()) return StatusCode.CORRUPTION;
    bytes.flip();
    return decoder.decode(
        table, logicalRowId, bytes, 0, source.length(), destination, filter, publishText);
  }

  ByteBuffer bytes() { return bytes; }
  int length() { return encoded.length(); }

}
