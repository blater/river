package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Packs logical hybrid items in their emission order without materializing them. */
final class IndexedHybridWalSizing {
  private int chunks;
  private int packedBytes;
  private long streamBytes;

  StatusCode measure(
      PendingMutationBuffer pending,
      IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle, int logicalRowFloors, int suboperations) {
    StatusCode status = lifecycle.active()
        ? measureLifecycleDescriptors(intents, lifecycle)
        : measureIntentDescriptors(intents, lifecycle, false);
    if (!status.isOk()) return status;
    status = addRepeated(
        IndexedRelationalWalCodec.LOGICAL_ROW_FLOOR_ITEM_BYTES, logicalRowFloors);
    if (status.isOk()) {
      status = addRepeated(IndexedRelationalWalCodec.SUBOPERATION_ITEM_BYTES, suboperations);
    }
    for (int index = 0; status.isOk() && index < pending.count(); index++) {
      int payload = pending.operationAt(index) == IndexedWalCodec.MUTATION_DELETE
          ? 0 : pending.rowLengthAt(index);
      status = addItem(IndexedRelationalWalCodec.MUTATION_ITEM_BYTES + payload);
    }
    if (status.isOk()) status = measureTuples(intents, lifecycle);
    if (status.isOk() && packedBytes > 0) chunks++;
    return status;
  }

  private StatusCode measureTuples(
      IndexedTupleIntentJournal intents, IndexedTupleIndexLifecycleBatch lifecycle) {
    if (intents.mutationCount() == 0) return StatusCode.OK;
    StatusCode status = StatusCode.OK;
    if (lifecycle.active()) {
      for (int index = 0; status.isOk() && index < lifecycle.count(); index++) {
        int descriptor = IndexedHybridLogicalSizing.intentDescriptor(
            intents, lifecycle.keyIdAt(index));
        if (descriptor >= 0) status = measureTupleMutations(intents, descriptor);
      }
      for (int descriptor = 0; status.isOk()
          && descriptor < intents.descriptorCount(); descriptor++) {
        if (IndexedHybridLogicalSizing.lifecycleIndex(intents, lifecycle, descriptor) < 0) {
          status = measureTupleMutations(intents, descriptor);
        }
      }
      return status;
    }
    for (int descriptor = 0; status.isOk()
        && descriptor < intents.descriptorCount(); descriptor++) {
      status = measureTupleMutations(intents, descriptor);
    }
    return status;
  }

  private StatusCode measureLifecycleDescriptors(
      IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle) {
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < lifecycle.count(); index++) {
      status = addDescriptor(lifecycle.shapeAt(index).partCount());
    }
    return status.isOk() ? measureIntentDescriptors(intents, lifecycle, true) : status;
  }

  private StatusCode measureIntentDescriptors(
      IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle,
      boolean excludeLifecycle) {
    StatusCode status = StatusCode.OK;
    for (int descriptor = 0; status.isOk()
        && descriptor < intents.descriptorCount(); descriptor++) {
      if (!excludeLifecycle
          || IndexedHybridLogicalSizing.lifecycleIndex(intents, lifecycle, descriptor) < 0) {
        status = addDescriptor(intents.shapeAt(descriptor).partCount());
      }
    }
    return status;
  }

  private StatusCode measureTupleMutations(
      IndexedTupleIntentJournal intents, int descriptor) {
    StatusCode status = measureTupleMutations(
        intents, descriptor, IndexedRelationalMutation.TUPLE_DELETE);
    return status.isOk() ? measureTupleMutations(
        intents, descriptor, IndexedRelationalMutation.TUPLE_INSERT) : status;
  }

  private StatusCode measureTupleMutations(
      IndexedTupleIntentJournal intents, int descriptor, int operation) {
    StatusCode status = StatusCode.OK;
    for (int mutation = 0; status.isOk() && mutation < intents.mutationCount(); mutation++) {
      if (intents.activeAt(mutation)
          && intents.descriptorAt(mutation) == descriptor
          && intents.operationAt(mutation) == operation) {
        status = addItem(
            IndexedRelationalWalCodec.MUTATION_ITEM_BYTES
                + intents.payloadLengthAt(mutation));
      }
    }
    return status;
  }

  private StatusCode addDescriptor(int parts) {
    if (parts <= 0
        || parts > (Integer.MAX_VALUE - IndexedRelationalWalCodec.DESCRIPTOR_ITEM_BYTES)
            / Integer.BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return addItem(
        IndexedRelationalWalCodec.DESCRIPTOR_ITEM_BYTES + parts * Integer.BYTES);
  }

  private StatusCode addRepeated(int bytes, int count) {
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < count; index++) status = addItem(bytes);
    return status;
  }

  private StatusCode addItem(int bytes) {
    int maximum = IndexedRelationalWalSizing.maximumChunkStreamBytes();
    if (bytes <= 0 || bytes > maximum || streamBytes > Long.MAX_VALUE - bytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (packedBytes > maximum - bytes) {
      if (chunks == Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
      chunks++;
      packedBytes = 0;
    }
    packedBytes += bytes;
    streamBytes += bytes;
    return StatusCode.OK;
  }

  void reset() {
    chunks = packedBytes = 0;
    streamBytes = 0;
  }

  int chunks() { return chunks; }
  long streamBytes() { return streamBytes; }
}
