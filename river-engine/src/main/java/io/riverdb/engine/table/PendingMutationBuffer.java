package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/**
 * Fixed-count primitive mutation set and bounded growing row arena owned by one session.
 * Store consumes it synchronously while table admission and COMMITTING prevent mutation.
 */
final class PendingMutationBuffer {
  private static final int MUTATION_NONE = 0;

  private final PendingRowArena rows;
  private final PendingMutationMetadata metadata;
  private final PendingMutationLatestIndex latest;
  private final int rowStride;
  private int count;
  private int payloadBytes;

  PendingMutationBuffer(int capacity, int maximumRowBytes) {
    this(capacity, maximumRowBytes, PendingRowChunkAllocator.HEAP);
  }

  PendingMutationBuffer(
      int capacity,
      int maximumRowBytes,
      PendingRowChunkAllocator rowChunkAllocator) {
    metadata = new PendingMutationMetadata(capacity);
    latest = new PendingMutationLatestIndex(capacity);
    rows = new PendingRowArena(capacity, maximumRowBytes, rowChunkAllocator);
    rowStride = maximumRowBytes;
  }

  int capacity() {
    return metadata.capacity();
  }

  int count() {
    return count;
  }

  int rowStride() {
    return rowStride;
  }

  int payloadBytes() { return payloadBytes; }

  long accountedBytesForReservation(int additionalRows, int additionalRowBytes) {
    if (additionalRows != 1 || additionalRows > capacity() - count) return -1;
    long rowBytes = rows.accountedBytesForRow(additionalRowBytes);
    long indexBytes = latest.accountedBytesForEntries(count + 1);
    return rowBytes < 0 || indexBytes < 0 ? -1
        : metadata.accountedBytesForCount(count + 1) + rowBytes + indexBytes;
  }

  long accountedBytesForReservation(int[] rowLengths, int start, int additionalRows) {
    if (additionalRows <= 0 || additionalRows > capacity() - count) return -1;
    long rowBytes = rows.accountedBytesForRows(rowLengths, start, additionalRows);
    long indexBytes = latest.accountedBytesForEntries(count + additionalRows);
    return rowBytes < 0 || indexBytes < 0 ? -1
        : metadata.accountedBytesForCount(count + additionalRows) + rowBytes + indexBytes;
  }

  long accountedBytes() {
    return metadata.accountedBytesForCount(count) + rows.accountedBytes()
        + latest.accountedBytes();
  }

  void release() {
    truncate(0);
    metadata.release();
    latest.release();
    rows.release();
    payloadBytes = 0;
  }

  int operationAt(int index) {
    return metadata.operationAt(index);
  }

  long keyAt(int index) {
    return metadata.keyAt(index);
  }

  long spaceAt(int index) {
    return metadata.spaceAt(index);
  }

  long previousRowIdAt(int index) {
    return metadata.previousRowIdAt(index);
  }

  int rowLengthAt(int index) {
    return metadata.rowLengthAt(index);
  }

  StatusCode reserve(int additionalRows, int additionalRowBytes) {
    if (additionalRows <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (additionalRows > capacity() - count) return StatusCode.RESOURCE_EXHAUSTED;
    if (additionalRows != 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = metadata.reserve(count, additionalRows);
    if (status.isOk()) status = latest.reserve(count + additionalRows);
    return status.isOk() ? rows.reserveRow(additionalRowBytes) : status;
  }

  StatusCode reserve(int[] rowLengths, int start, int additionalRows) {
    if (additionalRows <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (additionalRows > capacity() - count) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = metadata.reserve(count, additionalRows);
    if (status.isOk()) status = latest.reserve(count + additionalRows);
    return status.isOk() ? rows.reserveRows(rowLengths, start, additionalRows) : status;
  }

  void appendDeletion(int operation, long space, long key, long previousRowId) {
    metadata.set(
        count, operation, space, key, previousRowId, rows.appendDeletion(), 1);
    latest.put(space, key, count);
    count++;
    payloadBytes++;
  }

  void append(
      int operation,
      long space,
      long key,
      long previousRowId,
      ByteBuffer source,
      int sourceStart,
      int rowBytes) {
    metadata.set(
        count, operation, space, key, previousRowId,
        rows.append(source, sourceStart, rowBytes), rowBytes);
    latest.put(space, key, count);
    count++;
    payloadBytes += rowBytes;
  }

  void copyRowTo(int index, ByteBuffer target, int targetOffset) {
    rows.copyTo(
        metadata.rowOffsetAt(index), metadata.rowLengthAt(index), target, targetOffset);
  }

  StatusCode insertRowInto(int index, ByteBuffer heap, HeapInsertResult result) {
    return rows.insertInto(
        metadata.rowOffsetAt(index), metadata.rowLengthAt(index), heap, result);
  }

  /** Borrow remains valid only until this owner next appends, compacts, or truncates/reuses. */
  void setRowResult(int index, HeapRowResult result) {
    rows.setResult(metadata.rowOffsetAt(index), metadata.rowLengthAt(index), result);
  }

  boolean containsNonInsertMutation() {
    for (int index = 0; index < count; index++) {
      if (metadata.operationAt(index) != IndexedWalCodec.MUTATION_INSERT
          || metadata.previousRowIdAt(index) != 0) {
        return true;
      }
    }
    return false;
  }

  int findLatestIndex(long space, long key) {
    return latest.find(space, key);
  }

  int nextIndex(IndexedScanCursor cursor) {
    return latest.next(cursor);
  }

  void truncate(int first) {
    int retainedBytes = first < count ? metadata.rowOffsetAt(first) : rows.endOffset();
    for (int index = first; index < count; index++) {
      payloadBytes -= metadata.rowLengthAt(index);
      metadata.clear(index);
    }
    rows.truncateTo(retainedBytes);
    count = first;
    latest.rebuild(metadata, count);
  }

  void compact() {
    int originalCount = count;
    for (int index = 0; index < originalCount; index++) {
      metadata.retain(index,
          latest.find(metadata.spaceAt(index), metadata.keyAt(index)) == index
              && metadata.operationAt(index) != MUTATION_NONE);
    }
    int output = 0;
    int compactedPayloadBytes = 0;
    rows.beginCompaction();
    for (int index = 0; index < originalCount; index++) {
      if (!metadata.retainedAt(index)) {
        continue;
      }
      int rowBytes = metadata.rowLengthAt(index);
      int compactedOffset = rows.compactRow(metadata.rowOffsetAt(index), rowBytes);
      metadata.copy(index, output, compactedOffset);
      compactedPayloadBytes += rowBytes;
      output++;
    }
    for (int index = 0; index < originalCount; index++) {
      metadata.retain(index, false);
      if (index >= output) {
        metadata.clear(index);
      }
    }
    rows.finishCompaction();
    count = output;
    payloadBytes = compactedPayloadBytes;
    latest.rebuild(metadata, count);
  }
}
