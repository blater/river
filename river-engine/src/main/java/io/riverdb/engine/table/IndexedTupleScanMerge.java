package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.storage.btree.TupleBTreeCursor;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import java.util.Arrays;

/** Once-sorted read-your-writes merge for one tuple-index cursor. */
final class IndexedTupleScanMerge {
  private int[] ordinals = new int[0];
  private int count;
  private int position;
  private int direction;
  private boolean committed;
  private boolean exhausted;
  private StatusCode prepareStatus = StatusCode.OK;

  void prepare(
      IndexedTupleIntentJournal intents, long keyId, TupleBTreeScanBounds bounds) {
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
    if (!prepareStatus.isOk()) return prepareStatus;
    result.reset();
    while (true) {
      StatusCode status = fill(cursor, entry);
      if (!status.isOk()) return status;
      if (position >= count && !committed) return StatusCode.CONFLICT;
      int comparison = compare(cursor, entry, intents);
      if (comparison <= 0 && position < count) {
        int intent = ordinals[position++];
        if (comparison == 0) committed = false;
        if (intents.operationAt(intent) == IndexedRelationalMutation.TUPLE_INSERT) {
          result.set(intents.logicalRowIdAt(intent));
          return StatusCode.OK;
        }
      } else {
        committed = false;
        result.set(entry.logicalRowId());
        return StatusCode.OK;
      }
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
