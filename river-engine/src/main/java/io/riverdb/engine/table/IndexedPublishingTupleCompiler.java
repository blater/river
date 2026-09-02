package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Applies tuple deltas while retaining or publishing one private BUILDING root. */
final class IndexedPublishingTupleCompiler {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedTupleIntentRegistry registry;
  private final IndexedTupleDeltaCompiler deltas;

  IndexedPublishingTupleCompiler(
      IndexedTableStore store, IndexedTableKernel table, IndexedPageSet pageSet) {
    kernel = table;
    pages = pageSet;
    registry = new IndexedTupleIntentRegistry(store, table, pageSet);
    deltas = new IndexedTupleDeltaCompiler(pageSet);
  }

  StatusCode compile(
      IndexedTupleIntentJournal intents, int descriptor,
      IndexedTupleIndexLifecycleBatch lifecycle, int lifecycleIndex,
      IndexedRelationalMutation mutation, int suboperation, int firstMutation) {
    if (!IndexedPublishingTupleMatch.same(
        intents, descriptor, lifecycle, lifecycleIndex)) return StatusCode.CORRUPTION;
    StatusCode status = registry.loadBuilding(lifecycle, lifecycleIndex);
    ByteBuffer expected = status.isOk() ? metadata() : null;
    if (status.isOk() && expected == null) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    int scalarRoot = BTreeRootPage.rootPageId(expected);
    int nextPage = BTreeRootPage.nextPageId(expected);
    long heap = kernel.operationRowCount();
    int tupleRoot = registry.rootPageId();
    long generation = registry.generation();
    status = deltas.apply(intents, descriptor, tupleRoot);
    int resultingRoot = deltas.rootPageId();
    boolean building = lifecycle.appendsBuilding(lifecycleIndex);
    if (status.isOk()) status = registry.stage(
        resultingRoot, building, lifecycle.privateOwnerAt(lifecycleIndex));
    ByteBuffer resulting = status.isOk() ? metadata() : null;
    if (status.isOk() && resulting == null) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    status = mutation.appendSuboperation(
        intents.ownerAt(descriptor), lifecycleIndex, firstMutation,
        deltas.count(intents, descriptor), tupleRoot, resultingRoot,
        scalarRoot, BTreeRootPage.rootPageId(resulting), nextPage,
        BTreeRootPage.nextPageId(resulting), generation, generation + 1,
        heap, kernel.operationRowCount(), TupleIndexRootRecordCodec.STATE_BUILDING,
        building ? TupleIndexRootRecordCodec.STATE_BUILDING
            : TupleIndexRootRecordCodec.STATE_READY,
        lifecycle.privateOwnerAt(lifecycleIndex),
        building ? lifecycle.privateOwnerAt(lifecycleIndex) : 0);
    return status.isOk() ? deltas.append(
        intents, descriptor, mutation, suboperation, lifecycleIndex) : status;
  }

  int count(IndexedTupleIntentJournal intents, int descriptor) {
    return deltas.count(intents, descriptor);
  }

  private ByteBuffer metadata() {
    ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    return metadata != null && BTreeRootPage.validate(metadata).isOk() ? metadata : null;
  }
}
