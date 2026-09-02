package io.riverdb.engine.schema.catalog;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.table.IndexedTupleIndexState;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;

/** Exact catalog-to-registry identity matching for one physical index. */
final class CatalogTupleIndexIdentity {
  private CatalogTupleIndexIdentity() { }

  static boolean published(
      IndexedTupleIndexState state, KeyDescriptor key,
      long tableId) {
    return identity(state, key, tableId)
        && state.state() == TupleIndexRootRecordCodec.STATE_READY
        && state.rootPageId() > 0 && state.cleanupCursor() == 0
        && state.privateOwner() == 0;
  }

  static boolean owned(
      IndexedTupleIndexState state, KeyDescriptor key,
      long tableId, long privateOwner) {
    if (!identity(state, key, tableId)) return false;
    return switch (state.state()) {
      case TupleIndexRootRecordCodec.STATE_BUILDING ->
          state.rootPageId() > 0 && state.cleanupCursor() == 0
              && state.privateOwner() == privateOwner;
      case TupleIndexRootRecordCodec.STATE_READY ->
          state.rootPageId() > 0 && state.cleanupCursor() == 0
              && state.privateOwner() == 0;
      case TupleIndexRootRecordCodec.STATE_DROPPING ->
          state.rootPageId() == 0 && state.cleanupCursor() >= 4
              && state.privateOwner() == privateOwner;
      case TupleIndexRootRecordCodec.STATE_ABSENT ->
          state.rootPageId() == 0 && state.cleanupCursor() == 0
              && state.privateOwner() == 0;
      default -> false;
    };
  }

  private static boolean identity(
      IndexedTupleIndexState state, KeyDescriptor key,
      long tableId) {
    if (state == null || key == null || state.generation() <= 0
        || state.keyId() != key.keyId() || state.ownerObjectId() != tableId
        || state.schemaId() != key.keyId()
        || state.descriptorHash() != key.shape().descriptorHash()
        || state.descriptorCount() != key.shape().partCount()) return false;
    for (int part = 0; part < state.descriptorCount(); part++) {
      if (state.descriptorAt(part) != key.shape().descriptorAt(part)) return false;
    }
    return true;
  }
}
