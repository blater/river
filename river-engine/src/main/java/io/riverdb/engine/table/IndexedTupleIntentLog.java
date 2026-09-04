package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Primitive tuple-intent rows and a bounded, paged encoded-key arena. */
class IndexedTupleIntentLog extends IndexedTupleIntentView {

  IndexedTupleIntentLog() {
    this(IndexedTupleIntentJournal.MAX_MUTATIONS,
        Integer.MAX_VALUE);
  }

  IndexedTupleIntentLog(int maximumMutations, int maximumPayloadBytes) {
    super(maximumMutations, maximumPayloadBytes);
  }

  StatusCode reserve(int scalarCount, int additional, int additionalBytes) {
    if (scalarCount < 0 || additional < 0 || additionalBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (scalarCount > maximumMutations
        || additional > maximumMutations - scalarCount - count
        || additionalBytes > maximumPayloadBytes - payloadBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int required = count + additional;
    try {
      ensureCompareScratch();
      ensureEntryCapacity(required);
      ensurePayloadCapacity(payloadBytes + additionalBytes);
      ensureKeyIndexCapacity(required);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  long accountedBytesForReservation(int additional, int additionalBytes) {
    if (additional < 0 || additionalBytes < 0
        || additional > maximumMutations - count
        || additionalBytes > maximumPayloadBytes - payloadBytes) return -1;
    int required = count + additional;
    int requiredEntryChunks = chunksFor(required);
    int requiredPayloadChunks = payloadChunksFor(payloadBytes + additionalBytes);
    int requiredIndexCapacity = indexCapacityFor(required);
    if (requiredEntryChunks < 0 || requiredPayloadChunks < 0 || requiredIndexCapacity < 0) return -1;
    int entryChunks = Math.max(columns.chunks(), requiredEntryChunks);
    int payloadChunks = Math.max(payload.chunks(), requiredPayloadChunks);
    int indexCapacity = Math.max(keyIndexCapacity(), requiredIndexCapacity);
    long bytes = 9L * Integer.BYTES * entryChunks * IndexedTupleIntentColumns.SIZE;
    bytes += 2L * Long.BYTES * entryChunks * IndexedTupleIntentColumns.SIZE;
    bytes += (long) indexCapacity * Integer.BYTES;
    bytes += (long) payloadChunks * IndexedTupleIntentPayload.SIZE;
    long scratchBytes = 2L * TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES + 160L;
    return bytes + 9L * Integer.BYTES + 64L + scratchBytes;
  }

  void append(
      int operation, int descriptor, long logicalRowId,
      ByteBuffer key, int offset, int length) {
    payload.copyFrom(payloadBytes, key, offset, length);
    setOperation(count, operation);
    setDescriptor(count, descriptor);
    setLogicalRowId(count, logicalRowId);
    setOffset(count, payloadBytes);
    setLength(count, length);
    int hash = hash(descriptor, logicalRowId, payloadBytes, length);
    setKeyHash(count, hash);
    int first = count;
    int slot = hash & keyIndexMask();
    while (true) {
      int previous = indexAt(slot);
      if (previous < 0) {
        setIndex(slot, count);
        break;
      }
      if (keyHashAt(previous) == hash && sameKey(count, previous)) {
        first = firstEntryAt(previous);
      setActive(previous, false);
        setIndex(slot, count);
        break;
      }
      slot = (slot + 1) & keyIndexMask();
    }
    setFirstEntry(count, first);
    setActive(count, operationAt(first) == operation);
    payloadBytes += length;
    count++;
  }

  void truncate(int retained, int retainedBytes) {
    if (retained < 0 || retained > count
        || retainedBytes < 0 || retainedBytes > payloadBytes) return;
    for (int index = retained; index < count; index++) {
      setOperation(index, 0);
      setDescriptor(index, 0);
      setLogicalRowId(index, 0);
      setOffset(index, 0);
      setLength(index, 0);
      setFirstEntry(index, 0);
      setKeyHash(index, 0);
      setActive(index, false);
    }
    payload.clear(retainedBytes, payloadBytes);
    count = retained;
    payloadBytes = retainedBytes;
    rebuildKeyIndex();
  }

  private void rebuildKeyIndex() {
    if (keyIndexCapacity() == 0) return;
    for (int index = 0; index < keyIndexCapacity(); index++) setIndex(index, -1);
    for (int index = 0; index < count; index++) setActive(index, false);
    for (int index = 0; index < count; index++) {
      int first = index;
      int slot = keyHashAt(index) & keyIndexMask();
      while (true) {
        int previous = indexAt(slot);
        if (previous < 0) {
          setIndex(slot, index);
          break;
        }
        if (keyHashAt(previous) == keyHashAt(index) && sameKey(index, previous)) {
          first = firstEntryAt(previous);
          setActive(previous, false);
          setIndex(slot, index);
          break;
        }
        slot = (slot + 1) & keyIndexMask();
      }
      setFirstEntry(index, first);
      setActive(index, operationAt(first) == operationAt(index));
    }
  }

  void release() {
    releaseStorage();
  }
}
