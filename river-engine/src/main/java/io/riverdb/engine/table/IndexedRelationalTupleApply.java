package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Applies one tuple-index suboperation and publishes its registry CAS result. */
final class IndexedRelationalTupleApply {
  private final IndexedTableKernel kernel;
  private final IndexedTupleRegistryState registry;
  private final IndexedRelationalTupleSession session;
  private final IndexedTupleGraphReclaimer reclaimer;
  private final IndexedRetiredOverflowReclaimer retiredOverflow;
  private final ByteBuffer key =
      ByteBuffer.allocate(TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES);
  private final ByteBuffer value =
      ByteBuffer.allocate(io.riverdb.storage.heap.HeapPage.MAXIMUM_ROW_BYTES);

  IndexedRelationalTupleApply(
      IndexedTableKernel table, IndexedPageSet pageSet, IndexedTupleRegistryState roots) {
    kernel = table;
    registry = roots;
    session = new IndexedRelationalTupleSession(pageSet);
    reclaimer = new IndexedTupleGraphReclaimer(pageSet);
    retiredOverflow = new IndexedRetiredOverflowReclaimer(pageSet);
  }

  StatusCode apply(
      IndexedRelationalMutationBuffer source, int operation, long memberSequence) {
    if (kernel.operationRowCount() != source.expectedHeapVersionAt(operation)) {
      return StatusCode.CORRUPTION;
    }
    return applyLoaded(source, operation, memberSequence);
  }

  private StatusCode applyLoaded(
      IndexedRelationalMutationBuffer source, int operation, long memberSequence) {
    StatusCode status = registry.load(source, operation);
    int descriptor = source.suboperationDescriptorAt(operation);
    if (status.isOk()) status = reclaimRetired(source, operation, descriptor, memberSequence);
    if (status.isOk()) status = prepare(source, operation, descriptor);
    if (status.isOk()) status = applyMutations(source, operation, memberSequence);
    if (status.isOk()) status = validateResult(source, operation);
    if (status.isOk()) status = stageAndCleanup(
        source, operation, descriptor, memberSequence);
    return complete(status, source, operation);
  }

  private StatusCode stageAndCleanup(
      IndexedRelationalMutationBuffer source, int operation, int descriptor,
      long memberSequence) {
    // Registry allocation precedes free-page publication; failure discards both staged changes.
    StatusCode status = registry.stage(source, operation, memberSequence);
    return status.isOk() ? cleanup(source, operation, descriptor) : status;
  }

  private StatusCode reclaimRetired(
      IndexedRelationalMutationBuffer source, int operation, int descriptor,
      long memberSequence) {
    int pageId = source.reclaimedOverflowPageIdAt(operation);
    if (pageId == 0) return StatusCode.OK;
    long retirement = source.reclaimedOverflowRetirementAt(operation);
    return retirement >= memberSequence ? StatusCode.CORRUPTION
        : retiredOverflow.reclaimExact(
            source.keyIdAt(descriptor), pageId,
            source.reclaimedOverflowGenerationAt(operation), retirement);
  }

  private StatusCode complete(
      StatusCode status, IndexedRelationalMutationBuffer source, int operation) {
    return status.isOk() && resultingHeapMatches(source, operation)
        ? StatusCode.OK : status.isOk() ? StatusCode.CORRUPTION : status;
  }

  private StatusCode prepare(
      IndexedRelationalMutationBuffer source, int operation, int descriptor) {
    int expectedRoot = source.expectedTupleRootAt(operation);
    StatusCode status = session.configure(
        source.keyIdAt(descriptor), source.schemaIdAt(descriptor),
        expectedRoot, source.shapeAt(descriptor));
    return status.isOk() && shouldInitialize(source, operation, expectedRoot)
        ? session.initialize() : status;
  }

