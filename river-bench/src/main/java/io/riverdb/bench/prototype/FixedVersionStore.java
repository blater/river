package io.riverdb.bench.prototype;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.riverdb.base.error.StatusCode;

/** Fixed-layout, append-only version store alternative for measurement only. */
public final class FixedVersionStore {
  static final int RECORD_BYTES = 40;
  private static final int ROW_ID_OFFSET = 0;
  private static final int BEGIN_OFFSET = 8;
  private static final int END_OFFSET = 16;
  private static final int VALUE_OFFSET = 24;
  private static final int FLAGS_OFFSET = 32;

  private final int capacity;
  private final ByteBuffer storage;
  private final int[] selection;
  private int size;
  private int selectionCount;
  private long encodedBytes;
  private long copiedBytes;

  public FixedVersionStore(int capacity) {
    this.capacity = capacity;
    storage = ByteBuffer.allocateDirect(Math.multiplyExact(capacity, RECORD_BYTES))
      .order(ByteOrder.LITTLE_ENDIAN);
    selection = new int[capacity];
  }

  public StatusCode append(
      long rowId,
      long beginSequence,
      long endSequence,
      long value,
      long flags
  ) {
    if (size == capacity) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int offset = size * RECORD_BYTES;
    storage.putLong(offset + ROW_ID_OFFSET, rowId);
    storage.putLong(offset + BEGIN_OFFSET, beginSequence);
    storage.putLong(offset + END_OFFSET, endSequence);
    storage.putLong(offset + VALUE_OFFSET, value);
    storage.putLong(offset + FLAGS_OFFSET, flags);
    size++;
    encodedBytes += RECORD_BYTES;
    return StatusCode.OK;
  }

  public int scanVisible(long snapshotSequence) {
    int selected = 0;
    for (int record = 0; record < size; record++) {
      int offset = record * RECORD_BYTES;
      long begin = storage.getLong(offset + BEGIN_OFFSET);
      long end = storage.getLong(offset + END_OFFSET);
      if (begin <= snapshotSequence && (end == 0L || snapshotSequence < end)) {
        selection[selected++] = record;
      }
    }
    selectionCount = selected;
    return selected;
  }

  public StatusCode read(int record, VersionRecord target) {
    if (record < 0 || record >= size) {
      return StatusCode.INVARIANT_BROKEN;
    }
    int offset = record * RECORD_BYTES;
    target.rowId = storage.getLong(offset + ROW_ID_OFFSET);
    target.beginSequence = storage.getLong(offset + BEGIN_OFFSET);
    target.endSequence = storage.getLong(offset + END_OFFSET);
    target.value = storage.getLong(offset + VALUE_OFFSET);
    target.flags = storage.getLong(offset + FLAGS_OFFSET);
    return StatusCode.OK;
  }

  public void clear() {
    size = 0;
    selectionCount = 0;
  }

  public long sumVisibleValues() {
    long sum = 0L;
    for (int index = 0; index < selectionCount; index++) {
      int offset = selection[index] * RECORD_BYTES;
      sum += storage.getLong(offset + VALUE_OFFSET);
    }
    return sum;
  }

  public long selectedRowId(int index) {
    return storage.getLong(selection[index] * RECORD_BYTES + ROW_ID_OFFSET);
  }

  public int size() {
    return size;
  }

  public long encodedBytes() {
    return encodedBytes;
  }

  public long copiedBytes() {
    return copiedBytes;
  }
}
