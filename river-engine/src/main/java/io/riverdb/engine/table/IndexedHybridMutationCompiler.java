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
  private final IndexedHybridLogicalSizing sizing = new IndexedHybridLogicalSizing();
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
    StatusCode measured = sizing.measure(pending, intents, lifecycleBatch, floors);
    if (!measured.isOk()) return measured;
    StatusCode reservation = kernel.reserveOperationVersions(sizing.versions());
    if (!reservation.isOk()) return reservation;
    if (lifecycleBatch != null && lifecycleBatch.active()) {
      StatusCode status = compileLifecycle(
          pending, intents, lifecycleBatch, floors, sizing);
      if (status.isOk()) recordCompilation();
      return status;
    }
    StatusCode status = compileDml(pending, intents, floors, sizing);
    if (status.isOk()) recordCompilation();
    return status;
  }

  StatusCode compileCumulative(
      PendingMutationBuffer pending, IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycleBatch, IndexedLogicalRowIdFloors floors) {
    if (pending == null || intents == null || floors == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode measured = sizing.measure(pending, intents, lifecycleBatch, floors);
    if (!measured.isOk()) return measured;
    StatusCode status;
    if (lifecycleBatch != null && lifecycleBatch.active()) {
      status = compileLifecycle(pending, intents, lifecycleBatch, floors, sizing);
    } else if (pending.count() > 0 || intents.mutationCount() > 0 || floors.count() > 0) {
      status = compileDml(pending, intents, floors, sizing);
    } else status = StatusCode.INVALID_EXTERNAL_INPUT;
    if (status.isOk()) recordCompilation();
    return status;
  }

  private StatusCode compileLifecycle(
      PendingMutationBuffer pending, IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch batch, IndexedLogicalRowIdFloors floors,
      IndexedHybridLogicalSizing logical) {
    if (pending == null || intents == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (intents.mutationCount() != 0) {
      return compilePublishingDml(pending, intents, batch, floors, logical);
    }
    StatusCode status = intents.prepareLifecycleCompilation(
        logical.mutations(), logical.descriptors(), logical.descriptorParts(),
        logical.payloadBytes(), logical.logicalRowFloors(), compiled);
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
      IndexedTupleIndexLifecycleBatch batch, IndexedLogicalRowIdFloors floors,
      IndexedHybridLogicalSizing logical) {
    StatusCode status = intents.prepareLifecycleCompilation(
        logical.mutations(), logical.descriptors(), logical.descriptorParts(),
        logical.payloadBytes(), logical.logicalRowFloors(), compiled);
    if (status.isOk()) status = appendFloors(floors, compiled[0]);
    for (int index = 0; status.isOk() && index < batch.count(); index++) {
      status = lifecycle.appendDescriptor(batch, index, compiled[0]);
    }
    for (int descriptor = 0; status.isOk()
        && descriptor < intents.descriptorCount(); descriptor++) {
      if (IndexedHybridLogicalSizing.lifecycleIndex(intents, batch, descriptor) < 0) {
        status = descriptors.append(intents, descriptor, compiled[0]);
      }
    }
    if (status.isOk() && pending.count() > 0) status = scalar.compile(pending, compiled[0]);
    int firstMutation = pending.count();
    int suboperation = pending.count() > 0 ? 1 : 0;
    for (int index = 0; status.isOk() && index < batch.count(); index++) {
      int descriptor = IndexedHybridLogicalSizing.intentDescriptor(
          intents, batch.keyIdAt(index));
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
      if (IndexedHybridLogicalSizing.lifecycleIndex(intents, batch, descriptor) >= 0) continue;
      status = tuples.compile(
          intents, descriptor, compiled[0], outputDescriptor,
          suboperation, firstMutation);
      firstMutation += tuples.count(intents, descriptor);
      outputDescriptor++;
      suboperation++;
    }
    return status.isOk() ? compiled[0].seal() : status;
  }

  private StatusCode compileDml(
      PendingMutationBuffer pending, IndexedTupleIntentJournal intents,
      IndexedLogicalRowIdFloors floors, IndexedHybridLogicalSizing logical) {
    if (pending == null || intents == null
        || pending.count() + intents.mutationCount() <= 0 && floors.count() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = intents.prepareLifecycleCompilation(
        logical.mutations(), logical.descriptors(), logical.descriptorParts(),
        logical.payloadBytes(), logical.logicalRowFloors(), compiled);
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
