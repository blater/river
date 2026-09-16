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
  private final IndexedHybridWalSizing stream = new IndexedHybridWalSizing();
  private int payloadBytes;
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
    status = stream.measure(pending, intents, lifecycle, logicalRowFloors, suboperations);
    if (!status.isOk()) {
      reset();
      return status;
    }
    walBytes = IndexedRelationalWalSizing.encodedBytes(stream.streamBytes(), stream.chunks());
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
    logicalRowFloors = versions = items = payloadBytes = 0;
    walBytes = 0;
    stream.reset();
  }

  int mutations() { return mutations; }
  int descriptors() { return descriptors; }
  int descriptorParts() { return descriptorParts; }
  int suboperations() { return suboperations; }
  int logicalRowFloors() { return logicalRowFloors; }
  int versions() { return versions; }
  int items() { return items; }
  int chunks() { return stream.chunks(); }
  int payloadBytes() { return payloadBytes; }
  long streamBytes() { return stream.streamBytes(); }
  long walBytes() { return walBytes; }
}
