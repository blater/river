package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.storage.btree.BTreeLookupResult;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.BTreeSplitResult;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** Allocation-conscious indexed-table behavior over one owned page set. */
final class IndexedTableKernel {
  static final int HEAP_PAGE_ID = 1;
  static final int ROOT_META_PAGE_ID = 2;
  static final int INITIAL_LEAF_PAGE_ID = 3;
  private static final int MAXIMUM_TREE_HEIGHT = 8;

  private final IndexedPageSet pages;
  private final HeapInsertResult heapInsert = new HeapInsertResult();
  private final IndexedVersionState versions = new IndexedVersionState();
  private final PagedIntArray rowPageIds = new PagedIntArray(IndexedTableLimits.MAX_ROWS);
  private final PagedIntArray rowSlots = new PagedIntArray(IndexedTableLimits.MAX_ROWS);
  private final BTreeLookupResult indexLookup = new BTreeLookupResult();
  private final IndexedTableValidator validator;
  private final IndexedMutationValidator mutationValidator;
  private final IndexedTableWalApplier walApplier;
  private final IndexedTableVacuum vacuum;
  private final IndexedTableIndexTree indexTree;
  private final IndexedTableMutationStager mutationStager;
  private StatusCode stageOperationHeapStatus = StatusCode.OK;
  private int rowCount;
  private int lastHeapPageId = HEAP_PAGE_ID;
  private int operationRowCount;
  private int operationLastHeapPageId = HEAP_PAGE_ID;

  int operationVersionCount() {
    return versions.operationCount();
  }

  int operationRowCount() {
    return operationRowCount;
  }

  int operationPreviousRowId(int index) {
    return versions.operationPreviousRow(index);
  }

  boolean operationDeleted(int index) {
    return versions.operationDeleted(index);
  }

  int lastHeapPageId() {
    return lastHeapPageId;
  }

  HeapInsertResult heapInsertResult() {
    return heapInsert;
  }

  void recordVacuumDeleted(int rowId, boolean deleted) {
    versions.recordVacuumDeleted(rowId, deleted);
  }

