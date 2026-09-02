package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Copies actual descriptor shapes into one reusable grouped-WAL buffer. */
final class IndexedHybridDescriptorCompiler {
  private final int[] parts =
      new int[io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS];

  StatusCode append(
      IndexedTupleIntentJournal intents, IndexedRelationalMutation mutation) {
    for (int descriptor = 0; descriptor < intents.descriptorCount(); descriptor++) {
      StatusCode status = append(intents, descriptor, mutation);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode append(
      IndexedTupleIntentJournal intents, int descriptor,
      IndexedRelationalMutation mutation) {
    int count = intents.shapeAt(descriptor).partCount();
    StatusCode status = intents.shapeAt(descriptor).copyDescriptors(parts, 0);
    return status.isOk() ? mutation.appendDescriptor(
        intents.ownerAt(descriptor), intents.keyIdAt(descriptor),
        intents.schemaIdAt(descriptor), intents.hashAt(descriptor),
        parts, 0, count) : status;
  }
}
