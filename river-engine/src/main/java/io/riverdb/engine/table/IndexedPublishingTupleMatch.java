package io.riverdb.engine.table;

/** Exact identity match between one private lifecycle root and its tuple deltas. */
final class IndexedPublishingTupleMatch {
  private IndexedPublishingTupleMatch() { }

  static boolean same(
      IndexedTupleIntentJournal intents, int descriptor,
      IndexedTupleIndexLifecycleBatch lifecycle, int index) {
    int operation = lifecycle.operationAt(index);
    if (operation != IndexedTupleIndexLifecycleBatch.PUBLISH_READY
        && operation != IndexedTupleIndexLifecycleBatch.APPEND_BUILDING
        || intents.keyIdAt(descriptor) != lifecycle.keyIdAt(index)
        || intents.ownerAt(descriptor) != lifecycle.ownerAt(index)
        || intents.schemaIdAt(descriptor) != lifecycle.schemaIdAt(index)
        || intents.hashAt(descriptor) != lifecycle.shapeAt(index).descriptorHash()
        || intents.shapeAt(descriptor).partCount()
            != lifecycle.shapeAt(index).partCount()) return false;
    for (int part = 0; part < intents.shapeAt(descriptor).partCount(); part++) {
      if (intents.shapeAt(descriptor).descriptorAt(part)
          != lifecycle.shapeAt(index).descriptorAt(part)) return false;
    }
    return true;
  }
}
