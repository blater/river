package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.page.IndexedPageStore;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreeLookupResult;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.BTreeSplitResult;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Single-owner heap plus authoritative unique long-key B+tree committed atomically. */
public final class IndexedTable {
  private static final int HEAP_PAGE_ID = 1;
  private static final int ROOT_META_PAGE_ID = 2;
  private static final int INITIAL_LEAF_PAGE_ID = 3;
  private static final long BOOTSTRAP_TRANSACTION_ID = 1;

  private final IndexedPageStore store;
  private final HeapInsertResult heapInsert = new HeapInsertResult();
  private final BTreeLookupResult indexLookup = new BTreeLookupResult();
  private final BTreeSplitResult splitResult = new BTreeSplitResult();

  private IndexedTable(IndexedPageStore pageStore) {
    store = pageStore;
  }

  public static StatusCode create(
      IndexedPageStore store,
      IndexedTableOpenResult result) {
    if (store == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer heap = store.stageNew(HEAP_PAGE_ID);
    ByteBuffer metadata = store.stageNew(ROOT_META_PAGE_ID);
    ByteBuffer leaf = store.stageNew(INITIAL_LEAF_PAGE_ID);
    if (heap == null || metadata == null || leaf == null) {
      store.cancelOperation();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = HeapPage.initialize(heap);
    if (status.isOk()) {
      status = BTreeRootPage.initialize(metadata, INITIAL_LEAF_PAGE_ID, 4);
    }
    if (status.isOk()) {
      status = BTreePage.initializeLeaf(leaf, 0, Long.MAX_VALUE);
    }
    if (status.isOk()) {
      status = store.commit(
          BOOTSTRAP_TRANSACTION_ID, store.nextCommitSequence());
    }
    if (status.isOk()) {
      status = store.flush();
    }
    if (!status.isOk()) {
      store.cancelOperation();
      return status;
    }
    result.set(new IndexedTable(store));
    return StatusCode.OK;
  }

  public static StatusCode open(
      IndexedPageStore store,
      IndexedTableOpenResult result) {
    if (store == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = validateStructure(store);
    if (status.isOk()) {
      result.set(new IndexedTable(store));
    }
    return status;
  }

  public StatusCode insert(
      long transactionId,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID
        || key == Long.MAX_VALUE
        || row == null
        || !row.hasRemaining()
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = lookupRowId(key);
    if (status.isOk()) {
      return StatusCode.CONFLICT;
    }
    if (status != StatusCode.CONFLICT) {
      return status;
    }
    int leafPageId = findLeafPageId(key);
    if (leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer currentHeap = store.currentPayload(HEAP_PAGE_ID);
    ByteBuffer currentLeaf = store.currentPayload(leafPageId);
    if (!HeapPage.canInsert(currentHeap, row.remaining())) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (BTreePage.entryCount(currentLeaf) < BTreePage.MAX_ENTRIES) {
      return store.commitInsert(
          transactionId, store.nextCommitSequence(), key, row, result);
    }
    status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer heap = store.stageExisting(HEAP_PAGE_ID);
    ByteBuffer leaf = store.stageExisting(leafPageId);
    if (heap == null || leaf == null) {
      store.cancelOperation();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = HeapPage.insert(heap, row, heapInsert);
    if (!status.isOk()) {
      store.cancelOperation();
      return status;
    }
    status = BTreePage.insertLeaf(leaf, key, heapInsert.rowId());
    if (status == StatusCode.RESOURCE_EXHAUSTED) {
      status = splitAndInsert(leafPageId, leaf, key, heapInsert.rowId());
    }
    if (!status.isOk()) {
      store.cancelOperation();
      return status;
    }
    status = store.commit(transactionId, store.nextCommitSequence());
    if (status.isOk()) {
      result.setRowId(heapInsert.rowId());
    }
    return status;
  }

  public StatusCode fetchByKey(long key, HeapRowResult result) {
    if (key == Long.MAX_VALUE || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = lookupRowId(key);
    if (!status.isOk()) {
      return status;
    }
    return HeapPage.fetch(store.currentPayload(HEAP_PAGE_ID), indexLookup.rowId(), result);
  }

  public int rowCount() {
    return HeapPage.rowCount(store.currentPayload(HEAP_PAGE_ID));
  }

  public int rootPageId() {
    return BTreeRootPage.rootPageId(store.currentPayload(ROOT_META_PAGE_ID));
  }

  public int pageCount() {
    return store.highestPageId();
  }

  public long stagedCopyBytes() {
    return store.stagedCopyBytes();
  }

  public long walCopyBytes() {
    return store.walCopyBytes();
  }

  public StatusCode flush() {
    return store.flush();
  }

  public StatusCode close() {
    return store.close();
  }

  private StatusCode splitAndInsert(
      int leftPageId,
      ByteBuffer left,
      long key,
      int rowId) {
    ByteBuffer currentMetadata = store.currentPayload(ROOT_META_PAGE_ID);
    int currentRootPageId = BTreeRootPage.rootPageId(currentMetadata);
    ByteBuffer metadata = store.stageExisting(ROOT_META_PAGE_ID);
    if (metadata == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int rightPageId = BTreeRootPage.allocatePage(metadata);
    ByteBuffer right = store.stageNew(rightPageId);
    if (right == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = BTreePage.splitLeaf(
        left, right, rightPageId, key, rowId, splitResult);
    if (!status.isOk()) {
      return status;
    }
    if (BTreePage.type(store.currentPayload(currentRootPageId)) == BTreePage.TYPE_LEAF) {
      int newRootPageId = BTreeRootPage.allocatePage(metadata);
      ByteBuffer root = store.stageNew(newRootPageId);
      if (root == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = BTreePage.initializeInternal(root, leftPageId);
      if (status.isOk()) {
        status = BTreePage.insertInternal(root, splitResult.separatorKey(), rightPageId);
      }
      if (status.isOk()) {
        BTreeRootPage.publishRoot(metadata, newRootPageId);
      }
      return status;
    }
    ByteBuffer root = store.stageExisting(currentRootPageId);
    if (root == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return BTreePage.insertInternal(root, splitResult.separatorKey(), rightPageId);
  }

  private StatusCode lookupRowId(long key) {
    int leafPageId = findLeafPageId(key);
    if (leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    return BTreePage.lookupLeaf(store.currentPayload(leafPageId), key, indexLookup);
  }

  private int findLeafPageId(long key) {
    ByteBuffer metadata = store.currentPayload(ROOT_META_PAGE_ID);
    if (metadata == null) {
      return 0;
    }
    int rootPageId = BTreeRootPage.rootPageId(metadata);
    ByteBuffer root = store.currentPayload(rootPageId);
    if (root == null) {
      return 0;
    }
    if (BTreePage.type(root) == BTreePage.TYPE_LEAF) {
      return rootPageId;
    }
    int leafPageId = BTreePage.childForKey(root, key);
    ByteBuffer leaf = store.currentPayload(leafPageId);
    return leaf != null && BTreePage.type(leaf) == BTreePage.TYPE_LEAF ? leafPageId : 0;
  }

  private static StatusCode validateStructure(IndexedPageStore store) {
    ByteBuffer heap = store.currentPayload(HEAP_PAGE_ID);
    ByteBuffer metadata = store.currentPayload(ROOT_META_PAGE_ID);
    if (heap == null || metadata == null) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = HeapPage.validate(heap);
    if (status.isOk()) {
      status = BTreeRootPage.validate(metadata);
    }
    if (!status.isOk()) {
      return status;
    }
    int nextPageId = BTreeRootPage.nextPageId(metadata);
    if (nextPageId > IndexedPageStore.MAX_PAGES + 1) {
      return StatusCode.CORRUPTION;
    }
    for (int pageId = INITIAL_LEAF_PAGE_ID; pageId < nextPageId; pageId++) {
      ByteBuffer page = store.currentPayload(pageId);
      if (page == null) {
        return StatusCode.CORRUPTION;
      }
      status = BTreePage.validate(page);
      if (!status.isOk()) {
        return status;
      }
    }
    int rootPageId = BTreeRootPage.rootPageId(metadata);
    if (rootPageId < INITIAL_LEAF_PAGE_ID || rootPageId >= nextPageId) {
      return StatusCode.CORRUPTION;
    }
    return validateTreeLinks(store, heap, rootPageId);
  }

  private static StatusCode validateTreeLinks(
      IndexedPageStore store,
      ByteBuffer heap,
      int rootPageId) {
    ByteBuffer root = store.currentPayload(rootPageId);
    int heapRows = HeapPage.rowCount(heap);
    if (BTreePage.type(root) == BTreePage.TYPE_LEAF) {
      return BTreePage.rightSiblingPageId(root) == 0
          && BTreePage.highKey(root) == Long.MAX_VALUE
          && BTreePage.entryCount(root) == heapRows
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    int separatorCount = BTreePage.entryCount(root);
    int childPageId = BTreePage.firstChildPageId(root);
    int indexedRows = 0;
    for (int childIndex = 0; childIndex <= separatorCount; childIndex++) {
      ByteBuffer child = store.currentPayload(childPageId);
      if (child == null || BTreePage.type(child) != BTreePage.TYPE_LEAF) {
        return StatusCode.CORRUPTION;
      }
      int childEntries = BTreePage.entryCount(child);
      if (childEntries <= 0) {
        return StatusCode.CORRUPTION;
      }
      indexedRows += childEntries;
      if (childIndex == separatorCount) {
        if (BTreePage.rightSiblingPageId(child) != 0
            || BTreePage.highKey(child) != Long.MAX_VALUE) {
          return StatusCode.CORRUPTION;
        }
        continue;
      }
      long separator = BTreePage.keyAt(root, childIndex);
      int rightChildPageId = BTreePage.valueAt(root, childIndex);
      ByteBuffer rightChild = store.currentPayload(rightChildPageId);
      if (rightChild == null
          || BTreePage.type(rightChild) != BTreePage.TYPE_LEAF
          || BTreePage.rightSiblingPageId(child) != rightChildPageId
          || BTreePage.highKey(child) != separator
          || BTreePage.entryCount(rightChild) <= 0
          || BTreePage.keyAt(rightChild, 0) != separator) {
        return StatusCode.CORRUPTION;
      }
      childPageId = rightChildPageId;
    }
    return indexedRows == heapRows ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
