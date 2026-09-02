package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Primitive mutation metadata plus one retained, exact variable-payload arena. */
final class IndexedRelationalMutationEntries {
  private final IndexedIntChunks operations;
  private final IndexedIntChunks descriptorOrdinals;
  private final IndexedIntChunks suboperationOrdinals;
  private final IndexedIntChunks payloadOffsets;
  private final IndexedIntChunks payloadLengths;
  private final IndexedLongChunks logicalRowIds;
  private final IndexedLongChunks previousRowIds;
  private final IndexedLongChunks ownerObjectIds;
  private final IndexedLongChunks spaces;
  private final IndexedByteChunks payload;
  private int payloadBytes;
  private int count;

  IndexedRelationalMutationEntries(int capacity, int payloadCapacity) {
    operations = new IndexedIntChunks(capacity);
    descriptorOrdinals = new IndexedIntChunks(capacity);
    suboperationOrdinals = new IndexedIntChunks(capacity);
    payloadOffsets = new IndexedIntChunks(capacity);
    payloadLengths = new IndexedIntChunks(capacity);
    logicalRowIds = new IndexedLongChunks(capacity);
    previousRowIds = new IndexedLongChunks(capacity);
    ownerObjectIds = new IndexedLongChunks(capacity);
    spaces = new IndexedLongChunks(capacity);
    payload = new IndexedByteChunks(payloadCapacity);
  }

  StatusCode reserve(int additional, int additionalPayloadBytes) {
    if (additional < 0 || additionalPayloadBytes < 0
        || additional > operations.capacity() - count
        || payloadBytes > Integer.MAX_VALUE - additionalPayloadBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int required = count + additional;
    StatusCode status = operations.reserve(required);
    if (status.isOk()) status = descriptorOrdinals.reserve(required);
    if (status.isOk()) status = suboperationOrdinals.reserve(required);
    if (status.isOk()) status = payloadOffsets.reserve(required);
    if (status.isOk()) status = payloadLengths.reserve(required);
    if (status.isOk()) status = logicalRowIds.reserve(required);
    if (status.isOk()) status = previousRowIds.reserve(required);
    if (status.isOk()) status = ownerObjectIds.reserve(required);
    if (status.isOk()) status = spaces.reserve(required);
    if (!status.isOk()) return status;
    return payload.reserve(payloadBytes + additionalPayloadBytes);
  }

  void append(
      int operation,
      int descriptorOrdinal,
      int suboperationOrdinal,
      long ownerObjectId,
      long space,
      long logicalRowId,
      long previousRowId,
      ByteBuffer source,
      int sourceOffset,
      int length) {
    for (int index = 0; index < length; index++) {
      payload.set(payloadBytes + index, source.get(sourceOffset + index));
    }
    operations.set(count, operation);
    descriptorOrdinals.set(count, descriptorOrdinal);
    suboperationOrdinals.set(count, suboperationOrdinal);
    ownerObjectIds.set(count, ownerObjectId);
    spaces.set(count, space);
    logicalRowIds.set(count, logicalRowId);
    previousRowIds.set(count, previousRowId);
    payloadOffsets.set(count, payloadBytes);
    payloadLengths.set(count, length);
    payloadBytes += length;
    count++;
  }

  boolean canAppend(int length) {
    return length >= 0 && count < operations.capacity()
        && payloadBytes <= Integer.MAX_VALUE - length
        && payloadBytes + length <= payload.allocatedBytes()
        && count < operations.allocatedCapacity();
  }

  void copyPayloadTo(int mutation, ByteBuffer target, int targetOffset) {
    payload.copyTo(payloadOffsets.get(mutation), target, targetOffset, payloadLengths.get(mutation));
  }

  void reset() { count = 0; payloadBytes = 0; }
  long accountedBytes() {
    return operations.allocatedBytes() + descriptorOrdinals.allocatedBytes()
        + suboperationOrdinals.allocatedBytes() + payloadOffsets.allocatedBytes()
        + payloadLengths.allocatedBytes() + logicalRowIds.allocatedBytes()
        + previousRowIds.allocatedBytes() + ownerObjectIds.allocatedBytes()
        + spaces.allocatedBytes() + payload.retainedBytes() + 64L;
  }
  long accountedBytesForReservation(int additional, int additionalPayloadBytes) {
    if (additional < 0 || additionalPayloadBytes < 0
        || additional > operations.capacity() - count
        || additionalPayloadBytes > Integer.MAX_VALUE - payloadBytes) return -1;
    int required = count + additional;
    long requiredPayload = (long) payloadBytes + additionalPayloadBytes;
    if (requiredPayload > Integer.MAX_VALUE) return -1;
    long intBytes = operations.accountedBytesForCapacity(required);
    long longBytes = logicalRowIds.accountedBytesForCapacity(required);
    long payloadBytesRequired = payload.retainedBytesForCapacity((int) requiredPayload);
    if (intBytes < 0 || longBytes < 0 || payloadBytesRequired < 0) return -1;
    return Math.max(accountedBytes(), 5L * intBytes + 4L * longBytes
        + payloadBytesRequired + 64L);
  }
  int capacity() { return operations.capacity(); }
  int count() { return count; }
  int payloadBytes() { return payloadBytes; }
  int operationAt(int index) { return operations.get(index); }
  int descriptorOrdinalAt(int index) { return descriptorOrdinals.get(index); }
  int suboperationOrdinalAt(int index) { return suboperationOrdinals.get(index); }
  int payloadLengthAt(int index) { return payloadLengths.get(index); }
  long logicalRowIdAt(int index) { return logicalRowIds.get(index); }
  long previousRowIdAt(int index) { return previousRowIds.get(index); }
  long ownerObjectIdAt(int index) { return ownerObjectIds.get(index); }
  long spaceAt(int index) { return spaces.get(index); }
  byte payloadByteAt(int mutation, int index) {
    return payload.get(payloadOffsets.get(mutation) + index);
  }

  void release() {
    operations.release();
    descriptorOrdinals.release();
    suboperationOrdinals.release();
    payloadOffsets.release();
    payloadLengths.release();
    logicalRowIds.release();
    previousRowIds.release();
    ownerObjectIds.release();
    spaces.release();
    payload.release();
    count = payloadBytes = 0;
  }
}
