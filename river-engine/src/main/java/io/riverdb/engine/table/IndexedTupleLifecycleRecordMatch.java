package io.riverdb.engine.table;

import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;

/** Exact descriptor and state matching for storage-owned lifecycle records. */
final class IndexedTupleLifecycleRecordMatch {
  static final int BUILDING = 1;
  static final int DROPPABLE = 2;
  static final int DROPPING = 3;

  private IndexedTupleLifecycleRecordMatch() { }

  static boolean matches(
      TupleIndexRootRecord record, IndexedTupleIndexLifecycleBatch batch,
      int index, int expected) {
    TupleShape shape = batch.shapeAt(index);
    if (record.keyId() != batch.keyIdAt(index)
        || record.ownerObjectId() != batch.ownerAt(index)
        || record.schemaId() != batch.schemaIdAt(index)
        || record.descriptorHash() != shape.descriptorHash()
        || record.descriptorCount() != shape.partCount()) return false;
    for (int part = 0; part < record.descriptorCount(); part++) {
      if (record.descriptorAt(part) != shape.descriptorAt(part)) return false;
    }
    return state(record, batch, index, expected);
  }

  private static boolean state(
      TupleIndexRootRecord record, IndexedTupleIndexLifecycleBatch batch,
      int index, int expected) {
    if (expected == BUILDING) {
      return record.state() == TupleIndexRootRecordCodec.STATE_BUILDING
          && record.rootPageId() > 0 && record.cleanupCursor() == 0
          && record.privateOwner() == batch.privateOwnerAt(index);
    }
    if (expected == DROPPABLE) {
      return record.rootPageId() > 0 && record.cleanupCursor() == 0
          && (record.state() == TupleIndexRootRecordCodec.STATE_READY
              && record.privateOwner() == 0
              || record.state() == TupleIndexRootRecordCodec.STATE_BUILDING
                  && record.privateOwner() == batch.privateOwnerAt(index));
    }
    return expected == DROPPING
        && record.state() == TupleIndexRootRecordCodec.STATE_DROPPING
        && record.rootPageId() == 0 && record.cleanupCursor() >= 4
        && record.privateOwner() == batch.privateOwnerAt(index);
  }
}
