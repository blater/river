package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeLookupResult;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.BTreeSplitResult;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** Allocation-conscious indexed-table behavior over one durable table store. */
final class IndexedTableKernel {
  static final int HEAP_PAGE_ID = 1;
  static final int ROOT_META_PAGE_ID = 2;
  static final int INITIAL_LEAF_PAGE_ID = 3;
  private static final long BOOTSTRAP_TRANSACTION_ID = 1;
  private static final int MAXIMUM_TREE_HEIGHT = 8;

  private final IndexedTableStore store;
  private final IndexedPageSet pages;
  private final HeapInsertResult heapInsert = new HeapInsertResult();
  private final long[] rowCommitSequences = new long[IndexedTableStore.MAX_ROWS + 1];
  private final int[] previousRowIds = new int[IndexedTableStore.MAX_ROWS + 1];
  private final int[] rowPageIds = new int[IndexedTableStore.MAX_ROWS + 1];
  private final int[] rowSlots = new int[IndexedTableStore.MAX_ROWS + 1];
  private final boolean[] deletedRows = new boolean[IndexedTableStore.MAX_ROWS + 1];
  private final boolean[] vacuumDeletedRows = new boolean[IndexedTableStore.MAX_ROWS + 1];
  private final int[] operationPreviousRowIds =
      new int[IndexedTableStore.MAX_OPERATION_ROWS];
  private final boolean[] operationDeletedRows =
      new boolean[IndexedTableStore.MAX_OPERATION_ROWS];
  private final BTreeLookupResult indexLookup = new BTreeLookupResult();
  private final BTreeSplitResult splitResult = new BTreeSplitResult();
  private final int[] splitPathPageIds = new int[MAXIMUM_TREE_HEIGHT];
  private final TreeValidation treeValidation = new TreeValidation();
  private int splitPathDepth;
  private int validatedLeafPageId;
  private int rowCount;
  private int obsoleteVersionCount;
  private int lastHeapPageId = HEAP_PAGE_ID;
  private int operationRowCount;
  private int operationVersionCount;
  private int operationLastHeapPageId = HEAP_PAGE_ID;
  private int vacuumHeapPageId;
  private long vacuumLastKey;

  int operationVersionCount() {
    return operationVersionCount;
  }

  int operationRowCount() {
    return operationRowCount;
  }

  int operationPreviousRowId(int index) {
    return operationPreviousRowIds[index];
  }

  boolean operationDeleted(int index) {
    return operationDeletedRows[index];
  }

  int lastHeapPageId() {
    return lastHeapPageId;
  }

  HeapInsertResult heapInsertResult() {
    return heapInsert;
  }

  void recordVacuumDeleted(int rowId, boolean deleted) {
    vacuumDeletedRows[rowId] = deleted;
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
    return rowId > 0 && rowId <= rowCount ? rowCommitSequences[rowId] : 0;
  }

  int previousRowId(int rowId) {
    return rowId > 0 && rowId <= rowCount ? previousRowIds[rowId] : 0;
  }

  boolean isDeletedRow(int rowId) {
    return rowId > 0 && rowId <= rowCount && deletedRows[rowId];
  }

