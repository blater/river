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
  private final int[] rowPageIds = new int[IndexedTableLimits.MAX_ROWS + 1];
  private final int[] rowSlots = new int[IndexedTableLimits.MAX_ROWS + 1];
  private final BTreeLookupResult indexLookup = new BTreeLookupResult();
  private final BTreeSplitResult splitResult = new BTreeSplitResult();
  private final int[] splitPathPageIds = new int[MAXIMUM_TREE_HEIGHT];
  private final IndexedTableValidator validator;
  private final IndexedMutationValidator mutationValidator;
  private StatusCode stageOperationHeapStatus = StatusCode.OK;
  private int vacuumEncodeOrdinal;
  private int vacuumEncodedRows;
  private int vacuumOutputOffset;
  private int splitPromotedRightPageId;
  private boolean splitParentPromoted;
  private int splitPathDepth;
  private int rowCount;
  private int lastHeapPageId = HEAP_PAGE_ID;
  private int operationRowCount;
  private int operationLastHeapPageId = HEAP_PAGE_ID;
  private int vacuumHeapPageId;
  private int vacuumLastSpace;
  private long vacuumLastKey;

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
        pages.currentPayloadUnchecked(rowPageIds[rowId]), rowSlots[rowId], result);
  }

  int rowLength(int rowId) {
    return rowId > 0 && rowId <= rowCount
        ? HeapPage.rowLength(pages.currentPayloadUnchecked(rowPageIds[rowId]), rowSlots[rowId]) : 0;
  }

  StatusCode copyRowTo(int rowId, ByteBuffer destination, int destinationOffset) {
    if (rowId <= 0 || rowId > rowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return HeapPage.copyRowTo(
        pages.currentPayloadUnchecked(rowPageIds[rowId]),
        rowSlots[rowId],
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
    rowPageIds[rowCount] = lastHeapPageId;
    rowSlots[rowCount] = heapInsert.rowId();
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
        rowPageIds[rebuiltRows] = pageId;
        rowSlots[rebuiltRows] = slot;
      }
      rebuiltLastHeap = pageId;
    }
    if (rebuiltLastHeap == 0) {
      return StatusCode.CORRUPTION;
    }
    for (int rowId = rebuiltRows + 1; rowId <= rowCount; rowId++) {
      rowPageIds[rowId] = 0;
      rowSlots[rowId] = 0;
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
    int chunks = 0;
    int chunkBytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    int rows = 0;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      ByteBuffer leaf = leafPayload(pageId);
      if (leaf == null) {
        continue;
      }
      int entryCount = BTreePage.entryCount(leaf);
      for (int entry = 0; entry < entryCount; entry++) {
        int rowBytes = rowLength(BTreePage.valueAt(leaf, entry));
        int required = IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
        if (rowBytes <= 0
            || required > WalRecordCodec.MAX_PAYLOAD_BYTES
                - IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES) {
          return -1;
        }
        if (chunkBytes > WalRecordCodec.MAX_PAYLOAD_BYTES - required) {
          chunks++;
          chunkBytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
        }
        chunkBytes += required;
        rows++;
      }
    }
    if (rows > 0) {
      chunks++;
    }
    return rows == indexedEntryCount() ? chunks : -1;
  }

  int vacuumChunkRowCount(int firstRow) {
    int ordinal = 0;
    int rows = 0;
    int bytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      ByteBuffer leaf = leafPayload(pageId);
      if (leaf == null) {
        continue;
      }
      int entryCount = BTreePage.entryCount(leaf);
      for (int entry = 0; entry < entryCount; entry++) {
        if (ordinal++ < firstRow) {
          continue;
        }
        int rowBytes = rowLength(BTreePage.valueAt(leaf, entry));
        int required = IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
        if (rowBytes <= 0 || bytes > WalRecordCodec.MAX_PAYLOAD_BYTES - required) {
          return rows;
        }
        bytes += required;
        rows++;
      }
    }
    return rows;
  }

  int vacuumChunkPayloadBytes(int firstRow, int rowLimit) {
    int ordinal = 0;
    int rows = 0;
    int bytes = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    for (int pageId = 1; rows < rowLimit && pageId <= pages.highestPageId(); pageId++) {
      ByteBuffer leaf = leafPayload(pageId);
      if (leaf == null) {
        continue;
      }
      int entryCount = BTreePage.entryCount(leaf);
      for (int entry = 0; rows < rowLimit && entry < entryCount; entry++) {
        if (ordinal++ < firstRow) {
          continue;
        }
        int rowBytes = rowLength(BTreePage.valueAt(leaf, entry));
        if (rowBytes <= 0) {
          return -1;
        }
        bytes += IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
        rows++;
      }
    }
    return rows == rowLimit ? bytes : -1;
  }

  StatusCode encodeVacuumChunk(
      ByteBuffer payload,
      int retainedRows,
      int firstRow,
      int rowLimit,
      int chunk,
      int chunkCount,
      int payloadBytes) {
    if (payload == null
        || retainedRows <= 0
        || firstRow < 0
        || rowLimit <= 0
        || firstRow > retainedRows - rowLimit
        || chunk < 0
        || chunk >= chunkCount
        || payloadBytes <= IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES
        || payload.limit() != payloadBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    IndexedWalCodec.encodeVacuumChunkHeader(
        payload, retainedRows, firstRow, rowLimit, chunk, chunkCount);
    vacuumEncodeOrdinal = 0;
    vacuumEncodedRows = 0;
    vacuumOutputOffset = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    StatusCode status = StatusCode.OK;
    for (int pageId = 1;
        status.isOk() && vacuumEncodedRows < rowLimit && pageId <= pages.highestPageId();
        pageId++) {
      ByteBuffer leaf = leafPayload(pageId);
      if (leaf != null) status = encodeVacuumLeaf(payload, leaf, firstRow, rowLimit);
    }
    if (!status.isOk()) return status;
    if (vacuumEncodedRows != rowLimit || vacuumOutputOffset != payloadBytes) {
      return StatusCode.CORRUPTION;
    }
    payload.position(payloadBytes);
    return StatusCode.OK;
  }

  private StatusCode encodeVacuumLeaf(
      ByteBuffer payload, ByteBuffer leaf, int firstRow, int rowLimit) {
    int entryCount = BTreePage.entryCount(leaf);
    for (int entry = 0; vacuumEncodedRows < rowLimit && entry < entryCount; entry++) {
      if (vacuumEncodeOrdinal++ < firstRow) continue;
      int rowId = BTreePage.valueAt(leaf, entry);
      int rowBytes = rowLength(rowId);
      IndexedWalCodec.encodeVacuumEntry(
          payload,
          vacuumOutputOffset,
          BTreePage.spaceAt(leaf, entry),
          BTreePage.keyAt(leaf, entry),
          rowId,
          rowBytes,
          isDeletedRow(rowId));
      StatusCode status = copyRowTo(
          rowId, payload, vacuumOutputOffset + IndexedWalCodec.VACUUM_ENTRY_BYTES);
      if (!status.isOk()) return status;
      vacuumOutputOffset += IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
      vacuumEncodedRows++;
    }
    return StatusCode.OK;
  }

  StatusCode beginVacuumApply() {
    vacuumHeapPageId = HEAP_PAGE_ID;
    vacuumLastSpace = 0;
    vacuumLastKey = 0;
    StatusCode status = StatusCode.OK;
    for (int pageId = 1; status.isOk() && pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isPresent(pageId) || !HeapPage.isHeap(pages.currentPayloadUnchecked(pageId))) {
        continue;
      }
      ByteBuffer stagedHeap = pages.stageExisting(pageId, IndexedTableLimits.MAX_PAGES);
      status = stagedHeap == null
          ? StatusCode.RESOURCE_EXHAUSTED : HeapPage.initialize(stagedHeap);
    }
    return status;
  }

  StatusCode applyVacuumEntry(ByteBuffer payload, int entryOffset, int compactedRowId) {
    if (!IndexedWalCodec.validVacuumEntry(payload, entryOffset)) {
      return StatusCode.CORRUPTION;
    }
    long key = IndexedWalCodec.vacuumEntryKey(payload, entryOffset);
    int space = IndexedWalCodec.vacuumEntrySpace(payload, entryOffset);
    int oldRowId = IndexedWalCodec.vacuumEntryRowId(payload, entryOffset);
    int rowBytes = IndexedWalCodec.vacuumEntryRowBytes(payload, entryOffset);
    boolean deleted = IndexedWalCodec.vacuumEntryDeleted(payload, entryOffset);
    if (!OrderedKey.isFiniteSpace(space)
        || (compactedRowId > 1
            && !OrderedKey.lessThan(vacuumLastSpace, vacuumLastKey, space, key))
        || rowLength(oldRowId) != rowBytes
        || isDeletedRow(oldRowId) != deleted) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = validateVacuumHead(space, key, oldRowId);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer leaf = pages.stageExisting(
        mutationValidator.leafPageId(), IndexedTableLimits.MAX_PAGES);
    if (leaf == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    ByteBuffer heap = pages.operationPayload(vacuumHeapPageId);
    if (!HeapPage.canInsert(heap, rowBytes)) {
      vacuumHeapPageId = nextHeapPageId(vacuumHeapPageId);
      heap = vacuumHeapPageId == 0 ? null : pages.operationPayload(vacuumHeapPageId);
      if (heap == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    status = HeapPage.insertFrom(
        heap,
        payload,
        entryOffset + IndexedWalCodec.VACUUM_ENTRY_BYTES,
        rowBytes,
        heapInsert);
    if (status.isOk()) {
      status = BTreePage.updateLeaf(leaf, space, key, compactedRowId);
    }
    if (status.isOk()) {
      versions.recordVacuumDeleted(compactedRowId, deleted);
      vacuumLastSpace = space;
      vacuumLastKey = key;
    }
    return status;
  }

  void resetVacuumApply() {
    vacuumHeapPageId = 0;
    vacuumLastSpace = 0;
    vacuumLastKey = 0;
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

  private ByteBuffer leafPayload(int pageId) {
    if (!pages.isPresent(pageId) || pageId == ROOT_META_PAGE_ID) {
      return null;
    }
    ByteBuffer page = pages.currentPayloadUnchecked(pageId);
    return !HeapPage.isHeap(page) && BTreePage.type(page) == BTreePage.TYPE_LEAF
        ? page : null;
  }

  private int nextHeapPageId(int afterPageId) {
    for (int pageId = afterPageId + 1; pageId <= pages.highestPageId(); pageId++) {
      if (pages.isPresent(pageId) && HeapPage.isHeap(pages.currentPayloadUnchecked(pageId))) {
        return pageId;
      }
    }
    return 0;
  }

  IndexedTableKernel(IndexedPageSet pageSet) {
    pages = pageSet;
    validator = new IndexedTableValidator(
        pages, versions);
    mutationValidator = new IndexedMutationValidator(pages);
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
    if (spaces == null
        || keys == null
        || rows == null
        || rowStride <= 0
        || rowLengths == null
        || insertCount <= 0
        || insertCount > keys.length
        || insertCount > spaces.length
        || insertCount > rowLengths.length
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = StatusCode.OK;
    for (int index = 0; index < insertCount; index++) {
      status = stageInsertEntry(
          spaces[index], keys[index],
          rows, index * rowStride, rowLengths[index], rowStride);
      if (!status.isOk()) return status;
    }
    result.setRowId(heapInsert.rowId());
    return StatusCode.OK;
  }

  private StatusCode stageInsertEntry(
      int space, long key, ByteBuffer rows,
      int rowOffset, int rowBytes, int rowStride) {
    if (!OrderedKey.isFiniteSpace(space)
        || rowBytes <= 0
        || rowBytes > rowStride
        || rows.limit() - rowOffset < rowBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = findOperationLeafPageId(space, key);
    if (leafPageId <= 0) return StatusCode.CORRUPTION;
    ByteBuffer leaf = pages.stageExisting(
        leafPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (leaf == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = validateNewIndexEntryIn(leaf, space, key, 0);
    if (!status.isOk() && status != StatusCode.RESOURCE_EXHAUSTED) return status;
    status = stageVersionRow(rows, rowOffset, rowBytes, 0, false, heapInsert);
    if (!status.isOk()) return status;
    status = BTreePage.insertLeaf(leaf, space, key, heapInsert.rowId());
    return status == StatusCode.RESOURCE_EXHAUSTED
        ? splitAndInsert(leafPageId, leaf, space, key, heapInsert.rowId()) : status;
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
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = StatusCode.OK;
    for (int index = 0; index < mutationCount; index++) {
      status = stageMutationEntry(
          operations[index],
          spaces[index],
          keys[index],
          previousRowIds[index],
          rows,
          index * rowStride,
          rowLengths[index],
          rowStride);
      if (!status.isOk()) return status;
    }
    result.setRowId(heapInsert.rowId());
    return StatusCode.OK;
  }

  private StatusCode stageMutationEntry(
      int operation,
      int space,
      long key,
      int previousRowId,
      ByteBuffer rows,
      int rowOffset,
      int rowBytes,
      int rowStride) {
    if (!validMutation(operation)
        || !OrderedKey.isFiniteSpace(space)
        || rowBytes <= 0
        || rowBytes > rowStride
        || rows.limit() - rowOffset < rowBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = findOperationLeafPageId(space, key);
    if (leafPageId <= 0) return StatusCode.CORRUPTION;
    ByteBuffer leaf = pages.stageExisting(
        leafPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (leaf == null) return StatusCode.RESOURCE_EXHAUSTED;
    boolean newIndexEntry = operation == IndexedWalCodec.MUTATION_INSERT
        && previousRowId == 0;
    StatusCode status = validateMutationTargetIn(
        leaf, operation, space, key, previousRowId, 0);
    if (!status.isOk() && (!newIndexEntry || status != StatusCode.RESOURCE_EXHAUSTED)) {
      return status;
    }
    status = stageVersionRow(
        rows,
        rowOffset,
        rowBytes,
        previousRowId,
        operation == IndexedWalCodec.MUTATION_DELETE,
        heapInsert);
    return status.isOk()
        ? applyStagedIndexEntry(
            leafPageId, leaf, space, key, newIndexEntry, heapInsert.rowId()) : status;
  }

  StatusCode stageMutationBatch(
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (mutations == null || mutations.count() <= 0 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    for (int index = 0; index < mutations.count(); index++) {
      StatusCode status = stagePendingMutation(mutations, index);
      if (!status.isOk()) {
        return status;
      }
    }
    result.setRowId(heapInsert.rowId());
    return StatusCode.OK;
  }

  private StatusCode stagePendingMutation(
      PendingMutationBuffer mutations, int index) {
    int operation = mutations.operationAt(index);
    int space = mutations.spaceAt(index);
    long key = mutations.keyAt(index);
    int previousRowId = mutations.previousRowIdAt(index);
    int rowBytes = mutations.rowLengthAt(index);
    if (!validMutation(operation)
        || !OrderedKey.isFiniteSpace(space)
        || rowBytes <= 0
        || rowBytes > mutations.rowStride()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = findOperationLeafPageId(space, key);
    if (leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer leaf = pages.stageExisting(
        leafPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (leaf == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean newIndexEntry = operation == IndexedWalCodec.MUTATION_INSERT
        && previousRowId == 0;
    StatusCode status = validateMutationTargetIn(
        leaf, operation, space, key, previousRowId, 0);
    if (!status.isOk()
        && (!newIndexEntry || status != StatusCode.RESOURCE_EXHAUSTED)) {
      return status;
    }
    status = stageVersionRow(
        mutations,
        index,
        previousRowId,
        operation == IndexedWalCodec.MUTATION_DELETE,
        heapInsert);
    return status.isOk()
        ? applyStagedIndexEntry(
            leafPageId, leaf, space, key, newIndexEntry, heapInsert.rowId())
        : status;
  }

  StatusCode stageInsertBatch(
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (mutations == null || mutations.count() <= 0 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    for (int index = 0; index < mutations.count(); index++) {
      StatusCode status = stagePendingInsert(mutations, index);
      if (!status.isOk()) {
        return status;
      }
    }
    result.setRowId(heapInsert.rowId());
    return StatusCode.OK;
  }

  private StatusCode stagePendingInsert(
      PendingMutationBuffer mutations, int index) {
    int space = mutations.spaceAt(index);
    long key = mutations.keyAt(index);
    int rowBytes = mutations.rowLengthAt(index);
    if (!OrderedKey.isFiniteSpace(space)
        || rowBytes <= 0
        || rowBytes > mutations.rowStride()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = findOperationLeafPageId(space, key);
    if (leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer leaf = pages.stageExisting(
        leafPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (leaf == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = validateNewIndexEntryIn(leaf, space, key, 0);
    if (!status.isOk() && status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    status = stageVersionRow(mutations, index, 0, false, heapInsert);
    return status.isOk()
        ? applyStagedIndexEntry(
            leafPageId, leaf, space, key, true, heapInsert.rowId())
        : status;
  }

  private StatusCode applyStagedIndexEntry(
      int leafPageId,
      ByteBuffer leaf,
      int space,
      long key,
      boolean newIndexEntry,
      int rowId) {
    StatusCode status = newIndexEntry
        ? BTreePage.insertLeaf(leaf, space, key, rowId)
        : BTreePage.updateLeaf(leaf, space, key, rowId);
    return newIndexEntry && status == StatusCode.RESOURCE_EXHAUSTED
        ? splitAndInsert(leafPageId, leaf, space, key, rowId) : status;
  }

  boolean canAppendRows(PendingMutationBuffer mutations) {
    int required = 0;
    for (int index = 0; index < mutations.count(); index++) {
      required += HeapPage.SLOT_BYTES + mutations.rowLengthAt(index);
    }
    return currentHeapAvailableBytes() >= required;
  }

  private StatusCode stageVersionRow(
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
      status = splitAndInsert(leafPageId, leaf, space, key, heapInsert.rowId());
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
    int rowId = indexLookup.rowId();
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
          BTreePage.valueAt(leaf, entry), cursor.visibleCommitSequence());
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
    int latestRowId = indexLookup.rowId();
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
    int latestRowId = indexLookup.rowId();
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
    StatusCode structural = IndexedWalCodec.validateInsert(payload);
    if (!structural.isOk()) {
      return structural;
    }
    long key = IndexedWalCodec.insertKey(payload);
    int space = IndexedWalCodec.insertSpace(payload);
    int rowId = IndexedWalCodec.insertRowId(payload);
    int rowBytes = IndexedWalCodec.insertRowBytes(payload);
    if (!OrderedKey.isFiniteSpace(space) || !pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    StatusCode target = validateNewIndexEntry(space, key, 0);
    int leafPageId = mutationValidator.leafPageId();
    ByteBuffer leaf = pages.currentPayload(leafPageId);
    if (!target.isOk()
        || rowCount + 1 != rowId
        || !canAppendRow(rowBytes)
        || leaf == null) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = appendCurrentRow(
        payload,
        IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES,
        rowBytes,
        rowId,
        recordStart,
        recordEnd,
        commitSequence,
        0,
        false);
    if (status.isOk()) {
      status = BTreePage.insertLeaf(leaf, space, key, rowId);
    }
    if (!status.isOk()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    pages.markCurrentChanged(leafPageId, recordStart, recordEnd);
    return StatusCode.OK;
  }

  StatusCode applyInsertBatchOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    StatusCode structural = IndexedWalCodec.validateInsertBatch(
        payload, IndexedTableLimits.MAX_ROWS);
    if (!structural.isOk()) {
      return structural;
    }
    if (!pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    int insertCount = IndexedWalCodec.batchEntryCount(payload);
    int firstRowId = rowCount + 1;
    int entryOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      StatusCode status = validateEncodedInsertEntry(
          payload, entryOffset, firstRowId + index);
      if (!status.isOk()) return status;
      entryOffset += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES
          + IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
    }
    if (entryOffset != payload.limit()) {
      return StatusCode.CORRUPTION;
    }
    if (!canAppendEncodedRows(
        payload,
        IndexedWalCodec.INSERT_BATCH_HEADER_BYTES,
        insertCount,
        12,
        IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES)) {
      return StatusCode.CORRUPTION;
    }
    entryOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      StatusCode status = applyEncodedInsertEntry(
          payload, entryOffset, recordStart, recordEnd, commitSequence);
      if (!status.isOk()) return status;
      entryOffset += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES
          + IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
    }
    return StatusCode.OK;
  }

  private StatusCode validateEncodedInsertEntry(
      ByteBuffer payload, int entryOffset, int expectedRowId) {
    if (!IndexedWalCodec.validInsertBatchEntry(payload, entryOffset)) {
      return StatusCode.CORRUPTION;
    }
    long key = IndexedWalCodec.insertBatchKey(payload, entryOffset);
    int space = IndexedWalCodec.insertBatchSpace(payload, entryOffset);
    if (!OrderedKey.isFiniteSpace(space)
        || IndexedWalCodec.insertBatchRowId(payload, entryOffset) != expectedRowId
        || containsEarlierInsertKey(payload, entryOffset, space, key)) {
      return StatusCode.CORRUPTION;
    }
    int leafPageId = findLeafPageId(space, key);
    int earlierInLeaf = countEarlierInsertEntriesInLeaf(
        payload, entryOffset, leafPageId);
    return validateNewIndexEntryAt(leafPageId, space, key, earlierInLeaf).isOk()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode applyEncodedInsertEntry(
      ByteBuffer payload,
      int entryOffset,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    long key = IndexedWalCodec.insertBatchKey(payload, entryOffset);
    int space = IndexedWalCodec.insertBatchSpace(payload, entryOffset);
    int rowId = IndexedWalCodec.insertBatchRowId(payload, entryOffset);
    int rowBytes = IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
    int rowOffset = entryOffset + IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES;
    int leafPageId = findLeafPageId(space, key);
    ByteBuffer leaf = pages.currentPayload(leafPageId);
    StatusCode status = appendCurrentRow(
        payload, rowOffset, rowBytes, rowId, recordStart, recordEnd,
        commitSequence, 0, false);
    if (status.isOk()) status = BTreePage.insertLeaf(leaf, space, key, rowId);
    if (!status.isOk()) return StatusCode.INVARIANT_BROKEN;
    pages.markCurrentChanged(leafPageId, recordStart, recordEnd);
    return StatusCode.OK;
  }

  StatusCode applyMutationBatchOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    StatusCode structural = IndexedWalCodec.validateMutationBatch(
        payload, IndexedTableLimits.MAX_ROWS);
    if (!structural.isOk()) {
      return structural;
    }
    if (!pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    int mutationCount = IndexedWalCodec.batchEntryCount(payload);
    int firstRowId = rowCount + 1;
    int entryOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      StatusCode status = validateEncodedMutationEntry(
          payload, entryOffset, firstRowId + index);
      if (!status.isOk()) return status;
      entryOffset += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES
          + IndexedWalCodec.mutationRowBytes(payload, entryOffset);
    }
    if (entryOffset != payload.limit()) {
      return StatusCode.CORRUPTION;
    }
    if (!canAppendEncodedRows(
        payload,
        IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES,
        mutationCount,
        20,
        IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES)) {
      return StatusCode.CORRUPTION;
    }
    entryOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      StatusCode status = applyEncodedMutationEntry(
          payload, entryOffset, recordStart, recordEnd, commitSequence);
      if (!status.isOk()) return status;
      entryOffset += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES
          + IndexedWalCodec.mutationRowBytes(payload, entryOffset);
    }
    return StatusCode.OK;
  }

  private StatusCode validateEncodedMutationEntry(
      ByteBuffer payload, int entryOffset, int expectedRowId) {
    if (!IndexedWalCodec.validMutationBatchEntry(payload, entryOffset)) {
      return StatusCode.CORRUPTION;
    }
    int operation = IndexedWalCodec.mutationOperation(payload, entryOffset);
    int space = IndexedWalCodec.mutationSpace(payload, entryOffset);
    long key = IndexedWalCodec.mutationKey(payload, entryOffset);
    int previousRowId = IndexedWalCodec.mutationPreviousRowId(payload, entryOffset);
    if (!OrderedKey.isFiniteSpace(space)
        || IndexedWalCodec.mutationRowId(payload, entryOffset) != expectedRowId
        || containsEarlierMutationKey(payload, entryOffset, space, key)) {
      return StatusCode.CORRUPTION;
    }
    int leafPageId = findLeafPageId(space, key);
    int earlierInLeaf = countEarlierMutationInsertsInLeaf(
        payload, entryOffset, leafPageId);
    return validateMutationTargetAt(
        leafPageId, operation, space, key, previousRowId, earlierInLeaf).isOk()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode applyEncodedMutationEntry(
      ByteBuffer payload,
      int entryOffset,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    int operation = IndexedWalCodec.mutationOperation(payload, entryOffset);
    int space = IndexedWalCodec.mutationSpace(payload, entryOffset);
    long key = IndexedWalCodec.mutationKey(payload, entryOffset);
    int rowId = IndexedWalCodec.mutationRowId(payload, entryOffset);
    int previousRowId = IndexedWalCodec.mutationPreviousRowId(payload, entryOffset);
    int rowBytes = IndexedWalCodec.mutationRowBytes(payload, entryOffset);
    int rowOffset = entryOffset + IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES;
    int leafPageId = findLeafPageId(space, key);
    ByteBuffer leaf = pages.currentPayload(leafPageId);
    StatusCode status = appendCurrentRow(
        payload, rowOffset, rowBytes, rowId, recordStart, recordEnd,
        commitSequence, previousRowId, operation == IndexedWalCodec.MUTATION_DELETE);
    if (status.isOk()) {
      status = operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0
          ? BTreePage.insertLeaf(leaf, space, key, rowId)
          : BTreePage.updateLeaf(leaf, space, key, rowId);
    }
    if (!status.isOk()) return StatusCode.INVARIANT_BROKEN;
    pages.markCurrentChanged(leafPageId, recordStart, recordEnd);
    return StatusCode.OK;
  }

  private boolean containsEarlierInsertKey(
      ByteBuffer payload,
      int targetEntryOffset,
      int space,
      long key) {
    int entryOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (IndexedWalCodec.insertBatchSpace(payload, entryOffset) == space
          && IndexedWalCodec.insertBatchKey(payload, entryOffset) == key) {
        return true;
      }
      entryOffset += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES
          + IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
    }
    return false;
  }

  private boolean containsEarlierMutationKey(
      ByteBuffer payload,
      int targetEntryOffset,
      int space,
      long key) {
    int entryOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (IndexedWalCodec.mutationSpace(payload, entryOffset) == space
          && IndexedWalCodec.mutationKey(payload, entryOffset) == key) {
        return true;
      }
      entryOffset += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES
          + IndexedWalCodec.mutationRowBytes(payload, entryOffset);
    }
    return false;
  }

  private int countEarlierInsertEntriesInLeaf(
      ByteBuffer payload,
      int targetEntryOffset,
      int leafPageId) {
    int count = 0;
    int entryOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (findLeafPageId(
          IndexedWalCodec.insertBatchSpace(payload, entryOffset),
          IndexedWalCodec.insertBatchKey(payload, entryOffset)) == leafPageId) {
        count++;
      }
      entryOffset += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES
          + IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
    }
    return count;
  }

  private int countEarlierMutationInsertsInLeaf(
      ByteBuffer payload,
      int targetEntryOffset,
      int leafPageId) {
    int count = 0;
    int entryOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (IndexedWalCodec.mutationOperation(payload, entryOffset) == IndexedWalCodec.MUTATION_INSERT
          && IndexedWalCodec.mutationPreviousRowId(payload, entryOffset) == 0
          && findLeafPageId(
              IndexedWalCodec.mutationSpace(payload, entryOffset),
              IndexedWalCodec.mutationKey(payload, entryOffset))
              == leafPageId) {
        count++;
      }
      entryOffset += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES
          + IndexedWalCodec.mutationRowBytes(payload, entryOffset);
    }
    return count;
  }

  private StatusCode splitAndInsert(
      int leftPageId,
      ByteBuffer left,
      int space,
      long key,
      int rowId) {
    ByteBuffer metadata = pages.stageExisting(ROOT_META_PAGE_ID, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (metadata == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int rightPageId = BTreeRootPage.allocatePage(metadata);
    ByteBuffer right = pages.stageNew(rightPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (right == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = BTreePage.splitLeaf(
        left, right, rightPageId, space, key, rowId, splitResult);
    if (!status.isOk()) {
      return status;
    }
    long separator = splitResult.separatorKey();
    int separatorSpace = splitResult.separatorSpace();
    int promotedLeftPageId = leftPageId;
    int promotedRightPageId = rightPageId;
    for (int level = splitPathDepth - 1; level >= 0; level--) {
      int parentPageId = splitPathPageIds[level];
      status = promoteIntoParent(
          metadata, parentPageId, separatorSpace, separator, promotedRightPageId);
      if (!status.isOk()) return status;
      if (!splitParentPromoted) return StatusCode.OK;
      int internalRightPageId = splitPromotedRightPageId;
      separator = splitResult.separatorKey();
      separatorSpace = splitResult.separatorSpace();
      promotedLeftPageId = parentPageId;
      promotedRightPageId = internalRightPageId;
    }
    int newRootPageId = BTreeRootPage.allocatePage(metadata);
    ByteBuffer root = pages.stageNew(newRootPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (root == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = BTreePage.initializeInternal(root, promotedLeftPageId);
    if (status.isOk()) {
      status = BTreePage.insertInternal(
          root, separatorSpace, separator, promotedRightPageId);
    }
    if (status.isOk()) {
      BTreeRootPage.publishRoot(metadata, newRootPageId);
    }
    return status;
  }

  private StatusCode promoteIntoParent(
      ByteBuffer metadata,
      int parentPageId,
      int separatorSpace,
      long separator,
      int promotedRightPageId) {
    splitParentPromoted = false;
    ByteBuffer parent = pages.stageExisting(
        parentPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (parent == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = BTreePage.insertInternal(
        parent, separatorSpace, separator, promotedRightPageId);
    if (status != StatusCode.RESOURCE_EXHAUSTED) return status;
    int internalRightPageId = BTreeRootPage.allocatePage(metadata);
    ByteBuffer internalRight = pages.stageNew(
        internalRightPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (internalRight == null) return StatusCode.RESOURCE_EXHAUSTED;
    status = BTreePage.splitInternal(
        parent, internalRight, separatorSpace, separator,
        promotedRightPageId, splitResult);
    if (status.isOk()) {
      splitPromotedRightPageId = internalRightPageId;
      splitParentPromoted = true;
    }
    return status;
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
    return findLeafPageId(space, key, false, false);
  }

  private int findOperationLeafPageId(int space, long key) {
    return findLeafPageId(space, key, true, true);
  }

  private int findLeafPageId(
      int space, long key, boolean operation, boolean capturePath) {
    splitPathDepth = 0;
    ByteBuffer metadata = operation
        ? pages.operationPayload(ROOT_META_PAGE_ID)
        : pages.currentPayload(ROOT_META_PAGE_ID);
    if (metadata == null) {
      return 0;
    }
    int pageId = BTreeRootPage.rootPageId(metadata);
    for (int depth = 0; depth < MAXIMUM_TREE_HEIGHT; depth++) {
      ByteBuffer page = operation
          ? pages.operationPayload(pageId) : pages.currentPayload(pageId);
      if (page == null) {
        return 0;
      }
      if (BTreePage.type(page) == BTreePage.TYPE_LEAF) {
        return pageId;
      }
      if (BTreePage.type(page) != BTreePage.TYPE_INTERNAL) {
        return 0;
      }
      if (capturePath) {
        splitPathPageIds[splitPathDepth++] = pageId;
      }
      pageId = BTreePage.childForKey(page, space, key);
    }
    return 0;
  }

  private static boolean validMutation(int operation) {
    return operation == IndexedWalCodec.MUTATION_INSERT
        || operation == IndexedWalCodec.MUTATION_UPDATE
        || operation == IndexedWalCodec.MUTATION_DELETE;
  }

}
