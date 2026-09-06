package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.BTreeSplitResult;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Allocation-conscious indexed-table behavior over one owned page set. */
final class IndexedTableKernel extends IndexedKernelVersions {
  static final int HEAP_PAGE_ID = 1;
  static final int ROOT_META_PAGE_ID = 2;
  static final int INITIAL_LEAF_PAGE_ID = 3;

  private final IndexedPageSet pages;
  private final HeapInsertResult heapInsert = new HeapInsertResult();
  private final IndexedVersionRecord versionRecord = new IndexedVersionRecord();
  private final IndexedKernelComponents components;
  private final IndexedStagedPageAllocation stagedAllocation =
      new IndexedStagedPageAllocation();
  private StatusCode stageOperationHeapStatus = StatusCode.OK;
  private long rowCount;
  private int lastHeapPageId = HEAP_PAGE_ID;
  private long operationRowCount;
  private int operationLastHeapPageId = HEAP_PAGE_ID;

  long operationRowCount() {
    return operationRowCount;
  }

  int lastHeapPageId() {
    return lastHeapPageId;
  }

  int operationLastHeapPageId() { return operationLastHeapPageId; }

  HeapInsertResult heapInsertResult() {
    return heapInsert;
  }

  StatusCode fetchRow(long rowId, HeapRowResult result) {
    return components.rows.fetch(rowId, rowCount, result);
  }

