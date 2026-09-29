package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.storage.btree.TupleBTreeCursor;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Once-sorted read-your-writes merge for one tuple-index cursor. */
final class IndexedTupleScanMerge {
  private int[] ordinals = new int[0];
  private int count;
  private int position;
  private int direction;
  private long keyId;
  private long intentGeneration;
  private TupleBTreeScanBounds bounds;
  private final ByteBuffer lastKey = ByteBuffer.allocate(
      TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES);
  private int lastKeyLength;
  private boolean committed;
  private boolean exhausted;
  private StatusCode prepareStatus = StatusCode.OK;

  void prepare(
      IndexedTupleIntentJournal intents, long keyId, TupleBTreeScanBounds bounds) {
    this.keyId = keyId;
    this.bounds = bounds;
    intentGeneration = intents.generation();
    lastKeyLength = 0;
    prepareStatus = ensureCapacity(intents.mutationCount());
    count = prepareStatus.isOk() ? intents.collect(keyId, bounds, ordinals) : 0;
    if (count < 0) {
      prepareStatus = StatusCode.RESOURCE_EXHAUSTED;
      count = 0;
    }
    direction = bounds.direction();
    IndexedTupleIntentOrder.sort(intents, ordinals, count, direction);
    position = 0;
    committed = false;
    exhausted = false;
  }

  StatusCode next(
      TupleBTreeCursor cursor, TupleBTreeLeafEntry entry,
      IndexedTupleIntentJournal intents, IndexedTupleScanResult result) {
    if (intentGeneration != intents.generation()) refresh(intents);
    if (!prepareStatus.isOk()) return prepareStatus;
    result.reset();
    while (true) {
      StatusCode status = fill(cursor, entry);
      if (!status.isOk()) return status;
      if (committed && lastKeyLength > 0
          && TupleKeyCodec.compare(
              cursor.page(), cursor.pageStart() + entry.keyOffset(), entry.keyLength(),
              lastKey, 0, lastKeyLength) * direction <= 0) {
        committed = false;
        continue;
      }
      if (position >= count && !committed) return StatusCode.CONFLICT;
      int comparison = compare(cursor, entry, intents);
      if (comparison <= 0 && position < count) {
        int intent = ordinals[position++];
        if (comparison == 0) committed = false;
        if (intents.operationAt(intent) == IndexedRelationalMutation.TUPLE_INSERT
            || intents.operationAt(intent) == IndexedRelationalMutation.TUPLE_REPLACE) {
          result.setPending(intents.logicalRowIdAt(intent), intents, intent);
          rememberIntent(intents, intent);
          return StatusCode.OK;
        }
      } else {
        committed = false;
        result.setCommitted(
            entry.logicalRowId(), cursor.page(),
            cursor.pageStart() + entry.valueOffset(), entry.valueLength(),
            entry.overflowPageId(), entry.overflowGeneration(),
            entry.modificationSequence());
        rememberCommitted(cursor, entry);
        return StatusCode.OK;
      }
    }
  }

  private void refresh(IndexedTupleIntentJournal intents) {
    intentGeneration = intents.generation();
    prepareStatus = ensureCapacity(intents.mutationCount());
    count = prepareStatus.isOk() ? intents.collect(keyId, bounds, ordinals) : 0;
    if (count < 0) {
      prepareStatus = StatusCode.RESOURCE_EXHAUSTED;
      count = 0;
    }
    int retained = 0;
    for (int index = 0; index < count; index++) {
      int ordinal = ordinals[index];
      if (lastKeyLength == 0 || intents.compare(
          ordinal, lastKey, 0, lastKeyLength) * direction > 0) {
        ordinals[retained++] = ordinal;
      }
    }
    count = retained;
    IndexedTupleIntentOrder.sort(intents, ordinals, count, direction);
    position = 0;
  }

  private void rememberIntent(IndexedTupleIntentJournal intents, int intent) {
    lastKeyLength = intents.payloadLengthAt(intent);
    intents.copyPayloadTo(intent, lastKey, 0);
  }

  private void rememberCommitted(TupleBTreeCursor cursor, TupleBTreeLeafEntry entry) {
    lastKeyLength = entry.keyLength();
    int offset = cursor.pageStart() + entry.keyOffset();
    for (int index = 0; index < lastKeyLength; index++) {
      lastKey.put(index, cursor.page().get(offset + index));
    }
  }

  private StatusCode fill(TupleBTreeCursor cursor, TupleBTreeLeafEntry entry) {
    if (committed || exhausted) return StatusCode.OK;
    StatusCode status = cursor.next(entry);
    if (status == StatusCode.CONFLICT) {
      exhausted = true;
      return StatusCode.OK;
    }
    if (status.isOk()) committed = true;
    return status;
  }

  private int compare(
      TupleBTreeCursor cursor, TupleBTreeLeafEntry entry,
      IndexedTupleIntentJournal intents) {
    if (position >= count) return 1;
    if (!committed) return -1;
    return intents.compare(
        ordinals[position], cursor.page(),
        cursor.pageStart() + entry.keyOffset(), entry.keyLength()) * direction;
  }

  void reset() {
    for (int index = 0; index < count; index++) ordinals[index] = 0;
    count = 0;
    position = 0;
    direction = 0;
    keyId = 0;
    intentGeneration = 0;
    bounds = null;
    lastKeyLength = 0;
    committed = false;
    exhausted = false;
    prepareStatus = StatusCode.OK;
  }

  private StatusCode ensureCapacity(int required) {
    if (required <= ordinals.length) return StatusCode.OK;
    try {
      int capacity = Math.max(required, ordinals.length == 0 ? 256 : ordinals.length * 2);
      if (capacity < required || capacity > Integer.MAX_VALUE - 1) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      ordinals = Arrays.copyOf(ordinals, capacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
