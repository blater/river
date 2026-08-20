package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Owns one bounded encoded outer row while an inner JOIN cursor advances. */
final class SqlJoinOuterRow {
  private final HeapRowResult row = new HeapRowResult();
  private ByteBuffer bytes;
  private int highWater;

  StatusCode capture(HeapRowResult source) {
    if (source == null || source.length() < 0
        || source.length() > TableSchema.MAXIMUM_ROW_BYTES) {
      return StatusCode.CORRUPTION;
    }
    if (bytes == null) bytes = ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
    erase();
    bytes.clear();
    bytes.limit(source.length());
    highWater = source.length();
    StatusCode status = source.copyTo(bytes);
    if (status.isOk()) {
      bytes.flip();
      row.set(bytes, source.rowId(), 0, source.length());
    } else {
      reset();
    }
    return status;
  }

  HeapRowResult row() { return row; }

  void reset() {
    erase();
    row.reset();
  }

  private void erase() {
    if (bytes != null) {
      for (int index = 0; index < highWater; index++) bytes.put(index, (byte) 0);
    }
    highWater = 0;
  }
}
