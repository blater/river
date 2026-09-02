package io.riverdb.engine.table;

import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Primitive storage and retained comparison scratch for tuple intents. */
class IndexedTupleIntentStorage {
  final int maximumMutations;
  final int maximumPayloadBytes;
  final IndexedTupleIntentColumns columns = new IndexedTupleIntentColumns();
  final IndexedTupleIntentPayload payload = new IndexedTupleIntentPayload();
  final IndexedTupleIntentKeyIndex keyIndex = new IndexedTupleIntentKeyIndex();
  byte[] compareLeftBytes;
  byte[] compareRightBytes;
  ByteBuffer compareLeft;
  ByteBuffer compareRight;
  int count;
  int payloadBytes;

  IndexedTupleIntentStorage(int maximumMutations, int maximumPayloadBytes) {
    if (maximumMutations <= 0 || maximumPayloadBytes <= 0) {
      throw new IllegalArgumentException("tuple intent capacity must be positive");
    }
    this.maximumMutations = maximumMutations;
    this.maximumPayloadBytes = maximumPayloadBytes;
  }

  void ensureEntryCapacity(int required) { columns.reserve(required); }
  void ensurePayloadCapacity(int required) { payload.reserve(required); }
  void ensureKeyIndexCapacity(int required) {
    int needed = IndexedTupleIntentKeyIndex.capacityFor(required);
    if (needed <= keyIndexCapacity()) return;
    keyIndex.reserve(required);
    for (int index = 0; index < count; index++) {
      int slot = keyHashAt(index) & (needed - 1);
      while (indexAt(slot) >= 0) slot = (slot + 1) & (needed - 1);
      setIndex(slot, index);
    }
  }
  static int chunksFor(int values) { return IndexedTupleIntentColumns.chunksFor(values); }
  static int payloadChunksFor(int bytes) { return IndexedTupleIntentPayload.chunksFor(bytes); }
  static int indexCapacityFor(int values) { return IndexedTupleIntentKeyIndex.capacityFor(values); }
  int keyIndexCapacity() { return keyIndex.capacity(); }
  int keyIndexMask() { return keyIndex.mask(); }
  int offsetAt(int index) { return columns.offset(index); }
  int lengthAt(int index) { return columns.length(index); }
  int firstEntryAt(int index) { return columns.first(index); }
  int keyHashAt(int index) { return columns.hash(index); }
  boolean activeAtRaw(int index) { return columns.active(index); }
  int indexAt(int index) { return keyIndex.get(index); }
  void setIndex(int index, int value) { keyIndex.set(index, value); }
  void setOperation(int index, int value) { columns.operation(index, value); }
  void setDescriptor(int index, int value) { columns.descriptor(index, value); }
  void setOffset(int index, int value) { columns.offset(index, value); }
  void setLength(int index, int value) { columns.length(index, value); }
  void setFirstEntry(int index, int value) { columns.first(index, value); }
  void setKeyHash(int index, int value) { columns.hash(index, value); }
  void setLogicalRowId(int index, long value) { columns.rowId(index, value); }
  void setActive(int index, boolean value) { columns.active(index, value); }

  int copyToScratch(int index, byte[] target) {
    int length = lengthAt(index);
    payload.copyTo(offsetAt(index), length, target);
    return length;
  }
  void ensureCompareScratch() {
    if (compareLeftBytes != null) return;
    compareLeftBytes = new byte[TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES];
    compareRightBytes = new byte[TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES];
    compareLeft = ByteBuffer.wrap(compareLeftBytes);
    compareRight = ByteBuffer.wrap(compareRightBytes);
  }
  byte payloadAt(int offset) { return payload.get(offset); }
  int hash(int descriptor, long logicalRowId, int offset, int length) {
    int result = 0x811c9dc5;
    result = (result ^ descriptor) * 0x01000193;
    result = (result ^ (int) logicalRowId) * 0x01000193;
    result = (result ^ (int) (logicalRowId >>> 32)) * 0x01000193;
    for (int index = 0; index < length; index++) {
      result = (result ^ payloadAt(offset + index)) * 0x01000193;
    }
    return result;
  }
  void releaseStorage() {
    columns.release(); payload.release(); keyIndex.release();
    compareLeftBytes = compareRightBytes = null; compareLeft = compareRight = null;
    count = payloadBytes = 0;
  }
}