  void beginOperationState() {
    operationRowCount = rowCount;
    operationVersionCount = 0;
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
        || operationRowCount >= IndexedTableStore.MAX_ROWS
        || operationVersionCount >= IndexedTableStore.MAX_OPERATION_ROWS
        || previousRowId < 0
        || previousRowId > rowCount
        || (deleted && previousRowId == 0)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    ByteBuffer heap = pages.operationPayload(operationLastHeapPageId);
    if (heap == null || !HeapPage.isHeap(heap)) {
      return StatusCode.CORRUPTION;
    }
    if (!HeapPage.canInsert(heap, rowBytes)) {
      ByteBuffer metadata = pages.stageExisting(
          ROOT_META_PAGE_ID, IndexedTableStore.MAX_CHANGED_PAGES);
      if (metadata == null
          || BTreeRootPage.nextPageId(metadata) > IndexedTableStore.MAX_PAGES) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      int heapPageId = BTreeRootPage.allocatePage(metadata);
      heap = pages.stageNew(heapPageId, IndexedTableStore.MAX_CHANGED_PAGES);
      if (heap == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      StatusCode status = HeapPage.initialize(heap);
      if (!status.isOk()) {
        return status;
      }
      operationLastHeapPageId = heapPageId;
    } else {
      heap = pages.stageExisting(
          operationLastHeapPageId, IndexedTableStore.MAX_CHANGED_PAGES);
      if (heap == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    StatusCode status = HeapPage.insertFrom(heap, source, sourceOffset, rowBytes, heapInsert);
    if (status.isOk()) {
      operationRowCount++;
      operationPreviousRowIds[operationVersionCount] = previousRowId;
      operationDeletedRows[operationVersionCount] = deleted;
      operationVersionCount++;
      result.setRowId(operationRowCount);
    }
    return status;
  }

  boolean canAppendRow(int rowBytes) {
    if (rowBytes <= 0
        || rowCount >= IndexedTableStore.MAX_ROWS
        || !pages.isPresent(lastHeapPageId)) {
      return false;
    }
    if (HeapPage.canInsert(pages.currentPayloadUnchecked(lastHeapPageId), rowBytes)) {
      return true;
    }
    ByteBuffer metadata = pages.currentPayloadUnchecked(ROOT_META_PAGE_ID);
    return rowBytes + HeapPage.SLOT_BYTES
            <= pages.currentPayloadUnchecked(HEAP_PAGE_ID).limit() - HeapPage.HEADER_BYTES
        && BTreeRootPage.nextPageId(metadata) <= IndexedTableStore.MAX_PAGES;
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
    if (expectedRowId != rowCount + 1 || expectedRowId > IndexedTableStore.MAX_ROWS) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer heap = pages.currentPayloadUnchecked(lastHeapPageId);
    if (!HeapPage.canInsert(heap, rowBytes)) {
      ByteBuffer metadata = pages.currentPayloadUnchecked(ROOT_META_PAGE_ID);
      if (BTreeRootPage.nextPageId(metadata) > IndexedTableStore.MAX_PAGES) {
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
    rowCommitSequences[rowCount] = commitSequence;
    previousRowIds[rowCount] = previousRowId;
    deletedRows[rowCount] = deleted;
    if (previousRowId > 0) {
      obsoleteVersionCount++;
    }
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
      if (pageRows < 0 || rebuiltRows > IndexedTableStore.MAX_ROWS - pageRows) {
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
    for (int rowId = previousRowCount + 1; rowId <= rowCount; rowId++) {
      rowCommitSequences[rowId] = commitSequence;
      previousRowIds[rowId] = 0;
      deletedRows[rowId] = false;
    }
  }

  void recordOperationVersions(int previousRowCount, long commitSequence) {
    for (int index = 0; index < operationVersionCount; index++) {
      int rowId = previousRowCount + index + 1;
      rowCommitSequences[rowId] = commitSequence;
      previousRowIds[rowId] = operationPreviousRowIds[index];
      deletedRows[rowId] = operationDeletedRows[index];
      if (operationPreviousRowIds[index] > 0) {
        obsoleteVersionCount++;
      }
    }
  }

  void clearOperationVersions() {
    for (int index = 0; index < operationVersionCount; index++) {
      operationPreviousRowIds[index] = 0;
      operationDeletedRows[index] = false;
    }
    operationVersionCount = 0;
  }

  void loadCheckpointVersions(io.riverdb.engine.checkpoint.CheckpointState checkpoint) {
    obsoleteVersionCount = 0;
    for (int rowId = 1; rowId <= checkpoint.rowCount(); rowId++) {
      rowCommitSequences[rowId] = checkpoint.rowCommitSequence(rowId);
      previousRowIds[rowId] = checkpoint.previousRowId(rowId);
      deletedRows[rowId] = checkpoint.isDeleted(rowId);
      if (previousRowIds[rowId] > 0) {
        obsoleteVersionCount++;
      }
    }
  }

  StatusCode applyRecoveredVersions(
      ByteBuffer payload,
      int versionOffset,
      int previousRowCount,
      int versionCount,
      long commitSequence) {
    int recoveredObsoleteVersions = 0;
    for (int index = 0; index < versionCount; index++) {
      if (!IndexedWalCodec.validPageOperationVersion(payload, versionOffset)) {
        return StatusCode.CORRUPTION;
      }
      int previousRowId = IndexedWalCodec.pageVersionPreviousRowId(payload, versionOffset);
      boolean deleted = IndexedWalCodec.pageVersionDeleted(payload, versionOffset);
      int rowId = previousRowCount + index + 1;
      if (previousRowId >= rowId || (deleted && previousRowId == 0)) {
        return StatusCode.CORRUPTION;
      }
      rowCommitSequences[rowId] = commitSequence;
      previousRowIds[rowId] = previousRowId;
      deletedRows[rowId] = deleted;
      if (previousRowId > 0) {
        recoveredObsoleteVersions++;
      }
      versionOffset += IndexedWalCodec.PAGE_OPERATION_VERSION_BYTES;
    }
    obsoleteVersionCount += recoveredObsoleteVersions;
    return StatusCode.OK;
  }

  void publishVacuumVersions(int retainedRows, long commitSequence) {
    for (int rowId = 1; rowId <= IndexedTableStore.MAX_ROWS; rowId++) {
      rowCommitSequences[rowId] = 0;
      previousRowIds[rowId] = 0;
      deletedRows[rowId] = false;
    }
    for (int rowId = 1; rowId <= retainedRows; rowId++) {
      rowCommitSequences[rowId] = commitSequence;
      deletedRows[rowId] = vacuumDeletedRows[rowId];
      vacuumDeletedRows[rowId] = false;
    }
    obsoleteVersionCount = 0;
  }

  void cancelVacuumVersions(int appliedRows) {
    for (int rowId = 1; rowId <= appliedRows; rowId++) {
      vacuumDeletedRows[rowId] = false;
    }
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
    int ordinal = 0;
    int encodedRows = 0;
    int outputOffset = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    for (int pageId = 1; encodedRows < rowLimit && pageId <= pages.highestPageId(); pageId++) {
      ByteBuffer leaf = leafPayload(pageId);
      if (leaf == null) {
        continue;
      }
      int entryCount = BTreePage.entryCount(leaf);
      for (int entry = 0; encodedRows < rowLimit && entry < entryCount; entry++) {
        if (ordinal++ < firstRow) {
          continue;
        }
        int rowId = BTreePage.valueAt(leaf, entry);
        int rowBytes = rowLength(rowId);
        IndexedWalCodec.encodeVacuumEntry(
            payload,
            outputOffset,
            BTreePage.keyAt(leaf, entry),
            rowId,
            rowBytes,
            isDeletedRow(rowId));
        StatusCode status = copyRowTo(
            rowId, payload, outputOffset + IndexedWalCodec.VACUUM_ENTRY_BYTES);
        if (!status.isOk()) {
          return status;
        }
        outputOffset += IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
        encodedRows++;
      }
    }
    if (encodedRows != rowLimit || outputOffset != payloadBytes) {
      return StatusCode.CORRUPTION;
    }
    payload.position(payloadBytes);
    return StatusCode.OK;
  }

  StatusCode beginVacuumApply() {
    vacuumHeapPageId = HEAP_PAGE_ID;
    vacuumLastKey = 0;
    StatusCode status = StatusCode.OK;
    for (int pageId = 1; status.isOk() && pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isPresent(pageId) || !HeapPage.isHeap(pages.currentPayloadUnchecked(pageId))) {
        continue;
      }
      ByteBuffer stagedHeap = pages.stageExisting(pageId, IndexedTableStore.MAX_PAGES);
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
    int oldRowId = IndexedWalCodec.vacuumEntryRowId(payload, entryOffset);
    int rowBytes = IndexedWalCodec.vacuumEntryRowBytes(payload, entryOffset);
    boolean deleted = IndexedWalCodec.vacuumEntryDeleted(payload, entryOffset);
    if (key == Long.MAX_VALUE
        || (compactedRowId > 1 && key <= vacuumLastKey)
        || rowLength(oldRowId) != rowBytes
        || isDeletedRow(oldRowId) != deleted) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = validateVacuumHead(key, oldRowId);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer leaf = pages.stageExisting(validatedLeafPageId, IndexedTableStore.MAX_PAGES);
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
      status = BTreePage.updateLeaf(leaf, key, compactedRowId);
    }
    if (status.isOk()) {
      vacuumDeletedRows[compactedRowId] = deleted;
      vacuumLastKey = key;
    }
    return status;
  }

  void resetVacuumApply() {
    vacuumHeapPageId = 0;
    vacuumLastKey = 0;
  }

  boolean canAppendRows(int[] rowLengths, int count) {
    if (rowCount > IndexedTableStore.MAX_ROWS - count || !pages.isPresent(lastHeapPageId)) {
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
        <= IndexedTableStore.MAX_PAGES - newPages + 1;
  }

  boolean canAppendEncodedRows(
      ByteBuffer payload,
      int firstEntryOffset,
      int count,
      int rowLengthOffset,
      int entryBytes) {
    if (rowCount > IndexedTableStore.MAX_ROWS - count || !pages.isPresent(lastHeapPageId)) {
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
        <= IndexedTableStore.MAX_PAGES - newPages + 1;
  }

  int currentHeapAvailableBytes() {
    return HeapPage.availableBytes(pages.currentPayloadUnchecked(lastHeapPageId));
  }

  StatusCode validateCurrentPage(int pageId) {
    ByteBuffer payload = pages.currentPayloadUnchecked(pageId);
    if (HeapPage.isHeap(payload)) {
      return HeapPage.validate(payload);
    }
    return pageId == ROOT_META_PAGE_ID
        ? BTreeRootPage.validate(payload) : BTreePage.validate(payload);
  }

  StatusCode validateAppliedPages(int[] pageIds, int pageCount) {
    for (int index = 0; index < pageCount; index++) {
      StatusCode status = validateCurrentPage(pageIds[index]);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
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

  IndexedTableKernel(IndexedTableStore tableStore) {
    store = tableStore;
    pages = tableStore.pages();
  }

  StatusCode initialize() {
    StatusCode status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer heap = pages.stageNew(HEAP_PAGE_ID, IndexedTableStore.MAX_CHANGED_PAGES);
    ByteBuffer metadata = pages.stageNew(ROOT_META_PAGE_ID, IndexedTableStore.MAX_CHANGED_PAGES);
    ByteBuffer leaf = pages.stageNew(INITIAL_LEAF_PAGE_ID, IndexedTableStore.MAX_CHANGED_PAGES);
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
      status = store.commit(BOOTSTRAP_TRANSACTION_ID, store.nextCommitSequence());
    }
    if (status.isOk()) {
      status = store.flush();
    }
    if (!status.isOk()) {
      store.cancelOperation();
    }
    return status;
  }

  StatusCode validate() {
    ByteBuffer heap = pages.currentPayload(HEAP_PAGE_ID);
    ByteBuffer metadata = pages.currentPayload(ROOT_META_PAGE_ID);
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
    if (nextPageId > IndexedTableStore.MAX_PAGES + 1) {
      return StatusCode.CORRUPTION;
    }
    for (int pageId = INITIAL_LEAF_PAGE_ID; pageId < nextPageId; pageId++) {
      ByteBuffer page = pages.currentPayload(pageId);
      if (page == null) {
        return StatusCode.CORRUPTION;
      }
      status = HeapPage.isHeap(page) ? HeapPage.validate(page) : BTreePage.validate(page);
      if (!status.isOk()) {
        return status;
      }
    }
    int rootPageId = BTreeRootPage.rootPageId(metadata);
    if (rootPageId < INITIAL_LEAF_PAGE_ID || rootPageId >= nextPageId) {
      return StatusCode.CORRUPTION;
    }
    treeValidation.reset();
    status = validateSubtree(rootPageId, 0, false, Long.MAX_VALUE, 0);
    if (!status.isOk() || treeValidation.versionRows != rowCount) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    if (rowCount > 0) {
      if (treeValidation.previousLeafPageId <= 0) {
        return StatusCode.CORRUPTION;
      }
      ByteBuffer lastLeaf = pages.currentPayload(treeValidation.previousLeafPageId);
      if (BTreePage.rightSiblingPageId(lastLeaf) != 0
          || BTreePage.highKey(lastLeaf) != Long.MAX_VALUE) {
        return StatusCode.CORRUPTION;
      }
    }
    for (int pageId = INITIAL_LEAF_PAGE_ID; pageId < nextPageId; pageId++) {
      ByteBuffer page = pages.currentPayload(pageId);
      if (!HeapPage.isHeap(page) && !treeValidation.visited[pageId]) {
        return StatusCode.CORRUPTION;
      }
    }
    return StatusCode.OK;
  }

  StatusCode insert(
      long transactionId,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    return insertCommitted(transactionId, store.nextCommitSequence(), key, row, result);
  }

  StatusCode commitInsert(
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

  StatusCode commitInserts(
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
      ByteBuffer leaf = pages.stageExisting(leafPageId, IndexedTableStore.MAX_CHANGED_PAGES);
      if (leaf == null) {
        store.cancelOperation();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = validateNewIndexEntryIn(leaf, key, 0);
      if (!status.isOk() && status != StatusCode.RESOURCE_EXHAUSTED) {
        store.cancelOperation();
        return status;
      }
      status = stageVersionRow(rows, rowOffset, rowBytes, 0, false, heapInsert);
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

  StatusCode commitMutations(
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
    for (int index = 0; index < mutationCount; index++) {
      int operation = operations[index];
      long key = keys[index];
      int previousRowId = previousRowIds[index];
      int rowBytes = rowLengths[index];
      int rowOffset = index * rowStride;
      if (!validMutation(operation)
          || key == Long.MAX_VALUE
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
      ByteBuffer leaf = pages.stageExisting(leafPageId, IndexedTableStore.MAX_CHANGED_PAGES);
      if (leaf == null) {
        store.cancelOperation();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      boolean newIndexEntry = operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0;
      status = validateMutationTargetIn(leaf, operation, key, previousRowId, 0);
      if (!status.isOk() && (!newIndexEntry || status != StatusCode.RESOURCE_EXHAUSTED)) {
        store.cancelOperation();
        return status;
      }
      status = stageVersionRow(
          rows,
          rowOffset,
          rowBytes,
          previousRowId,
          operation == IndexedWalCodec.MUTATION_DELETE,
          heapInsert);
      if (!status.isOk()) {
        store.cancelOperation();
        return status;
      }
      status = newIndexEntry
          ? BTreePage.insertLeaf(leaf, key, heapInsert.rowId())
          : BTreePage.updateLeaf(leaf, key, heapInsert.rowId());
      if (newIndexEntry && status == StatusCode.RESOURCE_EXHAUSTED) {
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

  StatusCode preflightPreparedCommitGroup(
      IndexedTransactionSession[] sessions,
      int count) {
    if (sessions == null || count <= 0 || count > sessions.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = store.beginPreparedInsertGroup();
    for (int index = 0; status.isOk() && index < count; index++) {
      status = sessions[index].preflightPreparedWrites(store);
    }
    if (status.isOk()) {
      status = store.finishPreparedInsertPreflight(count);
    }
    if (!status.isOk()) {
      StatusCode cancel = store.cancelPreparedInsertPreflight();
      if (!cancel.isOk()) {
        return cancel;
      }
    }
    return status;
  }

  StatusCode appendPreparedWrites(
      IndexedTransactionSession session,
      long commitSequence) {
    return session.appendPreparedWrites(store, commitSequence);
  }

  StatusCode cancelPreparedInsertGroup() {
    return store.cancelPreparedInsertPreflight();
  }

  StatusCode forcePreparedInserts() {
    return store.forcePreparedInserts();
  }

  StatusCode publishForcedGroup() {
    return store.publishForcedInserts();
  }

  StatusCode vacuum(long transactionId, IndexedVacuumResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return store.commitVacuum(transactionId, store.nextCommitSequence(), result);
  }

  StatusCode vacuumPreflight() {
    return store.vacuumPreflight();
  }

  StatusCode insertCommitted(
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
    int leafPageId = findLeafPageId(key);
    StatusCode status = validateNewIndexEntryAt(leafPageId, key, 0);
    if (!status.isOk() && status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    ByteBuffer currentLeaf = pages.currentPayload(leafPageId);
    if (!canAppendRow(row.remaining())) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) {
      return store.commitInsert(transactionId, commitSequence, key, row, result);
    }
    status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    int operationLeafPageId = findOperationLeafPageId(key);
    if (operationLeafPageId != leafPageId) {
      store.cancelOperation();
      return StatusCode.CORRUPTION;
    }
    ByteBuffer leaf = pages.stageExisting(leafPageId, IndexedTableStore.MAX_CHANGED_PAGES);
    if (leaf == null) {
      store.cancelOperation();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = stageVersionRow(row, row.position(), row.remaining(), 0, false, heapInsert);
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

  StatusCode fetchByKey(long key, HeapRowResult result) {
    return fetchByKeyAt(store.currentCommitSequence(), key, result);
  }

  StatusCode fetchByKeyAt(
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

  StatusCode beginScan(
      IndexedTable owner,
      long visibleCommitSequence,
      long lowerKey,
      long upperKey,
      IndexedScanCursor cursor) {
    if (owner == null
        || visibleCommitSequence < 0
        || lowerKey >= upperKey
        || upperKey == Long.MIN_VALUE
        || cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = findLeafPageId(lowerKey);
    if (leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    return cursor.claim(owner, visibleCommitSequence, lowerKey, upperKey, leafPageId);
  }

  StatusCode nextScan(
      IndexedTable owner,
      IndexedScanCursor cursor,
      IndexedScanResult result) {
    if (owner == null || cursor == null || !cursor.isOwnedBy(owner) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    while (cursor.leafPageId() > 0) {
      ByteBuffer leaf = pages.currentPayload(cursor.leafPageId());
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
            && rowCommitSequence(rowId) > cursor.visibleCommitSequence()) {
          rowId = previousRowId(rowId);
        }
        if (rowId <= 0 || isDeletedRow(rowId)) {
          continue;
        }
        StatusCode status = fetchRow(rowId, result.row());
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

  StatusCode closeScan(IndexedTable owner, IndexedScanCursor cursor) {
    if (owner == null || cursor == null || !cursor.isOwnedBy(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    cursor.complete();
    return StatusCode.OK;
  }

  StatusCode prepareMutation(
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
    return obsoleteVersionCount;
  }

  int remainingVersionCapacity() {
    return IndexedTableStore.MAX_ROWS - rowCount;
  }

  int rootPageId() {
    return BTreeRootPage.rootPageId(pages.currentPayload(ROOT_META_PAGE_ID));
  }

  int pageCount() {
    return store.highestPageId();
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

  long currentCommitSequence() {
    return store.currentCommitSequence();
  }

  long nextCommitSequence() {
    return store.nextCommitSequence();
  }

  long nextTransactionId() {
    return store.nextTransactionId();
  }

  long stagedCopyBytes() {
    return store.stagedCopyBytes();
  }

  long walCopyBytes() {
    return store.walCopyBytes();
  }

  int validatedLeafPageId() {
    return validatedLeafPageId;
  }

  StatusCode validateNewIndexEntry(long key, int earlierEntriesInLeaf) {
    return validateNewIndexEntryAt(findLeafPageId(key), key, earlierEntriesInLeaf);
  }

  StatusCode validateNewIndexEntryAt(int leafPageId, long key, int earlierEntriesInLeaf) {
    validatedLeafPageId = leafPageId;
    if (!pages.isPresent(HEAP_PAGE_ID) || validatedLeafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    return validateNewIndexEntryIn(
        pages.currentPayload(validatedLeafPageId), key, earlierEntriesInLeaf);
  }

  StatusCode validateNewIndexEntryIn(
      ByteBuffer leaf,
      long key,
      int earlierEntriesInLeaf) {
    if (leaf == null) {
      return StatusCode.CORRUPTION;
    }
    StatusCode lookup = BTreePage.lookupLeaf(leaf, key, indexLookup);
    if (lookup.isOk()) {
      return StatusCode.CONFLICT;
    }
    if (lookup != StatusCode.CONFLICT) {
      return lookup;
    }
    return BTreePage.entryCount(leaf) + earlierEntriesInLeaf >= BTreePage.MAX_ENTRIES
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  StatusCode validateMutationTarget(
      int operation,
      long key,
      int previousRowId,
      int earlierNewEntriesInLeaf) {
    return validateMutationTargetAt(
        findLeafPageId(key), operation, key, previousRowId, earlierNewEntriesInLeaf);
  }

  StatusCode validateMutationTargetAt(
      int leafPageId,
      int operation,
      long key,
      int previousRowId,
      int earlierNewEntriesInLeaf) {
    if (operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0) {
      return validateNewIndexEntryAt(leafPageId, key, earlierNewEntriesInLeaf);
    }
    validatedLeafPageId = leafPageId;
    if (!pages.isPresent(HEAP_PAGE_ID) || validatedLeafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    return validateMutationTargetIn(
        pages.currentPayload(validatedLeafPageId), operation, key, previousRowId,
        earlierNewEntriesInLeaf);
  }

  StatusCode validateMutationTargetIn(
      ByteBuffer leaf,
      int operation,
      long key,
      int previousRowId,
      int earlierNewEntriesInLeaf) {
    if (operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0) {
      return validateNewIndexEntryIn(leaf, key, earlierNewEntriesInLeaf);
    }
    if (leaf == null) {
      return StatusCode.CORRUPTION;
    }
    StatusCode lookup = BTreePage.lookupLeaf(leaf, key, indexLookup);
    boolean validHead = lookup.isOk()
        && indexLookup.rowId() == previousRowId
        && previousRowId > 0;
    if (operation == IndexedWalCodec.MUTATION_INSERT) {
      validHead &= isDeletedRow(previousRowId);
    } else {
      validHead &= !isDeletedRow(previousRowId);
    }
    return validHead ? StatusCode.OK : StatusCode.CONFLICT;
  }

  StatusCode validateVacuumHead(long key, int rowId) {
    validatedLeafPageId = findLeafPageId(key);
    if (validatedLeafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    StatusCode lookup = BTreePage.lookupLeaf(
        pages.currentPayload(validatedLeafPageId), key, indexLookup);
    return lookup.isOk() && indexLookup.rowId() == rowId
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  StatusCode flush() {
    return store.flush();
  }

  StatusCode close() {
    return store.close();
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
    int rowId = IndexedWalCodec.insertRowId(payload);
    int rowBytes = IndexedWalCodec.insertRowBytes(payload);
    if (key == Long.MAX_VALUE || !pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    StatusCode target = validateNewIndexEntry(key, 0);
    int leafPageId = validatedLeafPageId;
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
      status = BTreePage.insertLeaf(leaf, key, rowId);
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
        payload, IndexedTableStore.MAX_ROWS);
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
      if (!IndexedWalCodec.validInsertBatchEntry(payload, entryOffset)) {
        return StatusCode.CORRUPTION;
      }
      long key = IndexedWalCodec.insertBatchKey(payload, entryOffset);
      int rowId = IndexedWalCodec.insertBatchRowId(payload, entryOffset);
      int rowBytes = IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
      if (key == Long.MAX_VALUE
          || rowId != firstRowId + index
          || containsEarlierInsertKey(payload, entryOffset, key)) {
        return StatusCode.CORRUPTION;
      }
      int leafPageId = findLeafPageId(key);
      int earlierInLeaf = countEarlierInsertEntriesInLeaf(
          payload, entryOffset, leafPageId);
      if (!validateNewIndexEntryAt(leafPageId, key, earlierInLeaf).isOk()) {
        return StatusCode.CORRUPTION;
      }
      entryOffset += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES + rowBytes;
    }
    if (entryOffset != payload.limit()) {
      return StatusCode.CORRUPTION;
    }
    if (!store.canAppendEncodedRows(
        payload,
        IndexedWalCodec.INSERT_BATCH_HEADER_BYTES,
        insertCount,
        12,
        IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES)) {
      return StatusCode.CORRUPTION;
    }
    entryOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      long key = IndexedWalCodec.insertBatchKey(payload, entryOffset);
      int rowId = IndexedWalCodec.insertBatchRowId(payload, entryOffset);
      int rowBytes = IndexedWalCodec.insertBatchRowBytes(payload, entryOffset);
      int rowOffset = entryOffset + IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES;
      int leafPageId = findLeafPageId(key);
      ByteBuffer leaf = pages.currentPayload(leafPageId);
      StatusCode status = appendCurrentRow(
          payload,
          rowOffset,
          rowBytes,
          rowId,
          recordStart,
          recordEnd,
          commitSequence,
          0,
          false);
      if (status.isOk()) {
        status = BTreePage.insertLeaf(leaf, key, rowId);
      }
      if (!status.isOk()) {
        return StatusCode.INVARIANT_BROKEN;
      }
      pages.markCurrentChanged(leafPageId, recordStart, recordEnd);
      entryOffset = rowOffset + rowBytes;
    }
    return StatusCode.OK;
  }

  StatusCode applyMutationBatchOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    StatusCode structural = IndexedWalCodec.validateMutationBatch(
        payload, IndexedTableStore.MAX_ROWS);
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
      if (!IndexedWalCodec.validMutationBatchEntry(payload, entryOffset)) {
        return StatusCode.CORRUPTION;
      }
      int operation = IndexedWalCodec.mutationOperation(payload, entryOffset);
      long key = IndexedWalCodec.mutationKey(payload, entryOffset);
      int rowId = IndexedWalCodec.mutationRowId(payload, entryOffset);
      int previousRowId = IndexedWalCodec.mutationPreviousRowId(payload, entryOffset);
      int rowBytes = IndexedWalCodec.mutationRowBytes(payload, entryOffset);
      if (key == Long.MAX_VALUE
          || rowId != firstRowId + index
          || containsEarlierMutationKey(payload, entryOffset, key)) {
        return StatusCode.CORRUPTION;
      }
      int leafPageId = findLeafPageId(key);
      int earlierInLeaf = countEarlierMutationInsertsInLeaf(
          payload, entryOffset, leafPageId);
      if (!validateMutationTargetAt(
          leafPageId, operation, key, previousRowId, earlierInLeaf).isOk()) {
        return StatusCode.CORRUPTION;
      }
      entryOffset += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES + rowBytes;
    }
    if (entryOffset != payload.limit()) {
      return StatusCode.CORRUPTION;
    }
    if (!store.canAppendEncodedRows(
        payload,
        IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES,
        mutationCount,
        20,
        IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES)) {
      return StatusCode.CORRUPTION;
    }
    entryOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      int operation = IndexedWalCodec.mutationOperation(payload, entryOffset);
      long key = IndexedWalCodec.mutationKey(payload, entryOffset);
      int rowId = IndexedWalCodec.mutationRowId(payload, entryOffset);
      int previousRowId = IndexedWalCodec.mutationPreviousRowId(payload, entryOffset);
      int rowBytes = IndexedWalCodec.mutationRowBytes(payload, entryOffset);
      int rowOffset = entryOffset + IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES;
      int leafPageId = findLeafPageId(key);
      ByteBuffer leaf = pages.currentPayload(leafPageId);
      StatusCode status = appendCurrentRow(
          payload,
          rowOffset,
          rowBytes,
          rowId,
          recordStart,
          recordEnd,
          commitSequence,
          previousRowId,
          operation == IndexedWalCodec.MUTATION_DELETE);
      if (status.isOk()) {
        status = operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0
            ? BTreePage.insertLeaf(leaf, key, rowId)
            : BTreePage.updateLeaf(leaf, key, rowId);
      }
      if (!status.isOk()) {
        return StatusCode.INVARIANT_BROKEN;
      }
      pages.markCurrentChanged(leafPageId, recordStart, recordEnd);
      entryOffset = rowOffset + rowBytes;
    }
    return StatusCode.OK;
  }

  private boolean containsEarlierInsertKey(
      ByteBuffer payload,
      int targetEntryOffset,
      long key) {
    int entryOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (IndexedWalCodec.insertBatchKey(payload, entryOffset) == key) {
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
      long key) {
    int entryOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (IndexedWalCodec.mutationKey(payload, entryOffset) == key) {
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
      if (findLeafPageId(IndexedWalCodec.insertBatchKey(payload, entryOffset)) == leafPageId) {
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
          && findLeafPageId(IndexedWalCodec.mutationKey(payload, entryOffset))
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
      long key,
      int rowId) {
    ByteBuffer metadata = pages.stageExisting(ROOT_META_PAGE_ID, IndexedTableStore.MAX_CHANGED_PAGES);
    if (metadata == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int rightPageId = BTreeRootPage.allocatePage(metadata);
    ByteBuffer right = pages.stageNew(rightPageId, IndexedTableStore.MAX_CHANGED_PAGES);
    if (right == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = BTreePage.splitLeaf(
        left, right, rightPageId, key, rowId, splitResult);
    if (!status.isOk()) {
      return status;
    }
    long separator = splitResult.separatorKey();
    int promotedLeftPageId = leftPageId;
    int promotedRightPageId = rightPageId;
    for (int level = splitPathDepth - 1; level >= 0; level--) {
      int parentPageId = splitPathPageIds[level];
      ByteBuffer parent = pages.stageExisting(parentPageId, IndexedTableStore.MAX_CHANGED_PAGES);
      if (parent == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = BTreePage.insertInternal(parent, separator, promotedRightPageId);
      if (status.isOk()) {
        return StatusCode.OK;
      }
      if (status != StatusCode.RESOURCE_EXHAUSTED) {
        return status;
      }
      int internalRightPageId = BTreeRootPage.allocatePage(metadata);
      ByteBuffer internalRight = pages.stageNew(internalRightPageId, IndexedTableStore.MAX_CHANGED_PAGES);
      if (internalRight == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = BTreePage.splitInternal(
          parent,
          internalRight,
          separator,
          promotedRightPageId,
          splitResult);
      if (!status.isOk()) {
        return status;
      }
      separator = splitResult.separatorKey();
      promotedLeftPageId = parentPageId;
      promotedRightPageId = internalRightPageId;
    }
    int newRootPageId = BTreeRootPage.allocatePage(metadata);
    ByteBuffer root = pages.stageNew(newRootPageId, IndexedTableStore.MAX_CHANGED_PAGES);
    if (root == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = BTreePage.initializeInternal(root, promotedLeftPageId);
    if (status.isOk()) {
      status = BTreePage.insertInternal(root, separator, promotedRightPageId);
    }
    if (status.isOk()) {
      BTreeRootPage.publishRoot(metadata, newRootPageId);
    }
    return status;
  }

  private StatusCode lookupRowId(long key) {
    int leafPageId = findLeafPageId(key);
    if (leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    return BTreePage.lookupLeaf(pages.currentPayload(leafPageId), key, indexLookup);
  }

  int findLeafPageId(long key) {
    return findLeafPageId(key, false, false);
  }

  private int findOperationLeafPageId(long key) {
    return findLeafPageId(key, true, true);
  }

  private int findLeafPageId(long key, boolean operation, boolean capturePath) {
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
      pageId = BTreePage.childForKey(page, key);
    }
    return 0;
  }

  private StatusCode validateSubtree(
      int pageId,
      long lowerBound,
      boolean hasLowerBound,
      long upperBound,
      int depth) {
    if (pageId <= 0
        || pageId > IndexedTableStore.MAX_PAGES
        || depth >= MAXIMUM_TREE_HEIGHT
        || treeValidation.visited[pageId]) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer page = pages.currentPayload(pageId);
    if (page == null || HeapPage.isHeap(page)) {
      return StatusCode.CORRUPTION;
    }
    treeValidation.visited[pageId] = true;
    int type = BTreePage.type(page);
    int entryCount = BTreePage.entryCount(page);
    if (BTreePage.highKey(page) != upperBound) {
      return StatusCode.CORRUPTION;
    }
    if (type == BTreePage.TYPE_LEAF) {
      if (entryCount == 0) {
        return depth == 0 && rowCount == 0
            ? StatusCode.OK : StatusCode.CORRUPTION;
      }
      long firstKey = BTreePage.keyAt(page, 0);
      if (hasLowerBound && firstKey != lowerBound) {
        return StatusCode.CORRUPTION;
      }
      if (treeValidation.previousLeafPageId > 0) {
        ByteBuffer previous = pages.currentPayload(treeValidation.previousLeafPageId);
        if (BTreePage.rightSiblingPageId(previous) != pageId
            || BTreePage.highKey(previous) != firstKey) {
          return StatusCode.CORRUPTION;
        }
      }
      int leafVersions = versionRowsInLeaf(page, rowCount);
      if (leafVersions < 0
          || treeValidation.versionRows > rowCount - leafVersions) {
        return StatusCode.CORRUPTION;
      }
      treeValidation.versionRows += leafVersions;
      treeValidation.previousLeafPageId = pageId;
      return StatusCode.OK;
    }
    if (type != BTreePage.TYPE_INTERNAL || entryCount <= 0) {
      return StatusCode.CORRUPTION;
    }
    int childPageId = BTreePage.firstChildPageId(page);
    long childLower = lowerBound;
    boolean childHasLower = hasLowerBound;
    for (int childIndex = 0; childIndex <= entryCount; childIndex++) {
      long childUpper = childIndex < entryCount
          ? BTreePage.keyAt(page, childIndex) : upperBound;
      StatusCode status = validateSubtree(
          childPageId,
          childLower,
          childHasLower,
          childUpper,
          depth + 1);
      if (!status.isOk()) {
        return status;
      }
      if (childIndex < entryCount) {
        childLower = childUpper;
        childHasLower = true;
        childPageId = BTreePage.valueAt(page, childIndex);
      }
    }
    return StatusCode.OK;
  }

  private int versionRowsInLeaf(ByteBuffer leaf, int heapRows) {
    int versionRows = 0;
    int entryCount = BTreePage.entryCount(leaf);
    for (int entry = 0; entry < entryCount; entry++) {
      int rowId = BTreePage.valueAt(leaf, entry);
      long newerCommitSequence = 0;
      while (rowId > 0) {
        long commitSequence = rowCommitSequence(rowId);
        if (rowId > heapRows
            || commitSequence <= 0
            || (newerCommitSequence != 0 && commitSequence >= newerCommitSequence)) {
          return -1;
        }
        int previousRowId = previousRowId(rowId);
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

  private static boolean validMutation(int operation) {
    return operation == IndexedWalCodec.MUTATION_INSERT
        || operation == IndexedWalCodec.MUTATION_UPDATE
        || operation == IndexedWalCodec.MUTATION_DELETE;
  }

  private static final class TreeValidation {
    private final boolean[] visited = new boolean[IndexedTableStore.MAX_PAGES + 1];
    private int previousLeafPageId;
    private int versionRows;

    private void reset() {
      for (int pageId = 0; pageId < visited.length; pageId++) {
        visited[pageId] = false;
      }
      previousLeafPageId = 0;
      versionRows = 0;
    }
  }
}
