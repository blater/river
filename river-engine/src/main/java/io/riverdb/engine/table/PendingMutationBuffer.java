package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/**
 * Fixed-capacity primitive mutation set and direct row arena owned by one session.
 * Store consumes it synchronously while table admission and COMMITTING prevent mutation.
 */
final class PendingMutationBuffer {
  private static final int MUTATION_NONE = 0;

  private final ByteBuffer rows;
  private final int[] operations;
  private final long[] keys;
  private final int[] spaces;
  private final int[] previousRowIds;
  private final int[] rowLengths;
  private final boolean[] retained;
  private final int rowStride;
  private int count;

  PendingMutationBuffer(int capacity, int maximumRowBytes) {
    rows = ByteBuffer.allocateDirect(capacity * maximumRowBytes);
    operations = new int[capacity];
    keys = new long[capacity];
    spaces = new int[capacity];
    previousRowIds = new int[capacity];
    rowLengths = new int[capacity];
    retained = new boolean[capacity];
    rowStride = maximumRowBytes;
  }

  int capacity() {
    return keys.length;
  }

  int count() {
    return count;
  }

  int rowStride() {
    return rowStride;
  }

  int operationAt(int index) {
    return operations[index];
  }

  long keyAt(int index) {
    return keys[index];
  }

  int spaceAt(int index) {
    return spaces[index];
  }

  int previousRowIdAt(int index) {
    return previousRowIds[index];
  }

  int rowLengthAt(int index) {
    return rowLengths[index];
  }

  void appendDeletion(int operation, int space, long key, int previousRowId) {
    int destinationStart = count * rowStride;
    rows.limit(rows.capacity());
    rows.put(destinationStart, (byte) 0);
    operations[count] = operation;
    spaces[count] = space;
    keys[count] = key;
    previousRowIds[count] = previousRowId;
    rowLengths[count] = 1;
    count++;
  }

  void append(
      int operation,
      int space,
      long key,
      int previousRowId,
      ByteBuffer source,
      int sourceStart,
      int rowBytes) {
    int destinationStart = count * rowStride;
    rows.limit(rows.capacity());
    for (int index = 0; index < rowBytes; index++) {
      rows.put(destinationStart + index, source.get(sourceStart + index));
    }
    operations[count] = operation;
    spaces[count] = space;
    keys[count] = key;
    previousRowIds[count] = previousRowId;
    rowLengths[count] = rowBytes;
    count++;
  }

  void copyRowTo(int index, ByteBuffer target, int targetOffset) {
    int sourceOffset = index * rowStride;
    int rowBytes = rowLengths[index];
    for (int byteIndex = 0; byteIndex < rowBytes; byteIndex++) {
      target.put(targetOffset + byteIndex, rows.get(sourceOffset + byteIndex));
    }
  }

  StatusCode insertRowInto(int index, ByteBuffer heap, HeapInsertResult result) {
    return HeapPage.insertFrom(
        heap, rows, index * rowStride, rowLengths[index], result);
  }

  /** Borrow remains valid only until this owner next appends, compacts, or truncates/reuses. */
  void setRowResult(int index, HeapRowResult result) {
    result.set(rows, 0, index * rowStride, rowLengths[index]);
  }

  boolean containsNonInsertMutation() {
    for (int index = 0; index < count; index++) {
      if (operations[index] != IndexedWalCodec.MUTATION_INSERT
          || previousRowIds[index] != 0) {
        return true;
      }
    }
    return false;
  }

  int findLatestIndex(int space, long key) {
    for (int index = count - 1; index >= 0; index--) {
      if (spaces[index] == space && keys[index] == key) {
        return index;
      }
    }
    return -1;
  }

  int nextIndex(IndexedScanCursor cursor) {
    int selected = -1;
    int selectedSpace = 0;
    long selectedKey = 0;
    for (int index = 0; index < count; index++) {
      long key = keys[index];
      int space = spaces[index];
      if (findLatestIndex(space, key) == index
          && cursor.contains(space, key)
          && cursor.afterLastReturned(space, key)
          && (selected < 0
              || OrderedKey.lessThan(space, key, selectedSpace, selectedKey))) {
        selected = index;
        selectedSpace = space;
        selectedKey = key;
      }
    }
    return selected;
  }

  void truncate(int first) {
    for (int index = first; index < count; index++) {
      keys[index] = 0;
      spaces[index] = 0;
      operations[index] = 0;
      previousRowIds[index] = 0;
      rowLengths[index] = 0;
    }
    count = first;
  }

  void compact() {
    int originalCount = count;
    for (int index = 0; index < originalCount; index++) {
      boolean latest = true;
      for (int later = index + 1; later < originalCount; later++) {
        if (spaces[later] == spaces[index] && keys[later] == keys[index]) {
          latest = false;
          break;
        }
      }
      retained[index] = latest && operations[index] != MUTATION_NONE;
    }
    int output = 0;
    for (int index = 0; index < originalCount; index++) {
      if (!retained[index]) {
        continue;
      }
      if (output != index) {
        int sourceOffset = index * rowStride;
        int targetOffset = output * rowStride;
        int rowBytes = rowLengths[index];
        for (int byteIndex = 0; byteIndex < rowBytes; byteIndex++) {
          rows.put(targetOffset + byteIndex, rows.get(sourceOffset + byteIndex));
        }
        operations[output] = operations[index];
        spaces[output] = spaces[index];
        keys[output] = keys[index];
        previousRowIds[output] = previousRowIds[index];
        rowLengths[output] = rowBytes;
      }
      output++;
    }
    for (int index = 0; index < originalCount; index++) {
      retained[index] = false;
      if (index >= output) {
        operations[index] = 0;
        keys[index] = 0;
        spaces[index] = 0;
        previousRowIds[index] = 0;
        rowLengths[index] = 0;
      }
    }
    count = output;
  }
}