  private StatusCode applyMutations(
      IndexedRelationalMutationBuffer source, int operation, long memberSequence) {
    int first = source.suboperationFirstMutationAt(operation);
    int end = first + source.suboperationMutationCountAt(operation);
    StatusCode status = StatusCode.OK;
    for (int mutation = first; status.isOk() && mutation < end; mutation++) {
      int bytes = source.tupleKeyLengthAt(mutation);
      key.position(0);
      key.limit(bytes);
      source.copyTupleKeyTo(mutation, key, 0);
      int valueLength = source.tupleValueLengthAt(mutation);
      value.clear();
      value.limit(valueLength);
      if (valueLength > 0) source.copyTupleValueTo(mutation, value, 0);
      int kind = source.operationAt(mutation);
      status = kind == IndexedRelationalMutationBuffer.TUPLE_INSERT
          ? valueLength == 0 ? session.insert(key)
              : session.insertValue(key, value, 0, valueLength, 0, 0, memberSequence)
          : kind == IndexedRelationalMutationBuffer.TUPLE_REPLACE
              ? session.replaceValue(key, value, 0, valueLength, 0, 0,
                  memberSequence)
              : session.delete(key, memberSequence);
    }
    return status;
  }

  private StatusCode validateResult(
      IndexedRelationalMutationBuffer source, int operation) {
    int expectedRoot = source.expectedTupleRootAt(operation);
    if (requiresPublishedRoot(source, operation)
        && session.rootPageId() != source.resultingTupleRootAt(operation)) {
      return StatusCode.CORRUPTION;
    }
    return expectedRoot > 0 ? session.validate() : StatusCode.OK;
  }

  private StatusCode cleanup(
      IndexedRelationalMutationBuffer source, int operation, int descriptor) {
    if (reclaims(source, operation)) {
      return reclaimer.reclaimBatch(
          source.keyIdAt(descriptor), source.expectedCleanupCursorAt(operation),
          source.resultingCleanupCursorAt(operation),
          source.resultingCleanupCursorAt(operation));
    }
    return finishesCleanup(source, operation)
        ? reclaimer.finish(
            source.keyIdAt(descriptor),
            source.expectedCleanupCursorAt(operation),
            source.expectedCleanupCursorAt(operation)) : StatusCode.OK;
  }

  private boolean resultingHeapMatches(
      IndexedRelationalMutationBuffer source, int operation) {
    return kernel.operationRowCount() == source.resultingHeapVersionAt(operation);
  }

  private static boolean shouldInitialize(
      IndexedRelationalMutationBuffer source, int operation, int expectedRoot) {
    return expectedRoot == 0
        && !emptyBuilding(source, operation)
        && !reclaims(source, operation)
        && !finishes(source, operation);
  }

  private static boolean emptyBuilding(
      IndexedRelationalMutationBuffer source, int operation) {
    return source.expectedTupleRootAt(operation) == 0
        && source.resultingTupleRootAt(operation) == 0
        && source.resultingRegistryStateAt(operation)
            == IndexedRelationalSuboperations.REGISTRY_BUILDING;
  }

  private static boolean requiresPublishedRoot(
      IndexedRelationalMutationBuffer source, int operation) {
    return !detaches(source, operation)
        && !reclaims(source, operation)
        && !finishes(source, operation);
  }

  private static boolean detaches(
      IndexedRelationalMutationBuffer source, int operation) {
    return source.resultingRegistryStateAt(operation)
            == IndexedRelationalSuboperations.REGISTRY_DROPPING
        && source.resultingTupleRootAt(operation) == 0
        && source.expectedTupleRootAt(operation) > 0;
  }

  private static boolean reclaims(
      IndexedRelationalMutationBuffer source, int operation) {
    int expectedState = source.expectedRegistryStateAt(operation);
    return expectedState == IndexedRelationalSuboperations.REGISTRY_DROPPING
        && source.expectedTupleRootAt(operation) == 0
        && source.resultingRegistryStateAt(operation) == expectedState;
  }

  private static boolean finishes(
      IndexedRelationalMutationBuffer source, int operation) {
    return source.resultingRegistryStateAt(operation)
        == IndexedRelationalSuboperations.REGISTRY_ABSENT;
  }

  private static boolean finishesCleanup(
      IndexedRelationalMutationBuffer source, int operation) {
    return finishes(source, operation)
        && source.expectedRegistryStateAt(operation)
            == IndexedRelationalSuboperations.REGISTRY_DROPPING;
  }
}
