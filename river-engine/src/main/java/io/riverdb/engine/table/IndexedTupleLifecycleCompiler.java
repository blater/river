package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Compiles storage-derived tuple-index lifecycle transitions into one grouped mutation. */
final class IndexedTupleLifecycleCompiler {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedRelationalTupleSession tuples;
  private final IndexedTupleLifecycleRegistry registry;
  private final IndexedTupleGraphReclaimer reclaimer;
  private final int[] parts =
      new int[io.riverdb.format.btree.TupleKeyCodec.MAX_INDEX_KEY_PARTS];

  IndexedTupleLifecycleCompiler(
      IndexedTableStore store, IndexedTableKernel table, IndexedPageSet pageSet) {
    kernel = table;
    pages = pageSet;
    tuples = new IndexedRelationalTupleSession(pageSet);
    registry = new IndexedTupleLifecycleRegistry(store, table, pageSet);
    reclaimer = new IndexedTupleGraphReclaimer(pageSet);
  }

  StatusCode appendDescriptor(
      IndexedTupleIndexLifecycleBatch batch, int index,
      IndexedRelationalMutation mutation) {
    int partCount = batch.shapeAt(index).partCount();
    StatusCode status = batch.shapeAt(index).copyDescriptors(parts, 0);
    return status.isOk() ? mutation.appendDescriptor(
        batch.ownerAt(index), batch.keyIdAt(index), batch.schemaIdAt(index),
        batch.shapeAt(index).descriptorHash(), parts, 0, partCount) : status;
  }

