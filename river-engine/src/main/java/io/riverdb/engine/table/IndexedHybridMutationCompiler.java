package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Compiles one session's semantic intents into staged pages and exact WAL evidence. */
final class IndexedHybridMutationCompiler {
  private final IndexedTableKernel kernel;
  private final IndexedHybridScalarCompiler scalar;
  private final IndexedHybridTupleCompiler tuples;
  private final IndexedPublishingTupleCompiler publishing;
  private final IndexedHybridDescriptorCompiler descriptors =
      new IndexedHybridDescriptorCompiler();
  private final IndexedTupleLifecycleCompiler lifecycle;
  private final IndexedRelationalMutation[] compiled = new IndexedRelationalMutation[1];
  private IndexedRelationalMutation current;
  private long copiedPayloadBytes;

  IndexedHybridMutationCompiler(
      IndexedTableStore store, IndexedTableKernel table, IndexedPageSet pages) {
    kernel = table;
    scalar = new IndexedHybridScalarCompiler(table, pages);
    tuples = new IndexedHybridTupleCompiler(store, table, pages);
    publishing = new IndexedPublishingTupleCompiler(store, table, pages);
    lifecycle = new IndexedTupleLifecycleCompiler(store, table, pages);
  }

  StatusCode compile(
      PendingMutationBuffer pending, IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycleBatch, IndexedLogicalRowIdFloors floors) {
    if (pending == null || intents == null || floors == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int internalVersions = lifecycleBatch != null && lifecycleBatch.active()
        ? combinedDescriptorCount(intents, lifecycleBatch) : intents.descriptorCount();
    int requiredVersions = IndexedVersionOperation.required(
        pending.count(), internalVersions);
    if (requiredVersions < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode reservation = kernel.reserveOperationVersions(requiredVersions);
    if (!reservation.isOk()) return reservation;
    if (lifecycleBatch != null && lifecycleBatch.active()) {
      StatusCode status = compileLifecycle(pending, intents, lifecycleBatch, floors);
      if (status.isOk()) recordCompilation();
      return status;
    }
    StatusCode status = compileDml(pending, intents, floors);
    if (status.isOk()) recordCompilation();
    return status;
  }

  StatusCode compileCumulative(
      PendingMutationBuffer pending, IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycleBatch, IndexedLogicalRowIdFloors floors) {
    if (pending == null || intents == null || floors == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int internalVersions = lifecycleBatch != null && lifecycleBatch.active()
        ? combinedDescriptorCount(intents, lifecycleBatch) : intents.descriptorCount();
    int additional = IndexedVersionOperation.required(pending.count(), internalVersions);
    int existing = kernel.operationVersionCount();
    if (additional < 0 || existing > Integer.MAX_VALUE - additional) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = kernel.reserveOperationVersions(existing + additional);
    if (!status.isOk()) return status;
    if (lifecycleBatch != null && lifecycleBatch.active()) {
      status = compileLifecycle(pending, intents, lifecycleBatch, floors);
    } else if (pending.count() > 0 || intents.mutationCount() > 0 || floors.count() > 0) {
      status = compileDml(pending, intents, floors);
    } else status = StatusCode.INVALID_EXTERNAL_INPUT;
    if (status.isOk()) recordCompilation();
    return status;
  }

  private StatusCode compileLifecycle(
      PendingMutationBuffer pending, IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch batch, IndexedLogicalRowIdFloors floors) {
    if (pending == null || intents == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (intents.mutationCount() != 0) {
      return compilePublishingDml(pending, intents, batch, floors);
    }
    int scalarPayload = scalar.payloadBytes(pending);
    if (scalarPayload < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = intents.prepareLifecycleCompilation(
        pending.count(), batch.count(), batch.partCount(), scalarPayload,
        floors.count(), compiled);
    if (status.isOk()) status = appendFloors(floors, compiled[0]);
    for (int index = 0; status.isOk() && index < batch.count(); index++) {
      status = lifecycle.appendDescriptor(batch, index, compiled[0]);
    }
    if (status.isOk() && pending.count() > 0) {
      status = scalar.compile(pending, compiled[0]);
    }
    for (int index = 0; status.isOk() && index < batch.count(); index++) {
      status = lifecycle.compile(batch, index, compiled[0], pending.count());
    }
    return status.isOk() ? compiled[0].seal() : status;
  }

  private StatusCode compilePublishingDml(
      PendingMutationBuffer pending, IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch batch, IndexedLogicalRowIdFloors floors) {
    if (!composable(batch)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int scalarPayload = scalar.payloadBytes(pending);
    if (scalarPayload < 0 || scalarPayload > Integer.MAX_VALUE - intents.payloadBytes()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int descriptorCount = combinedDescriptorCount(intents, batch);
    int descriptorParts = combinedDescriptorParts(intents, batch);
    if (descriptorCount < 0 || descriptorParts < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = intents.prepareLifecycleCompilation(
        pending.count() + intents.mutationCount(), descriptorCount, descriptorParts,
        scalarPayload + intents.payloadBytes(), floors.count(), compiled);
    if (status.isOk()) status = appendFloors(floors, compiled[0]);
    for (int index = 0; status.isOk() && index < batch.count(); index++) {
      status = lifecycle.appendDescriptor(batch, index, compiled[0]);
    }
    for (int descriptor = 0; status.isOk()
        && descriptor < intents.descriptorCount(); descriptor++) {
      if (find(intents, batch, descriptor) < 0) {
        status = descriptors.append(intents, descriptor, compiled[0]);
      }
    }
    if (status.isOk() && pending.count() > 0) status = scalar.compile(pending, compiled[0]);
    int firstMutation = pending.count();
    int suboperation = pending.count() > 0 ? 1 : 0;
    for (int index = 0; status.isOk() && index < batch.count(); index++) {
      int descriptor = find(intents, batch.keyIdAt(index));
      if (descriptor < 0) {
        status = lifecycle.compile(batch, index, compiled[0], firstMutation);
      } else {
        status = publishing.compile(
            intents, descriptor, batch, index, compiled[0],
            suboperation, firstMutation);
        firstMutation += publishing.count(intents, descriptor);
      }
      suboperation++;
    }
    int outputDescriptor = batch.count();
    for (int descriptor = 0; status.isOk()
        && descriptor < intents.descriptorCount(); descriptor++) {
      if (find(intents, batch, descriptor) >= 0) continue;
      status = tuples.compile(
          intents, descriptor, compiled[0], outputDescriptor,
          suboperation, firstMutation);
      firstMutation += tuples.count(intents, descriptor);
      outputDescriptor++;
      suboperation++;
    }
    return status.isOk() ? compiled[0].seal() : status;
  }

  private static boolean composable(IndexedTupleIndexLifecycleBatch batch) {
    for (int index = 0; index < batch.count(); index++) {
      int operation = batch.operationAt(index);
      if (operation != IndexedTupleIndexLifecycleBatch.PUBLISH_READY
          && operation != IndexedTupleIndexLifecycleBatch.APPEND_BUILDING) {
        return false;
      }
    }
    return true;
  }

  private static int combinedDescriptorCount(
      IndexedTupleIntentJournal intents, IndexedTupleIndexLifecycleBatch batch) {
    int count = batch.count();
    for (int descriptor = 0; descriptor < intents.descriptorCount(); descriptor++) {
      if (find(intents, batch, descriptor) < 0) {
        if (count == Integer.MAX_VALUE) return -1;
        count++;
      }
    }
    return count;
  }

  private static int combinedDescriptorParts(
      IndexedTupleIntentJournal intents, IndexedTupleIndexLifecycleBatch batch) {
    long parts = batch.partCount();
    for (int descriptor = 0; descriptor < intents.descriptorCount(); descriptor++) {
      if (find(intents, batch, descriptor) < 0) {
        parts += intents.shapeAt(descriptor).partCount();
        if (parts > Integer.MAX_VALUE) return -1;
      }
    }
    return (int) parts;
  }

  private static int find(IndexedTupleIntentJournal intents, long keyId) {
    for (int descriptor = 0; descriptor < intents.descriptorCount(); descriptor++) {
      if (intents.keyIdAt(descriptor) == keyId) return descriptor;
    }
    return -1;
  }

  private static int find(
      IndexedTupleIntentJournal intents, IndexedTupleIndexLifecycleBatch batch,
      int descriptor) {
    for (int index = 0; index < batch.count(); index++) {
      if (batch.keyIdAt(index) == intents.keyIdAt(descriptor)) return index;
    }
    return -1;
  }

  private StatusCode compileDml(
      PendingMutationBuffer pending, IndexedTupleIntentJournal intents,
      IndexedLogicalRowIdFloors floors) {
    if (pending == null || intents == null
        || pending.count() + intents.mutationCount() <= 0 && floors.count() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int scalarPayload = scalar.payloadBytes(pending);
    if (scalarPayload < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = intents.prepareCompilation(
        pending.count(), scalarPayload, floors.count(), compiled);
    if (status.isOk()) status = appendFloors(floors, compiled[0]);
    if (status.isOk()) status = descriptors.append(intents, compiled[0]);
    int firstMutation = 0;
    int suboperation = 0;
    if (status.isOk() && pending.count() > 0) {
      status = scalar.compile(pending, compiled[0]);
      firstMutation = pending.count();
      suboperation = 1;
    }
    for (int descriptor = 0; status.isOk()
        && descriptor < intents.descriptorCount(); descriptor++) {
      status = tuples.compile(
          intents, descriptor, compiled[0], descriptor,
          suboperation, firstMutation);
      firstMutation += tuples.count(intents, descriptor);
      suboperation++;
    }
    return status.isOk() ? compiled[0].seal() : status;
  }

  private static StatusCode appendFloors(
      IndexedLogicalRowIdFloors floors, IndexedRelationalMutation mutation) {
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < floors.count(); index++) {
      status = mutation.appendLogicalRowFloor(floors.objectIdAt(index), floors.nextAt(index));
    }
    return status;
  }

  IndexedRelationalMutation mutation() { return current; }

  long copiedPayloadBytes() { return copiedPayloadBytes; }

  private void recordCompilation() {
    current = compiled[0];
    copiedPayloadBytes += current.buffer().payloadBytes();
  }
}
