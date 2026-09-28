package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.row.StoredTableRowHeader;
import io.riverdb.format.row.StoredTableRowHeaderCodec;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Checks stored-row identity and bounds before publishing trusted values. */
final class StoredTableRowDecoder {
  private final StoredTableRowHeader header = new StoredTableRowHeader();

  StatusCode decode(
      TableDescriptor table,
      long expectedLogicalRowId,
      ByteBuffer source,
      int start,
      int length,
      SqlValueBuffer destination,
      StoredTableRowIntegerFilter filter,
      boolean publishText) {
    if (!validArguments(table, expectedLogicalRowId, source, start, length, destination)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (length < StoredTableRowHeaderCodec.HEADER_BYTES
        || length > HeapPage.MAXIMUM_ROW_BYTES
        || start > source.limit() - length) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = StoredTableRowHeaderCodec.decode(
        source, start, expectedLogicalRowId, header);
    if (!status.isOk() || header.rowLayoutId() != table.rowLayoutId()) {
      return StatusCode.CORRUPTION;
    }
    if (!StoredTableRowBounds.fixedPrefix(table, length)) return StatusCode.CORRUPTION;
    if (destination.capacity() < table.columnCount()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (filter != null) {
      status = filter.test(table, source, start);
      if (!status.isOk()) return status;
    }
    int textBytes = publishText
        ? StoredTableRowBounds.publishedTextBytes(table, source, start, length) : 0;
    if (textBytes < 0) return StatusCode.CORRUPTION;
    if (destination.textCapacity() < textBytes
        || destination.textMaximumBytes() < textBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return StoredTableRowPublisher.publish(table, source, start, destination, publishText);
  }

  private static boolean validArguments(
      TableDescriptor table, long rowId, ByteBuffer source, int start, int length,
      SqlValueBuffer destination) {
    return table != null && table.rowLayoutId() > 0 && rowId > 0 && source != null
        && destination != null && start >= 0 && start <= source.limit() && length >= 0;
  }
}
