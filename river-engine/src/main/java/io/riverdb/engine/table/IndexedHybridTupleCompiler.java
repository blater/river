package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Stages one READY tuple descriptor's deltas and derived registry evidence. */
final class IndexedHybridTupleCompiler {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedTupleIntentRegistry registry;
  private final IndexedTupleDeltaCompiler deltas;

  IndexedHybridTupleCompiler(
      IndexedTableStore store, IndexedTableKernel table, IndexedPageSet pageSet) {
    kernel = table;
    pages = pageSet;
    registry = new IndexedTupleIntentRegistry(store, table, pageSet);
    deltas = new IndexedTupleDeltaCompiler(pageSet);
  }

  StatusCode compile(
      IndexedTupleIntentJournal intents, int descriptor,
      IndexedRelationalMutation mutation, int outputDescriptor,
      int suboperation, int firstMutation) {
    StatusCode status = registry.load(intents, descriptor);
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
    if (status.isOk()) status = registry.stage(resultingRoot, false, 0);
    ByteBuffer resulting = status.isOk() ? metadata() : null;
    if (status.isOk() && resulting == null) status = StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    status = mutation.appendSuboperation(
        intents.ownerAt(descriptor), outputDescriptor,
        firstMutation, deltas.count(intents, descriptor),
        tupleRoot, resultingRoot, scalarRoot, BTreeRootPage.rootPageId(resulting),
        nextPage, BTreeRootPage.nextPageId(resulting), generation, generation + 1,
        heap, kernel.operationRowCount(), TupleIndexRootRecordCodec.STATE_READY,
        TupleIndexRootRecordCodec.STATE_READY, 0, 0);
    return status.isOk()
        ? deltas.append(
            intents, descriptor, mutation, suboperation, outputDescriptor) : status;
  }

  int count(IndexedTupleIntentJournal intents, int descriptor) {
    return deltas.count(intents, descriptor);
  }

  private ByteBuffer metadata() {
    ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    return metadata != null && BTreeRootPage.validate(metadata).isOk() ? metadata : null;
  }
}