  StatusCode fetchRow(int rowId, HeapRowResult result) {
    if (result == null || rowId <= 0 || rowId > rowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return HeapPage.fetch(
        pages.currentPayloadUnchecked(rowPageIds.get(rowId)), rowSlots.get(rowId), result);
  }

  int rowLength(int rowId) {
    return rowId > 0 && rowId <= rowCount
        ? HeapPage.rowLength(
            pages.currentPayloadUnchecked(rowPageIds.get(rowId)), rowSlots.get(rowId)) : 0;
  }

  StatusCode copyRowTo(int rowId, ByteBuffer destination, int destinationOffset) {
    if (rowId <= 0 || rowId > rowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return HeapPage.copyRowTo(
        pages.currentPayloadUnchecked(rowPageIds.get(rowId)),
        rowSlots.get(rowId),
        destination,
        destinationOffset);
  }

  long rowCommitSequence(int rowId) {
    return versions.commitSequence(rowId, rowCount);
  }

  int previousRowId(int rowId) {
    return versions.previousRow(rowId, rowCount);
  }

  boolean isDeletedRow(int rowId) {
    return versions.isDeleted(rowId, rowCount);
  }

  void beginOperationState() {
    operationRowCount = rowCount;
    versions.beginOperation();
    operationLastHeapPageId = lastHeapPageId;
  }

  StatusCode stageVersionRow(
      ByteBuffer source,
      int sourceOffset,
      int rowBytes,
      int previousRowId,
      boolean deleted,
      HeapInsertResult result) {
    if (source == null
        || sourceOffset < 0
        || rowBytes <= 0
        || source.limit() - sourceOffset < rowBytes
        || result == null
        || operationRowCount >= IndexedTableLimits.MAX_ROWS
        || !versions.canStage(previousRowId, deleted, rowCount)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    ByteBuffer heap = pages.operationPayload(operationLastHeapPageId);
    if (heap == null || !HeapPage.isHeap(heap)) {
      return StatusCode.CORRUPTION;
    }
    heap = stageOperationHeap(heap, rowBytes);
    if (heap == null) return stageOperationHeapStatus;
    StatusCode status = HeapPage.insertFrom(heap, source, sourceOffset, rowBytes, heapInsert);
    if (status.isOk()) {
      operationRowCount++;
      versions.stage(previousRowId, deleted);
      result.setRowId(operationRowCount);
    }
    return status;
  }

  boolean canAppendRow(int rowBytes) {
    if (rowBytes <= 0
        || rowCount >= IndexedTableLimits.MAX_ROWS
        || !pages.isPresent(lastHeapPageId)) {
      return false;
    }
    if (HeapPage.canInsert(pages.currentPayloadUnchecked(lastHeapPageId), rowBytes)) {
      return true;
    }
    ByteBuffer metadata = pages.currentPayloadUnchecked(ROOT_META_PAGE_ID);
    return rowBytes + HeapPage.SLOT_BYTES
            <= pages.currentPayloadUnchecked(HEAP_PAGE_ID).limit() - HeapPage.HEADER_BYTES
        && BTreeRootPage.nextPageId(metadata) <= IndexedTableLimits.MAX_PAGES;
  }

  StatusCode appendCurrentRow(
      ByteBuffer source,
      int sourceOffset,
      int rowBytes,
      int expectedRowId,
      long recordStart,
      long recordEnd,
      long commitSequence,
      int previousRowId,
      boolean deleted) {
    if (expectedRowId != rowCount + 1 || expectedRowId > IndexedTableLimits.MAX_ROWS) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer heap = pages.currentPayloadUnchecked(lastHeapPageId);
    if (!HeapPage.canInsert(heap, rowBytes)) {
      ByteBuffer metadata = pages.currentPayloadUnchecked(ROOT_META_PAGE_ID);
      if (BTreeRootPage.nextPageId(metadata) > IndexedTableLimits.MAX_PAGES) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      int pageId = BTreeRootPage.allocatePage(metadata);
      if (pages.isPresent(pageId)) {
        return StatusCode.CORRUPTION;
      }
      pages.ensureBuffers(pageId);
      heap = pages.currentPayloadUnchecked(pageId);
      StatusCode status = HeapPage.initialize(heap);
      if (!status.isOk()) {
        return status;
      }
      pages.installPresent(pageId);
      lastHeapPageId = pageId;
      pages.markCurrentChanged(ROOT_META_PAGE_ID, recordStart, recordEnd);
    }
    StatusCode status = HeapPage.insertFrom(heap, source, sourceOffset, rowBytes, heapInsert);
    if (!status.isOk()) {
      return status;
    }
    rowCount++;
    rowPageIds.set(rowCount, lastHeapPageId);
    rowSlots.set(rowCount, heapInsert.rowId());
    versions.recordCommitted(rowCount, commitSequence, previousRowId, deleted);
    pages.markCurrentChanged(lastHeapPageId, recordStart, recordEnd);
    return StatusCode.OK;
  }

  StatusCode rebuildRowLocations() {
    int rebuiltRows = 0;
    int rebuiltLastHeap = 0;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isPresent(pageId) || !HeapPage.isHeap(pages.currentPayloadUnchecked(pageId))) {
        continue;
      }
      int pageRows = HeapPage.rowCount(pages.currentPayloadUnchecked(pageId));
      if (pageRows < 0 || rebuiltRows > IndexedTableLimits.MAX_ROWS - pageRows) {
        return StatusCode.CORRUPTION;
      }
      for (int slot = 1; slot <= pageRows; slot++) {
        rebuiltRows++;
        rowPageIds.set(rebuiltRows, pageId);
        rowSlots.set(rebuiltRows, slot);
      }
      rebuiltLastHeap = pageId;
    }
    if (rebuiltLastHeap == 0) {
      return StatusCode.CORRUPTION;
    }
    for (int rowId = rebuiltRows + 1; rowId <= rowCount; rowId++) {
      rowPageIds.set(rowId, 0);
      rowSlots.set(rowId, 0);
    }
    rowCount = rebuiltRows;
    lastHeapPageId = rebuiltLastHeap;
    return StatusCode.OK;
  }

  void recordNewRowCommits(int previousRowCount, long commitSequence) {
    if (!pages.isPresent(HEAP_PAGE_ID)) {
      return;
    }
    versions.recordNewRows(previousRowCount, rowCount, commitSequence);
  }

  void recordOperationVersions(int previousRowCount, long commitSequence) {
    versions.recordOperation(previousRowCount, commitSequence);
  }

  void clearOperationVersions() {
    versions.clearOperation();
  }

  void loadCheckpointVersions(io.riverdb.engine.checkpoint.CheckpointState checkpoint) {
    versions.load(checkpoint);
  }

  StatusCode applyRecoveredVersions(
      ByteBuffer payload,
      int versionOffset,
      int previousRowCount,
      int versionCount,
      long commitSequence) {
    return versions.applyRecovered(
        payload, versionOffset, previousRowCount, versionCount, commitSequence);
  }

  void publishVacuumVersions(int retainedRows, long commitSequence) {
    versions.publishVacuum(retainedRows, commitSequence);
  }

  void cancelVacuumVersions(int appliedRows) {
    versions.cancelVacuum(appliedRows);
  }

  int indexedEntryCount() {
    int count = 0;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isPresent(pageId) || pageId == ROOT_META_PAGE_ID) {
        continue;
      }
      ByteBuffer page = pages.currentPayloadUnchecked(pageId);
      if (HeapPage.isHeap(page)) {
        continue;
      }
      int type = BTreePage.type(page);
      if (type == BTreePage.TYPE_LEAF) {
        count += BTreePage.entryCount(page);
      } else if (type != BTreePage.TYPE_INTERNAL) {
        return -1;
      }
    }
    return count;
  }

  int vacuumChunkCount() {
    return vacuum.chunkCount();
  }

  int vacuumChunkRowCount(int firstRow) {
    return vacuum.chunkRowCount(firstRow);
  }

  int vacuumChunkPayloadBytes(int firstRow, int rowLimit) {
    return vacuum.chunkPayloadBytes(firstRow, rowLimit);
  }

  StatusCode encodeVacuumChunk(
      ByteBuffer payload,
      int retainedRows,
      int firstRow,
      int rowLimit,
      int chunk,
      int chunkCount,
      int payloadBytes) {
    return vacuum.encodeChunk(
        payload, retainedRows, firstRow, rowLimit, chunk, chunkCount, payloadBytes);
  }

  StatusCode beginVacuumApply() {
    return vacuum.beginApply();
  }

  StatusCode applyVacuumEntry(ByteBuffer payload, int entryOffset, int compactedRowId) {
    return vacuum.applyEntry(payload, entryOffset, compactedRowId);
  }

  void resetVacuumApply() {
    vacuum.resetApply();
  }

  boolean canAppendRows(int[] rowLengths, int count) {
    if (rowCount > IndexedTableLimits.MAX_ROWS - count || !pages.isPresent(lastHeapPageId)) {
      return false;
    }
    int available = HeapPage.availableBytes(pages.currentPayloadUnchecked(lastHeapPageId));
    int newPages = 0;
    int pageCapacity = pages.currentPayloadUnchecked(HEAP_PAGE_ID).limit() - HeapPage.HEADER_BYTES;
    for (int index = 0; index < count; index++) {
      int required = HeapPage.SLOT_BYTES + rowLengths[index];
      if (required > pageCapacity) {
        return false;
      }
      if (required > available) {
        newPages++;
        available = pageCapacity;
      }
      available -= required;
    }
    return BTreeRootPage.nextPageId(pages.currentPayloadUnchecked(ROOT_META_PAGE_ID))
        <= IndexedTableLimits.MAX_PAGES - newPages + 1;
  }

  boolean canAppendEncodedRows(
      ByteBuffer payload,
      int firstEntryOffset,
      int count,
      int rowLengthOffset,
      int entryBytes) {
    if (rowCount > IndexedTableLimits.MAX_ROWS - count || !pages.isPresent(lastHeapPageId)) {
      return false;
    }
    int available = HeapPage.availableBytes(pages.currentPayloadUnchecked(lastHeapPageId));
    int newPages = 0;
    int pageCapacity = pages.currentPayloadUnchecked(HEAP_PAGE_ID).limit() - HeapPage.HEADER_BYTES;
    int entryOffset = firstEntryOffset;
    for (int index = 0; index < count; index++) {
      int rowBytes = IndexedWalCodec.encodedRowBytes(payload, entryOffset, rowLengthOffset);
      int required = HeapPage.SLOT_BYTES + rowBytes;
      if (required > pageCapacity) {
        return false;
      }
      if (required > available) {
        newPages++;
        available = pageCapacity;
      }
      available -= required;
      entryOffset += entryBytes + rowBytes;
    }
    return BTreeRootPage.nextPageId(pages.currentPayloadUnchecked(ROOT_META_PAGE_ID))
        <= IndexedTableLimits.MAX_PAGES - newPages + 1;
  }

  int currentHeapAvailableBytes() {
    return HeapPage.availableBytes(pages.currentPayloadUnchecked(lastHeapPageId));
  }

  StatusCode validateCurrentPage(int pageId) {
    return validator.validateCurrentPage(pageId);
  }

  StatusCode validateAppliedPages(int[] pageIds, int pageCount) {
    return validator.validateAppliedPages(pageIds, pageCount);
  }

  IndexedTableKernel(IndexedPageSet pageSet) {
    pages = pageSet;
    validator = new IndexedTableValidator(
        pages, versions);
    mutationValidator = new IndexedMutationValidator(pages);
    walApplier = new IndexedTableWalApplier(this, pages);
    vacuum = new IndexedTableVacuum(this, pages, versions, heapInsert);
    indexTree = new IndexedTableIndexTree(pages);
    mutationStager = new IndexedTableMutationStager(this, pages);
  }

  StatusCode initializePages() {
    ByteBuffer heap = pages.stageNew(HEAP_PAGE_ID, IndexedTableLimits.MAX_CHANGED_PAGES);
    ByteBuffer metadata = pages.stageNew(ROOT_META_PAGE_ID, IndexedTableLimits.MAX_CHANGED_PAGES);
    ByteBuffer leaf = pages.stageNew(INITIAL_LEAF_PAGE_ID, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (heap == null || metadata == null || leaf == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = HeapPage.initialize(heap);
    if (status.isOk()) {
      status = BTreeRootPage.initialize(metadata, INITIAL_LEAF_PAGE_ID, 4);
    }
    if (status.isOk()) {
      status = BTreePage.initializeLeaf(leaf, 0);
    }
    return status;
  }

  StatusCode validate() {
    return validator.validate(rowCount);
  }

  StatusCode stageInsertBatch(
      int[] spaces,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      HeapInsertResult result) {
    return mutationStager.stageInsertBatch(
        spaces, keys, rows, rowStride, rowLengths, insertCount, result);
  }

  StatusCode stageMutationBatch(
      int[] operations,
      int[] spaces,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      HeapInsertResult result) {
    return mutationStager.stageMutationBatch(
        operations, spaces, keys, previousRowIds, rows, rowStride,
        rowLengths, mutationCount, result);
  }

  StatusCode stageMutationBatch(
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    return mutationStager.stagePendingMutations(mutations, result);
  }

  StatusCode stageInsertBatch(
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    return mutationStager.stagePendingInserts(mutations, result);
  }

  boolean canAppendRows(PendingMutationBuffer mutations) {
    int required = 0;
    for (int index = 0; index < mutations.count(); index++) {
      required += HeapPage.SLOT_BYTES + mutations.rowLengthAt(index);
    }
    return currentHeapAvailableBytes() >= required;
  }

  StatusCode stageVersionRow(
      PendingMutationBuffer mutations,
      int index,
      int previousRowId,
      boolean deleted,
      HeapInsertResult result) {
    int rowBytes = mutations.rowLengthAt(index);
    if (!canAppendRow(rowBytes)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (operationRowCount >= IndexedTableLimits.MAX_ROWS
        || !versions.canStage(previousRowId, deleted, rowCount)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    ByteBuffer currentHeap = pages.operationPayload(operationLastHeapPageId);
    if (currentHeap == null || !HeapPage.isHeap(currentHeap)) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer heap = stageOperationHeap(currentHeap, rowBytes);
    if (heap == null) {
      return stageOperationHeapStatus;
    }
    StatusCode status = mutations.insertRowInto(index, heap, heapInsert);
    if (status.isOk()) {
      operationRowCount++;
      versions.stage(previousRowId, deleted);
      result.setRowId(operationRowCount);
    }
    return status;
  }

  private ByteBuffer stageOperationHeap(ByteBuffer current, int rowBytes) {
    stageOperationHeapStatus = StatusCode.OK;
    if (HeapPage.canInsert(current, rowBytes)) {
      ByteBuffer heap = pages.stageExisting(
          operationLastHeapPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
      if (heap == null) {
        stageOperationHeapStatus = StatusCode.RESOURCE_EXHAUSTED;
      }
      return heap;
    }
    ByteBuffer metadata = pages.stageExisting(
        ROOT_META_PAGE_ID, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (metadata == null
        || BTreeRootPage.nextPageId(metadata) > IndexedTableLimits.MAX_PAGES) {
      stageOperationHeapStatus = StatusCode.RESOURCE_EXHAUSTED;
      return null;
    }
    int heapPageId = BTreeRootPage.allocatePage(metadata);
    ByteBuffer heap = pages.stageNew(
        heapPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (heap == null) {
      stageOperationHeapStatus = StatusCode.RESOURCE_EXHAUSTED;
      return null;
    }
    stageOperationHeapStatus = HeapPage.initialize(heap);
    if (!stageOperationHeapStatus.isOk()) {
      return null;
    }
    operationLastHeapPageId = heapPageId;
    return heap;
  }

  StatusCode stageInsert(
      int leafPageId,
      int space,
      long key,
      ByteBuffer row) {
    if (leafPageId <= 0 || !OrderedKey.isFiniteSpace(space) || row == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    heapInsert.reset();
    int operationLeafPageId = findOperationLeafPageId(space, key);
    if (operationLeafPageId != leafPageId) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer leaf = pages.stageExisting(leafPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (leaf == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = stageVersionRow(
        row, row.position(), row.remaining(), 0, false, heapInsert);
    if (!status.isOk()) {
      return status;
    }
    status = BTreePage.insertLeaf(leaf, space, key, heapInsert.rowId());
    if (status == StatusCode.RESOURCE_EXHAUSTED) {
      status = indexTree.splitAndInsert(leafPageId, leaf, space, key, heapInsert.rowId());
    }
    return status;
  }

  StatusCode fetchByKeyAt(
      long visibleCommitSequence,
      int space,
      long key,
      HeapRowResult result) {
    if (!OrderedKey.isFiniteSpace(space) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = lookupRowId(space, key);
    if (!status.isOk()) {
      return status;
    }
    int rowId = (int) indexLookup.rowId();
    while (rowId > 0) {
      long rowCommitSequence = rowCommitSequence(rowId);
      if (rowCommitSequence <= 0) {
        return StatusCode.CORRUPTION;
      }
      if (rowCommitSequence <= visibleCommitSequence) {
        if (isDeletedRow(rowId)) {
          result.reset();
          return StatusCode.CONFLICT;
        }
        return fetchRow(rowId, result);
      }
      rowId = previousRowId(rowId);
    }
    result.reset();
    return StatusCode.CONFLICT;
  }

  StatusCode nextScan(IndexedScanCursor cursor, IndexedScanResult result) {
    result.reset();
    while (cursor.leafPageId() > 0) {
      ByteBuffer leaf = pages.currentPayload(cursor.leafPageId());
      if (leaf == null || BTreePage.type(leaf) != BTreePage.TYPE_LEAF) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = nextVisibleLeafEntry(cursor, result, leaf);
      if (status != StatusCode.CONFLICT || cursor.leafPageId() == 0) return status;
      cursor.advanceLeaf(BTreePage.rightSiblingPageId(leaf));
    }
    return StatusCode.CONFLICT;
  }

  private StatusCode nextVisibleLeafEntry(
      IndexedScanCursor cursor, IndexedScanResult result, ByteBuffer leaf) {
    int entryCount = BTreePage.entryCount(leaf);
    while (cursor.entryIndex() < entryCount) {
      int entry = cursor.entryIndex();
      cursor.advanceEntry();
      long key = BTreePage.keyAt(leaf, entry);
      int space = BTreePage.spaceAt(leaf, entry);
      if (OrderedKey.compare(
          space, key, cursor.lowerSpace(), cursor.lowerKey()) < 0) continue;
      if (!OrderedKey.lessThan(
          space, key, cursor.upperSpace(), cursor.upperKey())) {
        cursor.advanceLeaf(0);
        return StatusCode.CONFLICT;
      }
      int rowId = visibleRowId(
          (int) BTreePage.leafValueAt(leaf, entry), cursor.visibleCommitSequence());
      if (rowId <= 0 || isDeletedRow(rowId)) continue;
      StatusCode status = fetchRow(rowId, result.row());
      if (!status.isOk()) return status;
      result.set(space, key);
      return StatusCode.OK;
    }
    return StatusCode.CONFLICT;
  }

  private int visibleRowId(int rowId, long visibleCommitSequence) {
    return versions.visibleRow(rowId, visibleCommitSequence, rowCount);
  }

  StatusCode prepareMutation(
      long visibleCommitSequence,
      int space,
      long key,
      IndexedMutationTarget result) {
    if (visibleCommitSequence < 0
        || !OrderedKey.isFiniteSpace(space) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = lookupRowId(space, key);
    if (!status.isOk()) {
      return status;
    }
    int latestRowId = (int) indexLookup.rowId();
    long latestCommitSequence = rowCommitSequence(latestRowId);
    if (latestCommitSequence <= 0) {
      return StatusCode.CORRUPTION;
    }
    if (latestCommitSequence > visibleCommitSequence || isDeletedRow(latestRowId)) {
      return StatusCode.CONFLICT;
    }
    result.set(latestRowId);
    return StatusCode.OK;
  }

  StatusCode prepareInsert(
      long visibleCommitSequence,
      int space,
      long key,
      IndexedMutationTarget result) {
    if (visibleCommitSequence < 0
        || !OrderedKey.isFiniteSpace(space) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = lookupRowId(space, key);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.OK;
    }
    if (!status.isOk()) {
      return status;
    }
    int latestRowId = (int) indexLookup.rowId();
    long latestCommitSequence = rowCommitSequence(latestRowId);
    if (latestCommitSequence <= 0) {
      return StatusCode.CORRUPTION;
    }
    if (latestCommitSequence > visibleCommitSequence
        || !isDeletedRow(latestRowId)) {
      return StatusCode.CONFLICT;
    }
    result.set(latestRowId);
    return StatusCode.OK;
  }

  int rowCount() {
    return rowCount;
  }

  int obsoleteVersionCount() {
    return versions.obsoleteCount();
  }

  int remainingVersionCapacity() {
    return IndexedTableLimits.MAX_ROWS - rowCount;
  }

  int rootPageId() {
    return BTreeRootPage.rootPageId(pages.currentPayload(ROOT_META_PAGE_ID));
  }

  int treeHeight() {
    ByteBuffer metadata = pages.currentPayload(ROOT_META_PAGE_ID);
    if (metadata == null) {
      return 0;
    }
    int pageId = BTreeRootPage.rootPageId(metadata);
    for (int depth = 1; depth <= MAXIMUM_TREE_HEIGHT; depth++) {
      ByteBuffer page = pages.currentPayload(pageId);
      if (page == null) {
        return 0;
      }
      if (BTreePage.type(page) == BTreePage.TYPE_LEAF) {
        return depth;
      }
      if (BTreePage.type(page) != BTreePage.TYPE_INTERNAL) {
        return 0;
      }
      pageId = BTreePage.firstChildPageId(page);
    }
    return 0;
  }

  int validatedLeafPageId() {
    return mutationValidator.leafPageId();
  }

  StatusCode validateNewIndexEntry(
      int space, long key, int earlierEntriesInLeaf) {
    return validateNewIndexEntryAt(
        findLeafPageId(space, key), space, key, earlierEntriesInLeaf);
  }

  StatusCode validateNewIndexEntryAt(
      int leafPageId, int space, long key, int earlierEntriesInLeaf) {
    return mutationValidator.validateNewAt(
        leafPageId, space, key, earlierEntriesInLeaf);
  }

  StatusCode validateNewIndexEntryIn(
      ByteBuffer leaf,
      int space,
      long key,
      int earlierEntriesInLeaf) {
    return mutationValidator.validateNewIn(
        leaf, space, key, earlierEntriesInLeaf);
  }

  StatusCode validateMutationTarget(
      int operation,
      int space,
      long key,
      int previousRowId,
      int earlierNewEntriesInLeaf) {
    return validateMutationTargetAt(
        findLeafPageId(space, key), operation, space, key,
        previousRowId, earlierNewEntriesInLeaf);
  }

  StatusCode validateMutationTargetAt(
      int leafPageId,
      int operation,
      int space,
      long key,
      int previousRowId,
      int earlierNewEntriesInLeaf) {
    return mutationValidator.validateMutationAt(
        leafPageId,
        operation,
        space,
        key,
        previousRowId,
        earlierNewEntriesInLeaf,
        isDeletedRow(previousRowId));
  }

  StatusCode validateMutationTargetIn(
      ByteBuffer leaf,
      int operation,
      int space,
      long key,
      int previousRowId,
      int earlierNewEntriesInLeaf) {
    return mutationValidator.validateMutationIn(
        leaf,
        operation,
        space,
        key,
        previousRowId,
        earlierNewEntriesInLeaf,
        isDeletedRow(previousRowId));
  }

  StatusCode validateVacuumHead(int space, long key, int rowId) {
    return mutationValidator.validateVacuumAt(
        findLeafPageId(space, key), space, key, rowId);
  }

  StatusCode applyInsertOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    return walApplier.applyInsert(payload, recordStart, recordEnd, commitSequence);
  }

  StatusCode applyInsertBatchOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    return walApplier.applyInsertBatch(
        payload, recordStart, recordEnd, commitSequence);
  }

  StatusCode applyMutationBatchOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    return walApplier.applyMutationBatch(
        payload, recordStart, recordEnd, commitSequence);
  }

  private StatusCode lookupRowId(int space, long key) {
    int leafPageId = findLeafPageId(space, key);
    if (leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    return BTreePage.lookupLeaf(
        pages.currentPayload(leafPageId), space, key, indexLookup);
  }

  int findLeafPageId(int space, long key) {
    return indexTree.findLeafPageId(space, key);
  }

  int findOperationLeafPageId(int space, long key) {
    return indexTree.findOperationLeafPageId(space, key);
  }

  StatusCode splitIndexLeaf(
      int leafPageId, ByteBuffer leaf, int space, long key, int rowId) {
    return indexTree.splitAndInsert(leafPageId, leaf, space, key, rowId);
  }

}