  StatusCode compile(
      IndexedTupleIndexLifecycleBatch batch, int index,
      IndexedRelationalMutation mutation, int firstMutation) {
    return switch (batch.operationAt(index)) {
      case IndexedTupleIndexLifecycleBatch.CREATE_BUILDING ->
          create(batch, index, mutation, firstMutation);
      case IndexedTupleIndexLifecycleBatch.PUBLISH_READY ->
          ready(batch, index, mutation, firstMutation);
      case IndexedTupleIndexLifecycleBatch.DETACH_DROPPING ->
          detach(batch, index, mutation, firstMutation);
      case IndexedTupleIndexLifecycleBatch.RECLAIM_DROPPING ->
          reclaim(batch, index, mutation, firstMutation);
      case IndexedTupleIndexLifecycleBatch.FINISH_DROPPING ->
          finishDrop(batch, index, mutation, firstMutation);
      case IndexedTupleIndexLifecycleBatch.APPEND_BUILDING ->
          appendBuilding(batch, index, mutation, firstMutation);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
  }

  private StatusCode appendBuilding(
      IndexedTupleIndexLifecycleBatch batch, int index,
      IndexedRelationalMutation mutation, int firstMutation) {
    StatusCode status = registry.loadBuilding(batch, index);
    ByteBuffer expected = status.isOk() ? metadata() : null;
    if (status.isOk() && expected == null) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    int scalarRoot = BTreeRootPage.rootPageId(expected);
    int nextPage = BTreeRootPage.nextPageId(expected);
    long heap = kernel.operationRowCount();
    int tupleRoot = registry.rootPageId();
    long generation = registry.generation();
    status = registry.stageBuildingProgress(batch, index);
    return status.isOk() ? finish(
        batch, index, mutation, firstMutation,
        tupleRoot, tupleRoot, scalarRoot, nextPage, heap,
        generation, generation + 1, TupleIndexRootRecordCodec.STATE_BUILDING,
        TupleIndexRootRecordCodec.STATE_BUILDING,
        batch.privateOwnerAt(index), batch.privateOwnerAt(index), 0, 0) : status;
  }

  private StatusCode create(
      IndexedTupleIndexLifecycleBatch batch, int index,
      IndexedRelationalMutation mutation, int firstMutation) {
    StatusCode status = registry.loadAbsent(batch, index);
    ByteBuffer expected = status.isOk() ? metadata() : null;
    if (status.isOk() && expected == null) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    int scalarRoot = BTreeRootPage.rootPageId(expected);
    int nextPage = BTreeRootPage.nextPageId(expected);
    long heap = kernel.operationRowCount();
    status = tuples.configure(
        batch.keyIdAt(index), batch.schemaIdAt(index), 0, batch.shapeAt(index));
    if (status.isOk()) status = tuples.initialize();
    int tupleRoot = tuples.rootPageId();
    if (status.isOk()) status = registry.stageBuilding(batch, index, tupleRoot);
    return status.isOk() ? finish(
        batch, index, mutation, firstMutation,
        0, tupleRoot, scalarRoot, nextPage, heap,
        0, 1, TupleIndexRootRecordCodec.STATE_ABSENT,
        TupleIndexRootRecordCodec.STATE_BUILDING, 0, batch.privateOwnerAt(index),
        0, 0) : status;
  }

  private StatusCode ready(
      IndexedTupleIndexLifecycleBatch batch, int index,
      IndexedRelationalMutation mutation, int firstMutation) {
    StatusCode status = registry.loadBuilding(batch, index);
    ByteBuffer expected = status.isOk() ? metadata() : null;
    if (status.isOk() && expected == null) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    int scalarRoot = BTreeRootPage.rootPageId(expected);
    int nextPage = BTreeRootPage.nextPageId(expected);
    long heap = kernel.operationRowCount();
    int tupleRoot = registry.rootPageId();
    long generation = registry.generation();
    status = registry.stageReady(batch, index);
    return status.isOk() ? finish(
        batch, index, mutation, firstMutation,
        tupleRoot, tupleRoot, scalarRoot, nextPage, heap,
        generation, generation + 1, TupleIndexRootRecordCodec.STATE_BUILDING,
        TupleIndexRootRecordCodec.STATE_READY, batch.privateOwnerAt(index), 0,
        0, 0) : status;
  }

  private StatusCode detach(
      IndexedTupleIndexLifecycleBatch batch, int index,
      IndexedRelationalMutation mutation, int firstMutation) {
    StatusCode status = registry.loadDroppable(batch, index);
    ByteBuffer expected = status.isOk() ? metadata() : null;
    if (status.isOk() && expected == null) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    int scalarRoot = BTreeRootPage.rootPageId(expected);
    int nextPage = BTreeRootPage.nextPageId(expected);
    long heap = kernel.operationRowCount();
    int tupleRoot = registry.rootPageId();
    int state = registry.state();
    long generation = registry.generation();
    long privateOwner = registry.privateOwner();
    status = registry.stageDropping(batch, index);
    return status.isOk() ? finish(
        batch, index, mutation, firstMutation,
        tupleRoot, 0, scalarRoot, nextPage, heap,
        generation, generation + 1, state, TupleIndexRootRecordCodec.STATE_DROPPING,
        privateOwner, batch.privateOwnerAt(index), 0, 4) : status;
  }

  private StatusCode reclaim(
      IndexedTupleIndexLifecycleBatch batch, int index,
      IndexedRelationalMutation mutation, int firstMutation) {
    StatusCode status = registry.loadDropping(batch, index);
    ByteBuffer expected = status.isOk() ? metadata() : null;
    if (status.isOk() && expected == null) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    int scalarRoot = BTreeRootPage.rootPageId(expected);
    int nextPage = BTreeRootPage.nextPageId(expected);
    int cursor = registry.cleanupCursor();
    int cleanupEnd = batch.cleanupEndAt(index);
    if (cleanupEnd > nextPage || cursor >= cleanupEnd) return StatusCode.CONFLICT;
    int resultingCursor = Math.min(
        cleanupEnd, cursor + IndexedTupleGraphReclaimer.MAX_INSPECTED_PAGES);
    long heap = kernel.operationRowCount();
    long generation = registry.generation();
    status = reclaimer.reclaimBatch(
        batch.keyIdAt(index), cursor, resultingCursor, cleanupEnd);
    if (status.isOk()) status = registry.stageReclaim(batch, index, resultingCursor);
    return status.isOk() ? finish(
        batch, index, mutation, firstMutation,
        0, 0, scalarRoot, nextPage, heap,
        generation, generation + 1, TupleIndexRootRecordCodec.STATE_DROPPING,
        TupleIndexRootRecordCodec.STATE_DROPPING,
        batch.privateOwnerAt(index), batch.privateOwnerAt(index),
        cursor, resultingCursor) : status;
  }

  private StatusCode finishDrop(
      IndexedTupleIndexLifecycleBatch batch, int index,
      IndexedRelationalMutation mutation, int firstMutation) {
    StatusCode status = registry.loadDropping(batch, index);
    ByteBuffer expected = status.isOk() ? metadata() : null;
    if (status.isOk() && expected == null) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    int scalarRoot = BTreeRootPage.rootPageId(expected);
    int nextPage = BTreeRootPage.nextPageId(expected);
    int cursor = registry.cleanupCursor();
    int cleanupEnd = batch.cleanupEndAt(index);
    long heap = kernel.operationRowCount();
    long generation = registry.generation();
    status = reclaimer.finish(batch.keyIdAt(index), cursor, cleanupEnd);
    if (status.isOk()) status = registry.stageAbsent(batch, index);
    return status.isOk() ? finish(
        batch, index, mutation, firstMutation,
        0, 0, scalarRoot, nextPage, heap,
        generation, generation + 1, TupleIndexRootRecordCodec.STATE_DROPPING,
        TupleIndexRootRecordCodec.STATE_ABSENT,
        batch.privateOwnerAt(index), 0, cursor, 0) : status;
  }

  private StatusCode finish(
      IndexedTupleIndexLifecycleBatch batch, int index,
      IndexedRelationalMutation mutation, int firstMutation,
      int expectedTupleRoot, int resultingTupleRoot,
      int expectedScalarRoot, int expectedNextPage, long expectedHeap,
      long expectedGeneration, long resultingGeneration,
      int expectedState, int resultingState,
      long expectedPrivateOwner, long resultingPrivateOwner,
      int expectedCleanupCursor, int resultingCleanupCursor) {
    ByteBuffer resulting = metadata();
    if (resulting == null) return StatusCode.CORRUPTION;
    StatusCode status = mutation.appendSuboperation(
        batch.ownerAt(index), index, firstMutation, 0,
        expectedTupleRoot, resultingTupleRoot,
        expectedScalarRoot, BTreeRootPage.rootPageId(resulting),
        expectedNextPage, BTreeRootPage.nextPageId(resulting),
        expectedGeneration, resultingGeneration,
        expectedHeap, kernel.operationRowCount(), expectedState, resultingState,
        expectedPrivateOwner, resultingPrivateOwner,
        expectedCleanupCursor, resultingCleanupCursor);
    return status;
  }

  private ByteBuffer metadata() {
    ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    return metadata != null && BTreeRootPage.validate(metadata).isOk() ? metadata : null;
  }
}
