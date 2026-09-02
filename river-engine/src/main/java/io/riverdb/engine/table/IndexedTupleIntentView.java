package io.riverdb.engine.table;

import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import java.nio.ByteBuffer;

/** Query and comparison view over retained tuple intents. */
class IndexedTupleIntentView extends IndexedTupleIntentStorage {
  IndexedTupleIntentView(int maximumMutations, int maximumPayloadBytes) {
    super(maximumMutations, maximumPayloadBytes);
  }

  int activeCount() {
    int activeCount = 0;
    for (int index = 0; index < count; index++) if (activeAt(index)) activeCount++;
    return activeCount;
  }
  boolean activeAt(int index) { return index >= 0 && index < count && activeAtRaw(index); }
  boolean hasActiveInsertPrefix(
      int descriptor, ByteBuffer key, int offset, int length, int parts, long excludedRowId) {
    for (int index = 0; index < count; index++) {
      if (activeAt(index) && operationAt(index) == IndexedRelationalMutation.TUPLE_INSERT
          && descriptorAt(index) == descriptor && logicalRowIdAt(index) != excludedRowId
          && comparePrefix(index, key, offset, length, parts) == 0) return true;
    }
    return false;
  }
  boolean hasAppendOnlyInsertPrefix(
      int descriptor, ByteBuffer key, int offset, int length, int parts, long excludedRowId) {
    for (int index = 0; index < count; index++) {
      if (operationAt(index) != IndexedRelationalMutation.TUPLE_INSERT) return true;
      if (descriptorAt(index) == descriptor && logicalRowIdAt(index) != excludedRowId
          && comparePrefix(index, key, offset, length, parts) == 0) return true;
    }
    return false;
  }
  boolean hasActiveDeletePrefix(
      int descriptor, ByteBuffer key, int offset, int length, int parts, long logicalRowId) {
    for (int index = 0; index < count; index++) {
      if (activeAt(index) && operationAt(index) == IndexedRelationalMutation.TUPLE_DELETE
          && descriptorAt(index) == descriptor && logicalRowIdAt(index) == logicalRowId
          && comparePrefix(index, key, offset, length, parts) == 0) return true;
    }
    return false;
  }
  boolean endsWithDeletePrefix(
      int descriptor, ByteBuffer key, int offset, int length, int parts) {
    for (int index = count - 1; index >= 0; index--) {
      if (descriptorAt(index) == descriptor && comparePrefix(index, key, offset, length, parts) == 0) {
        return operationAt(index) == IndexedRelationalMutation.TUPLE_DELETE;
      }
    }
    return false;
  }
  long activeInsertPrefixRowId(
      int descriptor, ByteBuffer key, int offset, int length, int parts) {
    long found = 0;
    for (int index = 0; index < count; index++) {
      if (!activeAt(index) || operationAt(index) != IndexedRelationalMutation.TUPLE_INSERT
          || descriptorAt(index) != descriptor
          || comparePrefix(index, key, offset, length, parts) != 0) continue;
      long rowId = logicalRowIdAt(index);
      if (found != 0 && found != rowId) return -1;
      found = rowId;
    }
    return found;
  }
  long anyActiveInsertPrefixRowId(
      int descriptor, ByteBuffer key, int offset, int length, int parts) {
    return anyActiveInsertPrefixRowId(descriptor, key, offset, length, parts, 0);
  }
  long anyActiveInsertPrefixRowId(
      int descriptor, ByteBuffer key, int offset, int length, int parts, long excludedRowId) {
    for (int index = 0; index < count; index++) {
      if (activeAt(index) && operationAt(index) == IndexedRelationalMutation.TUPLE_INSERT
          && descriptorAt(index) == descriptor && logicalRowIdAt(index) != excludedRowId
          && comparePrefix(index, key, offset, length, parts) == 0) return logicalRowIdAt(index);
    }
    return 0;
  }
  int count() { return count; }
  int payloadBytes() { return payloadBytes; }
  int operationAt(int index) { return columns.operation(index); }
  int descriptorAt(int index) { return columns.descriptor(index); }
  long logicalRowIdAt(int index) { return columns.rowId(index); }
  int payloadLengthAt(int index) { return lengthAt(index); }
  boolean canAppend(int length) {
    return length >= 0 && count < maximumMutations
        && payloadBytes <= maximumPayloadBytes - length && columns.hasSlot(count, maximumMutations);
  }
  void copyPayloadTo(int index, ByteBuffer target, int targetOffset) {
    payload.copyTo(offsetAt(index), lengthAt(index), target, targetOffset);
  }
  int compare(int left, int right) {
    int leftLength = copyToScratch(left, compareLeftBytes);
    int rightLength = copyToScratch(right, compareRightBytes);
    return TupleKeyCodec.compare(compareLeft, 0, leftLength, compareRight, 0, rightLength);
  }
  int compare(int index, ByteBuffer key, int offset, int length) {
    int storedLength = copyToScratch(index, compareLeftBytes);
    return TupleKeyCodec.compare(compareLeft, 0, storedLength, key, offset, length);
  }
  boolean within(int index, TupleBTreeScanBounds bounds) {
    if (bounds.lowerShape() != null) {
      int compared = comparePrefix(index, bounds.lowerKey(), bounds.lowerOffset(),
          bounds.lowerLength(), bounds.lowerShape().partCount());
      if (compared < 0 || compared == 0 && !bounds.lowerInclusive()) return false;
    }
    if (bounds.upperShape() != null) {
      int compared = comparePrefix(index, bounds.upperKey(), bounds.upperOffset(),
          bounds.upperLength(), bounds.upperShape().partCount());
      if (compared > 0 || compared == 0 && !bounds.upperInclusive()) return false;
    }
    return true;
  }
  int comparePrefix(int index, ByteBuffer key, int offset, int length, int parts) {
    int storedLength = copyToScratch(index, compareLeftBytes);
    return TupleKeyCodec.comparePrefix(compareLeft, 0, storedLength, key, offset, length, parts);
  }
  boolean sameKey(int left, int right) {
    if (descriptorAt(left) != descriptorAt(right) || logicalRowIdAt(left) != logicalRowIdAt(right)
        || lengthAt(left) != lengthAt(right)) return false;
    int leftOffset = offsetAt(left); int rightOffset = offsetAt(right);
    for (int index = 0; index < lengthAt(left); index++) {
      if (payloadAt(leftOffset + index) != payloadAt(rightOffset + index)) return false;
    }
    return true;
  }
}