  StatusCode fetchOperationRow(long rowId, HeapRowResult result) {
    if (result == null || rowId <= 0 || rowId > operationRowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (rowId <= rowCount) return components.rows.fetch(rowId, rowCount, result);
    long ordinal = rowId - rowCount - 1;
    IndexedVersionOperation operation = versions.operation();
    if (ordinal < 0 || ordinal >= operation.count()) return StatusCode.CORRUPTION;
    int index = (int) ordinal;
    ByteBuffer heap = pages.operationPayload(operation.pageId(index));
    StatusCode status = pages.lastStatus();
    if (heap == null && status.isOk()) status = StatusCode.CORRUPTION;
    if (heap != null) status = HeapPage.fetch(heap, operation.slot(index), result);
    if (status.isOk()) status = result.retainBytes();
    if (!status.isOk()) result.reset();
    return status;
  }

  StatusCode pinRow(long rowId, HeapRowResult result, IndexedRowPin pin) {
    return components.rows.pin(rowId, rowCount, result, pin);
  }

  StatusCode releaseRow(IndexedRowPin pin) {
    return components.rows.release(pin);
  }

  int rowLength(long rowId) {
    return components.rows.length(rowId, rowCount);
  }

  StatusCode copyRowTo(long rowId, ByteBuffer destination, int destinationOffset) {
    return components.rows.copyTo(rowId, rowCount, destination, destinationOffset);
  }

  StatusCode readVersion(long rowId, IndexedVersionRecord result) {
    return versions.lookup(rowId, rowCount, result);
  }

  void beginOperationState() {
    operationRowCount = rowCount;
    versions.operation().begin();
    operationLastHeapPageId = lastHeapPageId;
  }

  StatusCode reserveOperationVersions(int required) {
    return versions.operation().reserve(required);
  }

  StatusCode stageVersionRow(
      ByteBuffer source,
      int sourceOffset,
      int rowBytes,
      long previousRowId,
      boolean deleted,
      HeapInsertResult result) {
    return stageVersionRow(
        source, sourceOffset, rowBytes, previousRowId, deleted, result,
        IndexedTableLimits.MAX_CHANGED_PAGES);
  }

  StatusCode stageRelationalVersionRow(
      ByteBuffer source,
      int sourceOffset,
      int rowBytes,
      long previousRowId,
      boolean deleted,
      HeapInsertResult result) {
    return stageVersionRow(
        source, sourceOffset, rowBytes, previousRowId, deleted, result,
        pages.changedPageCapacity());
  }

  private StatusCode stageVersionRow(
      ByteBuffer source,
      int sourceOffset,
      int rowBytes,
      long previousRowId,
      boolean deleted,
      HeapInsertResult result,
      int maximumChangedPages) {
    if (source == null
        || sourceOffset < 0
        || rowBytes <= 0
        || source.limit() - sourceOffset < rowBytes
        || result == null
        || operationRowCount >= IndexedTableLimits.MAX_ROWS
        || !versions.operation().canStage(previousRowId, deleted, operationRowCount)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    ByteBuffer heap = pages.operationPayload(operationLastHeapPageId);
    if (heap == null || !HeapPage.isHeap(heap)) {
      return StatusCode.CORRUPTION;
    }
    heap = stageOperationHeap(heap, rowBytes, maximumChangedPages);
    if (heap == null) return stageOperationHeapStatus;
    StatusCode status = HeapPage.insertFrom(heap, source, sourceOffset, rowBytes, heapInsert);
    if (status.isOk()) {
      int heapSlot = (int) heapInsert.rowId();
      operationRowCount++;
      versions.operation().stage(
          previousRowId, deleted, operationLastHeapPageId, heapSlot);
      result.setRowId(operationRowCount);
    }
    return status;
  }

  boolean canAppendRow(int rowBytes) {
    return components.capacity.row(operationRowCount, operationLastHeapPageId, rowBytes);
  }

  StatusCode rebuildRowLocations() {
    StatusCode status = versions.rows().rebuild(pages);
    if (status.isOk()) {
      rowCount = versions.rows().rebuiltRowCount();
      lastHeapPageId = versions.rows().rebuiltLastHeapPageId();
    }
    return status;
  }

  boolean rowDirectoryMatches(long expectedRowCount, long commitSequence) {
    return versions.rows().matches(expectedRowCount, commitSequence);
  }

  StatusCode loadRowDirectory(long expectedRowCount) {
    int expectedLastHeapPageId = versions.rows().publishedLastHeapPageId();
    StatusCode status = versions.rows().load(expectedRowCount);
    if (status.isOk()) {
      rowCount = expectedRowCount;
      lastHeapPageId = expectedLastHeapPageId;
    }
    return status;
  }

  StatusCode recordNewRowCommits(long previousRowCount, long commitSequence) {
    if (!pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    return versions.recordNewRows(previousRowCount, rowCount, commitSequence);
  }

  StatusCode recordOperationVersions(long previousRowCount, long commitSequence) {
    return versions.recordOperation(previousRowCount, commitSequence);
  }

  StatusCode recordOperationVersions(
      long groupBaseRow, int firstVersion, int versionCount, long commitSequence) {
    return versions.recordOperation(
        groupBaseRow, firstVersion, versionCount, commitSequence);
  }

  StatusCode admitOperationPublication() {
    return versions.admitRows(rowCount + 1, versions.operation().count());
  }

  StatusCode publishOperationRows(long previousRowCount) {
    if (previousRowCount != rowCount
        || operationRowCount - previousRowCount != versions.operation().count()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode status = versions.publishOperationRows(previousRowCount);
    if (status.isOk()) {
      rowCount = operationRowCount;
      lastHeapPageId = operationLastHeapPageId;
    }
    return status;
  }

  StatusCode publishOperationRows(
      long groupBaseRow, int firstVersion, int versionCount) {
    if (groupBaseRow != rowCount || firstVersion < 0 || versionCount < 0
        || firstVersion > versions.operation().count() - versionCount) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return versions.operation().publishRows(
        groupBaseRow, firstVersion, versionCount, versions.rows());
  }

  StatusCode publishOperationFrontier(long groupBaseRow, long memberRowEnd, int heapPageEnd) {
    if (groupBaseRow != rowCount || memberRowEnd != operationRowCount
        || memberRowEnd - groupBaseRow != versions.operation().count()
        || heapPageEnd != operationLastHeapPageId) {
      return StatusCode.INVARIANT_BROKEN;
    }
    rowCount = memberRowEnd;
    lastHeapPageId = heapPageEnd;
    return StatusCode.OK;
  }

  StatusCode indexedEntryCount(IndexedCountResult result) {
    return components.entryCounter.count(result);
  }

  StatusCode vacuumChunkCount(IndexedCountResult result) {
    return components.vacuum.chunkCount(result);
  }

  StatusCode vacuumChunkRowCount(long firstRow, IndexedCountResult result) {
    return components.vacuum.chunkRowCount(firstRow, result);
  }

  StatusCode vacuumChunkPayloadBytes(
      long firstRow, int rowLimit, IndexedCountResult result) {
    return components.vacuum.chunkPayloadBytes(firstRow, rowLimit, result);
  }

  StatusCode encodeVacuumChunk(
      ByteBuffer payload,
      long retainedRows,
      long firstRow,
      int rowLimit,
      int chunk,
      int chunkCount,
      int payloadBytes) {
    return components.vacuum.encodeChunk(
        payload, retainedRows, firstRow, rowLimit, chunk, chunkCount, payloadBytes);
  }

  StatusCode beginVacuumApply() {
    return components.vacuum.beginApply();
  }

  StatusCode applyVacuumEntry(ByteBuffer payload, int entryOffset, long compactedRowId) {
    return components.vacuum.applyEntry(payload, entryOffset, compactedRowId);
  }

  StatusCode finishVacuumApply() {
    return components.vacuum.finishApply();
  }

  StatusCode publishVacuumApply(long start, long end) {
    return components.vacuum.publish(start, end);
  }

  void resetVacuumApply() {
    components.vacuum.resetApply();
  }

  int currentHeapAvailableBytes() {
    return components.capacity.available(operationLastHeapPageId);
  }

  StatusCode validateCurrentPage(int pageId) {
    return components.validator.validateCurrentPage(pageId);
  }

  StatusCode validateAppliedPages(int[] pageIds, int pageCount) {
    return components.validator.validateAppliedPages(pageIds, pageCount);
  }

  IndexedTableKernel(
      IndexedPageSet pageSet,
      IndexedVersionState versionState) {
    super(versionState);
    pages = pageSet;
    components = new IndexedKernelComponents(this, pages, versions, heapInsert);
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
    return components.validator.validate(rowCount);
  }

  StatusCode stageInsertBatch(
      long[] spaces,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      HeapInsertResult result) {
    return components.mutationStager.stageInsertBatch(
        spaces, keys, rows, rowStride, rowLengths, insertCount, result);
  }

  StatusCode stageMutationBatch(
      int[] operations,
      long[] spaces,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      HeapInsertResult result) {
    return components.mutationStager.stageMutationBatch(
        operations, spaces, keys, previousRowIds, rows, rowStride,
        rowLengths, mutationCount, result);
  }

  StatusCode stageMutationBatch(
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    return components.mutationStager.stagePendingMutations(mutations, result);
  }

  StatusCode stageInsertBatch(
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    return components.mutationStager.stagePendingInserts(mutations, result);
  }

  StatusCode stageVersionRow(
      PendingMutationBuffer mutations,
      int index,
      long previousRowId,
      boolean deleted,
      HeapInsertResult result) {
    int rowBytes = mutations.rowLengthAt(index);
    if (!canAppendRow(rowBytes)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (operationRowCount >= IndexedTableLimits.MAX_ROWS
        || !versions.operation().canStage(previousRowId, deleted, rowCount)) {
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
      int heapSlot = (int) heapInsert.rowId();
      operationRowCount++;
      versions.operation().stage(
          previousRowId, deleted, operationLastHeapPageId, heapSlot);
      result.setRowId(operationRowCount);
    }
    return status;
  }

  private ByteBuffer stageOperationHeap(ByteBuffer current, int rowBytes) {
    return stageOperationHeap(current, rowBytes, IndexedTableLimits.MAX_CHANGED_PAGES);
  }

  private ByteBuffer stageOperationHeap(
      ByteBuffer current, int rowBytes, int maximumChangedPages) {
    stageOperationHeapStatus = StatusCode.OK;
    if (HeapPage.canInsert(current, rowBytes)) {
      ByteBuffer heap = pages.stageExisting(
          operationLastHeapPageId, maximumChangedPages);
      if (heap == null) {
        stageOperationHeapStatus = StatusCode.RESOURCE_EXHAUSTED;
      }
      return heap;
    }
    ByteBuffer metadata = pages.stageExisting(
        ROOT_META_PAGE_ID, maximumChangedPages);
    if (metadata == null
        || !BTreeRootPage.hasAllocations(metadata, 1, IndexedTableLimits.MAX_PAGES)) {
      stageOperationHeapStatus = StatusCode.RESOURCE_EXHAUSTED;
      return null;
    }
    stageOperationHeapStatus = stagedAllocation.stage(
        pages, metadata, maximumChangedPages,
        PageCodec.PAYLOAD_KIND_SCALAR_BTREE, PageCodec.SCALAR_OWNER_KEY_ID);
    if (!stageOperationHeapStatus.isOk()) {
      return null;
    }
    int heapPageId = stagedAllocation.pageId();
    ByteBuffer heap = stagedAllocation.payload();
    stageOperationHeapStatus = HeapPage.initialize(heap);
    if (!stageOperationHeapStatus.isOk()) {
      return null;
    }
    operationLastHeapPageId = heapPageId;
    return heap;
  }

  StatusCode stageInsert(
      int leafPageId,
      long space,
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
      status = components.indexTree.splitAndInsert(leafPageId, leaf, space, key, heapInsert.rowId());
    }
    return status;
  }

  StatusCode fetchByKeyAt(
      long visibleCommitSequence,
      long space,
      long key,
      HeapRowResult result) {
    return components.visibility.fetchByKey(visibleCommitSequence, space, key, rowCount, result);
  }

  StatusCode fetchVersionedByKeyAt(
      long visibleCommitSequence, long space, long key,
      HeapRowResult row, IndexedVersionedRowResult result) {
    return components.visibility.fetchVersionedByKey(
        visibleCommitSequence, space, key, rowCount, row, result);
  }

  StatusCode fetchCurrentSuccessor(
      long space, long key, long candidateRowId, HeapRowResult row, IndexedVersionedRowResult result) {
    return components.visibility.fetchCurrentSuccessor(
        space, key, candidateRowId, rowCount, row, result);
  }

  StatusCode nextScan(IndexedScanCursor cursor, IndexedScanResult result) {
    return components.visibility.nextScan(cursor, result, rowCount);
  }

  StatusCode prepareMutation(
      long visibleCommitSequence,
      long space,
      long key,
      IndexedMutationTarget result) {
    return components.visibility.prepareMutation(
        visibleCommitSequence, space, key, rowCount, false, result);
  }

  StatusCode prepareInsert(
      long visibleCommitSequence,
      long space,
      long key,
      IndexedMutationTarget result) {
    return components.visibility.prepareMutation(
        visibleCommitSequence, space, key, rowCount, true, result);
  }

  long rowCount() {
    return rowCount;
  }

  int obsoleteVersionCount() {
    return versions.obsoleteCount();
  }

  long checkpointObsoleteVersionCount() {
    return versions.checkpointObsoleteCount();
  }

  long remainingVersionCapacity() {
    return IndexedTableLimits.MAX_ROWS - rowCount;
  }

  int rootPageId() {
    return BTreeRootPage.rootPageId(pages.currentPayload(ROOT_META_PAGE_ID));
  }

  int nextPageId() {
    return BTreeRootPage.nextPageId(pages.currentPayloadUnchecked(ROOT_META_PAGE_ID));
  }

  int treeHeight() {
    ByteBuffer metadata = pages.currentPayload(ROOT_META_PAGE_ID);
    if (metadata == null) {
      return 0;
    }
    int pageId = BTreeRootPage.rootPageId(metadata);
    for (int level = 0; BTreeStructuralLimits.canVisitLevel(level); level++) {
      if (!BTreeStructuralLimits.validPageId(pageId)) {
        return 0;
      }
      ByteBuffer page = pages.currentPayload(pageId);
      if (page == null) {
        return 0;
      }
      if (BTreePage.type(page) == BTreePage.TYPE_LEAF) {
        return level + 1;
      }
      if (BTreePage.type(page) != BTreePage.TYPE_INTERNAL) {
        return 0;
      }
      pageId = BTreePage.firstChildPageId(page);
    }
    return 0;
  }

  int validatedLeafPageId() {
    return components.mutationValidator.leafPageId();
  }

  StatusCode validateNewIndexEntry(
      long space, long key, int earlierEntriesInLeaf) {
    return validateNewIndexEntryAt(
        findLeafPageId(space, key), space, key, earlierEntriesInLeaf);
  }

  StatusCode validateNewIndexEntryAt(
      int leafPageId, long space, long key, int earlierEntriesInLeaf) {
    return components.mutationValidator.validateNewAt(
        leafPageId, space, key, earlierEntriesInLeaf);
  }

  StatusCode validateNewIndexEntryIn(
      ByteBuffer leaf,
      long space,
      long key,
      int earlierEntriesInLeaf) {
    return components.mutationValidator.validateNewIn(
        leaf, space, key, earlierEntriesInLeaf);
  }

  StatusCode validateMutationTarget(
      int operation,
      long space,
      long key,
      long previousRowId,
      int earlierNewEntriesInLeaf) {
    return validateMutationTargetAt(
        findLeafPageId(space, key), operation, space, key,
        previousRowId, earlierNewEntriesInLeaf);
  }

  StatusCode validateMutationTargetAt(
      int leafPageId,
      int operation,
      long space,
      long key,
      long previousRowId,
      int earlierNewEntriesInLeaf) {
    boolean previousDeleted = false;
    if (previousRowId > 0) {
      StatusCode status = readVersion(previousRowId, versionRecord);
      if (!status.isOk()) return status;
      previousDeleted = versionRecord.deleted();
    }
    return components.mutationValidator.validateMutationAt(
        leafPageId,
        operation,
        space,
        key,
        previousRowId,
        earlierNewEntriesInLeaf,
        previousDeleted);
  }

  StatusCode validateMutationTargetIn(
      ByteBuffer leaf,
      int operation,
      long space,
      long key,
      long previousRowId,
      int earlierNewEntriesInLeaf) {
    boolean previousDeleted = false;
    if (previousRowId > 0) {
      StatusCode status = readVersion(previousRowId, versionRecord);
      if (!status.isOk()) return status;
      previousDeleted = versionRecord.deleted();
    }
    return components.mutationValidator.validateMutationIn(
        leaf,
        operation,
        space,
        key,
        previousRowId,
        earlierNewEntriesInLeaf,
        previousDeleted);
  }

  StatusCode validateVacuumHead(
      long space, long key, long oldRowId, long compactedRowId) {
    return components.mutationValidator.validateVacuumAt(
        findLeafPageId(space, key), space, key, oldRowId, compactedRowId);
  }

  int findLeafPageId(long space, long key) {
    return components.indexTree.findLeafPageId(space, key);
  }

  int findLeafPageIdAt(long visibleCommitSequence, long space, long key) {
    return components.indexTree.findLeafPageIdAt(visibleCommitSequence, space, key);
  }

  StatusCode snapshotLookupStatus() { return components.indexTree.snapshotLookupStatus(); }

  int findOperationLeafPageId(long space, long key) {
    return components.indexTree.findOperationLeafPageId(space, key);
  }

  StatusCode operationLookupStatus() { return components.indexTree.lookupStatus(); }

  StatusCode splitIndexLeaf(
      int leafPageId, ByteBuffer leaf, long space, long key, long rowId) {
    return components.indexTree.splitAndInsert(leafPageId, leaf, space, key, rowId);
  }

  StatusCode splitRelationalIndexLeaf(
      int leafPageId, ByteBuffer leaf, long space, long key, long rowId) {
    return components.indexTree.splitAndInsertLogical(leafPageId, leaf, space, key, rowId);
  }

}
