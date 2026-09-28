package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Checks stored-row bounds before publishing trusted values using the table layout. */
final class StoredTableRowDecoder {
  private StoredTableRowDecoder() {
  }

  static StatusCode decode(
      TableDescriptor table,
      ByteBuffer source,
      int start,
      int length,
      SqlValueBuffer destination,
      StoredTableRowIntegerFilter filter,
      StoredTableColumnSelection selection) {
    if (!validArguments(table, source, start, length, destination)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (length > HeapPage.MAXIMUM_ROW_BYTES
        || start > source.limit() - length) {
      return StatusCode.CORRUPTION;
    }
    if (!StoredTableRowBounds.fixedPrefix(table, length)) return StatusCode.CORRUPTION;
    if (destination.capacity() < table.columnCount()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (filter != null) {
      StatusCode status = filter.test(table, source, start);
      if (!status.isOk()) return status;
    }
    int textBytes = StoredTableRowBounds.publishedTextBytes(
        table, source, start, length, selection);
    if (textBytes < 0) return StatusCode.CORRUPTION;
    if (destination.textCapacity() < textBytes
        || destination.textMaximumBytes() < textBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return StoredTableRowPublisher.publish(table, source, start, destination, selection);
  }

  private static boolean validArguments(
      TableDescriptor table, ByteBuffer source, int start, int length,
      SqlValueBuffer destination) {
    return table != null && source != null
        && destination != null && start >= 0 && start <= source.limit() && length >= 0;
  }
}
