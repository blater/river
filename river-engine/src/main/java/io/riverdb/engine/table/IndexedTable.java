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
import io.riverdb.tx.CommitSequenceSource;
import java.nio.ByteBuffer;

/** Single-owner heap plus authoritative unique long-key B+tree committed atomically. */
public final class IndexedTable implements CommitSequenceSource {
  static final int MUTATION_INSERT = 1;
  static final int MUTATION_UPDATE = 2;
  static final int MUTATION_DELETE = 3;

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

  public synchronized StatusCode insert(
      long transactionId,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    return insertCommitted(
        transactionId, store.nextCommitSequence(), key, row, result);
  }

  public synchronized StatusCode commitInsert(
      long transactionId,
      long key,
      ByteBuffer row,
      IndexedCommitResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long commitSequence = store.nextCommitSequence();
    StatusCode status = insertCommitted(
        transactionId, commitSequence, key, row, heapInsert);
    if (status.isOk()) {
      result.set(heapInsert.rowId(), commitSequence);
    }
    return status;
  }

  /** Atomically commits a bounded write set; one-row transactions retain the compact WAL path. */
  public synchronized StatusCode commitInserts(
      long transactionId,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      IndexedCommitResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID
        || keys == null
        || rows == null
        || rowStride <= 0
        || rowLengths == null
        || insertCount <= 0
        || insertCount > keys.length
        || insertCount > rowLengths.length
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (insertCount == 1) {
      rows.position(0);
      rows.limit(rowLengths[0]);
      return commitInsert(transactionId, keys[0], rows, result);
    }
    long commitSequence = store.nextCommitSequence();
    StatusCode status = store.commitInsertBatch(
        transactionId,
        commitSequence,
        keys,
        rows,
        rowStride,
        rowLengths,
        insertCount,
        heapInsert);
    if (status.isOk()) {
      result.set(heapInsert.rowId(), commitSequence);
      return StatusCode.OK;
    }
    if (status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    int lastRowId = 0;
    for (int index = 0; index < insertCount; index++) {
      int rowBytes = rowLengths[index];
      int rowOffset = index * rowStride;
      long key = keys[index];
      if (key == Long.MAX_VALUE
          || rowBytes <= 0
          || rowBytes > rowStride
          || rows.limit() - rowOffset < rowBytes) {
        store.cancelOperation();
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int leafPageId = findOperationLeafPageId(key);
      if (leafPageId <= 0) {
        store.cancelOperation();
        return StatusCode.CORRUPTION;
      }
      ByteBuffer leaf = store.stageExisting(leafPageId);
      if (leaf == null) {
        store.cancelOperation();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = BTreePage.lookupLeaf(leaf, key, indexLookup);
      if (status.isOk()) {
        store.cancelOperation();
        return StatusCode.CONFLICT;
      }
      if (status != StatusCode.CONFLICT) {
        store.cancelOperation();
        return status;
      }
      status = store.stageRow(rows, rowOffset, rowBytes, heapInsert);
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
      lastRowId = heapInsert.rowId();
    }
    status = store.commit(transactionId, commitSequence);
    if (status.isOk()) {
      result.set(lastRowId, commitSequence);
    }
    return status;
  }

  public synchronized StatusCode commitMutations(
      long transactionId,
      int[] operations,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      IndexedCommitResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long commitSequence = store.nextCommitSequence();
    StatusCode status = store.commitMutationBatch(
        transactionId,
        commitSequence,
        operations,
        keys,
        previousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        heapInsert);
    if (status.isOk()) {
      result.set(heapInsert.rowId(), commitSequence);
    }
    return status;
  }

  /** Compacts obsolete row versions; caller must hold the transaction publication barrier. */
  public synchronized StatusCode vacuum(
      long transactionId,
      IndexedVacuumResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return store.commitVacuum(
        transactionId,
        store.nextCommitSequence(),
        result);
  }

  public synchronized StatusCode vacuumPreflight() {
    return store.vacuumPreflight();
  }

  public synchronized StatusCode insertCommitted(
      long transactionId,
      long commitSequence,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID
        || commitSequence <= 0
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
    ByteBuffer currentLeaf = store.currentPayload(leafPageId);
    if (!store.canAppendRow(row.remaining())) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (BTreePage.entryCount(currentLeaf) < BTreePage.MAX_ENTRIES) {
      return store.commitInsert(
          transactionId, commitSequence, key, row, result);
    }
    status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer leaf = store.stageExisting(leafPageId);
    if (leaf == null) {
      store.cancelOperation();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = store.stageRow(row, row.position(), row.remaining(), heapInsert);
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
    status = store.commit(transactionId, commitSequence);
    if (status.isOk()) {
      result.setRowId(heapInsert.rowId());
    }
    return status;
  }

  public synchronized StatusCode fetchByKey(long key, HeapRowResult result) {
    return fetchByKeyAt(store.currentCommitSequence(), key, result);
  }

  public synchronized StatusCode fetchByKeyAt(
      long visibleCommitSequence,
      long key,
      HeapRowResult result) {
    if (key == Long.MAX_VALUE || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = lookupRowId(key);
    if (!status.isOk()) {
      return status;
    }
    int rowId = indexLookup.rowId();
    while (rowId > 0) {
      long rowCommitSequence = store.rowCommitSequence(rowId);
      if (rowCommitSequence <= 0) {
        return StatusCode.CORRUPTION;
      }
      if (rowCommitSequence <= visibleCommitSequence) {
        if (store.isDeletedRow(rowId)) {
          result.reset();
          return StatusCode.CONFLICT;
        }
        return store.fetchRow(rowId, result);
      }
      rowId = store.previousRowId(rowId);
    }
    result.reset();
    return StatusCode.CONFLICT;
  }

  public synchronized StatusCode beginScan(
      long visibleCommitSequence,
      long lowerKey,
      long upperKey,
      IndexedScanCursor cursor) {
    if (visibleCommitSequence < 0
        || lowerKey >= upperKey
        || upperKey == Long.MIN_VALUE
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = findLeafPageId(lowerKey);
    if (leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    return cursor.claim(this, visibleCommitSequence, lowerKey, upperKey, leafPageId);
  }

  public synchronized StatusCode nextScan(
      IndexedScanCursor cursor,
      IndexedScanResult result) {
    if (cursor == null || !cursor.isOwnedBy(this) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    while (cursor.leafPageId() > 0) {
      ByteBuffer leaf = store.currentPayload(cursor.leafPageId());
      if (leaf == null || BTreePage.type(leaf) != BTreePage.TYPE_LEAF) {
        return StatusCode.CORRUPTION;
      }
      int entryCount = BTreePage.entryCount(leaf);
      while (cursor.entryIndex() < entryCount) {
        int entry = cursor.entryIndex();
        cursor.advanceEntry();
        long key = BTreePage.keyAt(leaf, entry);
        if (key < cursor.lowerKey()) {
          continue;
        }
        if (key >= cursor.upperKey()) {
          cursor.advanceLeaf(0);
          return StatusCode.CONFLICT;
        }
        int rowId = BTreePage.valueAt(leaf, entry);
        while (rowId > 0
            && store.rowCommitSequence(rowId) > cursor.visibleCommitSequence()) {
          rowId = store.previousRowId(rowId);
        }
        if (rowId <= 0 || store.isDeletedRow(rowId)) {
          continue;
        }
        StatusCode status = store.fetchRow(rowId, result.row());
        if (!status.isOk()) {
          return status;
        }
        result.set(key);
        return StatusCode.OK;
      }
      cursor.advanceLeaf(BTreePage.rightSiblingPageId(leaf));
    }
    return StatusCode.CONFLICT;
  }

  public synchronized StatusCode closeScan(IndexedScanCursor cursor) {
    if (cursor == null || !cursor.isOwnedBy(this)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    cursor.complete();
    return StatusCode.OK;
  }

  public synchronized StatusCode prepareMutation(
      long visibleCommitSequence,
      long key,
      IndexedMutationTarget result) {
    if (visibleCommitSequence < 0 || key == Long.MAX_VALUE || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = lookupRowId(key);
    if (!status.isOk()) {
      return status;
    }
    int latestRowId = indexLookup.rowId();
    long latestCommitSequence = store.rowCommitSequence(latestRowId);
    if (latestCommitSequence <= 0) {
      return StatusCode.CORRUPTION;
    }
    if (latestCommitSequence > visibleCommitSequence || store.isDeletedRow(latestRowId)) {
      return StatusCode.CONFLICT;
    }
    result.set(latestRowId);
    return StatusCode.OK;
  }

  public synchronized StatusCode prepareInsert(
      long visibleCommitSequence,
      long key,
      IndexedMutationTarget result) {
    if (visibleCommitSequence < 0 || key == Long.MAX_VALUE || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = lookupRowId(key);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.OK;
    }
    if (!status.isOk()) {
      return status;
    }
    int latestRowId = indexLookup.rowId();
    long latestCommitSequence = store.rowCommitSequence(latestRowId);
    if (latestCommitSequence <= 0) {
      return StatusCode.CORRUPTION;
    }
    if (latestCommitSequence > visibleCommitSequence
        || !store.isDeletedRow(latestRowId)) {
      return StatusCode.CONFLICT;
    }
    result.set(latestRowId);
    return StatusCode.OK;
  }

  public synchronized int rowCount() {
    return store.rowCount();
  }

  public int rootPageId() {
    return BTreeRootPage.rootPageId(store.currentPayload(ROOT_META_PAGE_ID));
  }

  public int pageCount() {
    return store.highestPageId();
  }

  public synchronized long visibleCommitSequence() {
    return store.currentCommitSequence();
  }

  @Override
  public synchronized long currentCommitSequence() {
    return store.currentCommitSequence();
  }

  public synchronized long nextCommitSequence() {
    return store.nextCommitSequence();
  }

  public synchronized long nextTransactionId() {
    return store.nextTransactionId();
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
    ByteBuffer currentMetadata = store.operationPayload(ROOT_META_PAGE_ID);
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
    if (BTreePage.type(store.operationPayload(currentRootPageId)) == BTreePage.TYPE_LEAF) {
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

  private int findOperationLeafPageId(long key) {
    ByteBuffer metadata = store.operationPayload(ROOT_META_PAGE_ID);
    if (metadata == null) {
      return 0;
    }
    int rootPageId = BTreeRootPage.rootPageId(metadata);
    ByteBuffer root = store.operationPayload(rootPageId);
    if (root == null) {
      return 0;
    }
    if (BTreePage.type(root) == BTreePage.TYPE_LEAF) {
      return rootPageId;
    }
    int leafPageId = BTreePage.childForKey(root, key);
    ByteBuffer leaf = store.operationPayload(leafPageId);
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
      status = HeapPage.isHeap(page)
          ? HeapPage.validate(page) : BTreePage.validate(page);
      if (!status.isOk()) {
        return status;
      }
    }
    int rootPageId = BTreeRootPage.rootPageId(metadata);
    if (rootPageId < INITIAL_LEAF_PAGE_ID || rootPageId >= nextPageId) {
      return StatusCode.CORRUPTION;
    }
    return validateTreeLinks(store, rootPageId);
  }

  private static StatusCode validateTreeLinks(
      IndexedPageStore store,
      int rootPageId) {
    ByteBuffer root = store.currentPayload(rootPageId);
    int heapRows = store.rowCount();
    if (BTreePage.type(root) == BTreePage.TYPE_LEAF) {
      if (BTreePage.rightSiblingPageId(root) != 0
          || BTreePage.highKey(root) != Long.MAX_VALUE) {
        return StatusCode.CORRUPTION;
      }
      int versionRows = versionRowsInLeaf(store, root, heapRows);
      return versionRows == heapRows ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    int separatorCount = BTreePage.entryCount(root);
    int childPageId = BTreePage.firstChildPageId(root);
    int versionRows = 0;
    for (int childIndex = 0; childIndex <= separatorCount; childIndex++) {
      ByteBuffer child = store.currentPayload(childPageId);
      if (child == null || BTreePage.type(child) != BTreePage.TYPE_LEAF) {
        return StatusCode.CORRUPTION;
      }
      int childEntries = BTreePage.entryCount(child);
      if (childEntries <= 0) {
        return StatusCode.CORRUPTION;
      }
      int childVersionRows = versionRowsInLeaf(store, child, heapRows);
      if (childVersionRows < 0) {
        return StatusCode.CORRUPTION;
      }
      versionRows += childVersionRows;
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
    return versionRows == heapRows ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static int versionRowsInLeaf(
      IndexedPageStore store,
      ByteBuffer leaf,
      int heapRows) {
    int versionRows = 0;
    int entryCount = BTreePage.entryCount(leaf);
    for (int entry = 0; entry < entryCount; entry++) {
      int rowId = BTreePage.valueAt(leaf, entry);
      long newerCommitSequence = 0;
      while (rowId > 0) {
        long commitSequence = store.rowCommitSequence(rowId);
        if (rowId > heapRows
            || commitSequence <= 0
            || (newerCommitSequence != 0
                && commitSequence >= newerCommitSequence)) {
          return -1;
        }
        int previousRowId = store.previousRowId(rowId);
        if (previousRowId < 0 || previousRowId >= rowId) {
          return -1;
        }
        versionRows++;
        if (versionRows > heapRows) {
          return -1;
        }
        newerCommitSequence = commitSequence;
        rowId = previousRowId;
      }
    }
    return versionRows;
  }
}
