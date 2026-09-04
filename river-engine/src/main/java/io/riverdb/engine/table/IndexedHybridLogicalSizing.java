package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Exact reusable logical-output and WAL demand calculation before physical staging. */
final class IndexedHybridLogicalSizing {
  private int mutations;
  private int descriptors;
  private int descriptorParts;
  private int suboperations;
  private int logicalRowFloors;
  private int versions;
  private int items;
  private int chunks;
  private int packedBytes;
  private int payloadBytes;
  private long streamBytes;
  private long walBytes;

  StatusCode measure(
      PendingMutationBuffer pending,
      IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle,
      IndexedLogicalRowIdFloors floors) {
    reset();
    if (pending == null || intents == null || lifecycle == null || floors == null
        || pending.count() == 0 && intents.mutationCount() == 0
            && !lifecycle.active() && floors.count() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (lifecycle.active() && intents.mutationCount() != 0
        && !lifecycle.acceptsTupleMutations()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = measureShape(pending, intents, lifecycle, floors);
    if (!status.isOk()) {
      reset();
      return status;
    }
    status = measureItems(pending, intents, lifecycle);
    if (!status.isOk()) {
      reset();
      return status;
    }
    if (packedBytes > 0) chunks++;
    walBytes = IndexedRelationalWalSizing.encodedBytes(streamBytes, chunks);
    if (walBytes < 0) {
      reset();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return StatusCode.OK;
  }

  private StatusCode measureShape(
      PendingMutationBuffer pending,
      IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle,
      IndexedLogicalRowIdFloors floors) {
    int activeTuples = intents.activeMutationCount();
    long mutationTotal = (long) pending.count() + activeTuples;
    if (mutationTotal > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    mutations = (int) mutationTotal;
    logicalRowFloors = floors.count();

    long partTotal = 0;
    if (lifecycle.active()) {
      descriptors = lifecycle.count();
      for (int index = 0; index < lifecycle.count(); index++) {
        partTotal += lifecycle.shapeAt(index).partCount();
      }
      for (int descriptor = 0; descriptor < intents.descriptorCount(); descriptor++) {
        if (lifecycleIndex(intents, lifecycle, descriptor) >= 0) continue;
        if (descriptors == Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
        descriptors++;
        partTotal += intents.shapeAt(descriptor).partCount();
      }
    } else {
      descriptors = intents.descriptorCount();
      for (int descriptor = 0; descriptor < descriptors; descriptor++) {
        partTotal += intents.shapeAt(descriptor).partCount();
      }
    }
    if (partTotal > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    descriptorParts = (int) partTotal;

    long suboperationTotal = (pending.count() > 0 ? 1L : 0L) + descriptors;
    long itemTotal = (long) descriptors + logicalRowFloors
        + suboperationTotal + mutations;
    if (suboperationTotal > Integer.MAX_VALUE
        || itemTotal <= 0 || itemTotal > Integer.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    suboperations = (int) suboperationTotal;
    items = (int) itemTotal;
    versions = IndexedVersionOperation.required(pending.count(), descriptors);
    if (versions < 0) return StatusCode.RESOURCE_EXHAUSTED;

    long payloadTotal = scalarPayloadBytes(pending);
    if (payloadTotal < 0) return StatusCode.RESOURCE_EXHAUSTED;
    for (int mutation = 0; mutation < intents.mutationCount(); mutation++) {
      if (intents.activeAt(mutation)) {
        payloadTotal += intents.payloadLengthAt(mutation);
        if (payloadTotal > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    payloadBytes = (int) payloadTotal;
    return StatusCode.OK;
  }

  private StatusCode measureItems(
      PendingMutationBuffer pending,
      IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle) {
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
    if (!status.isOk() || intents.mutationCount() == 0) return status;
    if (lifecycle.active()) {
      for (int index = 0; status.isOk() && index < lifecycle.count(); index++) {
        int descriptor = intentDescriptor(intents, lifecycle.keyIdAt(index));
        if (descriptor >= 0) status = measureTupleMutations(intents, descriptor);
      }
      for (int descriptor = 0; status.isOk()
          && descriptor < intents.descriptorCount(); descriptor++) {
        if (lifecycleIndex(intents, lifecycle, descriptor) < 0) {
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
      if (!excludeLifecycle || lifecycleIndex(intents, lifecycle, descriptor) < 0) {
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

  private static long scalarPayloadBytes(PendingMutationBuffer pending) {
    long bytes = 0;
    for (int index = 0; index < pending.count(); index++) {
      if (pending.operationAt(index) == IndexedWalCodec.MUTATION_DELETE) continue;
      bytes += pending.rowLengthAt(index);
      if (bytes > Integer.MAX_VALUE) return -1;
    }
    return bytes;
  }

  static int intentDescriptor(
      IndexedTupleIntentJournal intents, long keyId) {
    for (int descriptor = 0; descriptor < intents.descriptorCount(); descriptor++) {
      if (intents.keyIdAt(descriptor) == keyId) return descriptor;
    }
    return -1;
  }

  static int lifecycleIndex(
      IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle,
      int descriptor) {
    for (int index = 0; index < lifecycle.count(); index++) {
      if (lifecycle.keyIdAt(index) == intents.keyIdAt(descriptor)) return index;
    }
    return -1;
  }

  void reset() {
    mutations = descriptors = descriptorParts = suboperations = 0;
    logicalRowFloors = versions = items = chunks = packedBytes = payloadBytes = 0;
    streamBytes = walBytes = 0;
  }

  int mutations() { return mutations; }
  int descriptors() { return descriptors; }
  int descriptorParts() { return descriptorParts; }
  int suboperations() { return suboperations; }
  int logicalRowFloors() { return logicalRowFloors; }
  int versions() { return versions; }
  int items() { return items; }
  int chunks() { return chunks; }
  int payloadBytes() { return payloadBytes; }
  long streamBytes() { return streamBytes; }
  long walBytes() { return walBytes; }
}
