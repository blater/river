package io.riverdb.engine.page;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.storage.btree.BTreeLookupResult;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalReadResult;
import io.riverdb.wal.local.LocalWalReservation;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Single-owner bounded page store whose WAL operations atomically cover heap and index state. */
public final class IndexedPageStore {
  public static final String FILE_NAME = "river.indexed.pages";
  public static final int WAL_FORMAT_ID = 1002;
  public static final int WAL_FORMAT_VERSION = 1;
  public static final int MAX_PAGES = 12;
  public static final int MAX_CHANGED_PAGES = MAX_PAGES;
  public static final int MAX_ROWS = 2048;

  private static final long OPERATION_MAGIC = 0x5249564552494458L; // RIVERIDX
  private static final int OPERATION_TYPE_PAGE_IMAGES = 1;
  private static final int OPERATION_TYPE_INSERT = 2;
  private static final int OPERATION_TYPE_INSERT_BATCH = 3;
  private static final int OPERATION_TYPE_MUTATION_BATCH = 4;
  private static final int OPERATION_TYPE_VACUUM = 5;
  private static final int MUTATION_INSERT = 1;
  private static final int MUTATION_UPDATE = 2;
  private static final int MUTATION_DELETE = 3;
  private static final int PAGE_OPERATION_HEADER_BYTES = 20;
  private static final int INSERT_OPERATION_HEADER_BYTES = 40;
  private static final int INSERT_BATCH_HEADER_BYTES = 24;
  private static final int INSERT_BATCH_ENTRY_BYTES = 16;
  private static final int MUTATION_BATCH_HEADER_BYTES = 24;
  private static final int MUTATION_BATCH_ENTRY_BYTES = 24;
  private static final int VACUUM_HEADER_BYTES = 24;
  private static final int VACUUM_ENTRY_BYTES = 24;
  private static final int HEAP_PAGE_ID = 1;
  private static final int ROOT_META_PAGE_ID = 2;

  private final DurableDirectory directory;
  private final DurableFile file;
  private final LocalWal wal;
  private final DatabaseIncarnation database;
  private final WalGeneration walGeneration;
  private final ByteBuffer[] currentPages = new ByteBuffer[MAX_PAGES + 1];
  private final ByteBuffer[] currentPayloads = new ByteBuffer[MAX_PAGES + 1];
  private final ByteBuffer[] stagingPages = new ByteBuffer[MAX_PAGES + 1];
  private final ByteBuffer[] stagingPayloads = new ByteBuffer[MAX_PAGES + 1];
  private final boolean[] present = new boolean[MAX_PAGES + 1];
  private final boolean[] staged = new boolean[MAX_PAGES + 1];
  private final boolean[] dirty = new boolean[MAX_PAGES + 1];
  private final long[] pageRecordStarts = new long[MAX_PAGES + 1];
  private final long[] pageRecordEnds = new long[MAX_PAGES + 1];
  private final long[] rowCommitSequences = new long[MAX_ROWS + 1];
  private final int[] previousRowIds = new int[MAX_ROWS + 1];
  private final boolean[] deletedRows = new boolean[MAX_ROWS + 1];
  private final int[] changedPageIds = new int[MAX_CHANGED_PAGES];
  private final int[] recoveryPageIds = new int[MAX_CHANGED_PAGES];
  private final CRC32C checksum = new CRC32C();
  private final IoResult ioResult = new IoResult();
  private final PageHeader pageHeader = new PageHeader();
  private final LocalWalReservation walReservation = new LocalWalReservation();
  private final LocalWalAppendResult walAppendResult = new LocalWalAppendResult();
  private final LocalWalReadResult walReadResult = new LocalWalReadResult();
  private final HeapInsertResult appliedInsert = new HeapInsertResult();
  private final BTreeLookupResult lookupResult = new BTreeLookupResult();
  private int changedPageCount;
  private int highestPageId;
  private boolean operationActive;
  private boolean failed;
  private boolean closed;
  private long stagedCopyBytes;
  private long walCopyBytes;
  private long lastCommitSequence;

  private IndexedPageStore(
      DurableDirectory durableDirectory,
      DurableFile durableFile,
      LocalWal localWal,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration generation) {
    directory = durableDirectory;
    file = durableFile;
    wal = localWal;
    database = databaseIncarnation;
    walGeneration = generation;
    for (int pageId = 1; pageId <= MAX_PAGES; pageId++) {
      currentPages[pageId] = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);
      currentPayloads[pageId] = payloadView(currentPages[pageId]);
      stagingPages[pageId] = ByteBuffer.allocateDirect(PageCodec.PAGE_BYTES);
      stagingPayloads[pageId] = payloadView(stagingPages[pageId]);
    }
  }

  public static StatusCode create(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      IndexedPageStoreOpenResult result) {
    if (!validInput(directory, wal, database, walGeneration, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.createFile(FILE_NAME, operation);
    if (!status.isOk()) {
      return status;
    }
    result.set(new IndexedPageStore(
        directory, operation.file(), wal, database, walGeneration));
    return StatusCode.OK;
  }

  public static StatusCode open(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      IndexedPageStoreOpenResult result) {
    return open(directory, wal, database, walGeneration, true, result);
  }

  public static StatusCode openExisting(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      IndexedPageStoreOpenResult result) {
    return open(directory, wal, database, walGeneration, false, result);
  }

  private static StatusCode open(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      boolean createWhenMissing,
      IndexedPageStoreOpenResult result) {
    if (!validInput(directory, wal, database, walGeneration, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.reopen(FILE_NAME, operation);
    if (status == StatusCode.CONFLICT && createWhenMissing) {
      status = directory.createFile(FILE_NAME, operation);
    }
    if (!status.isOk()) {
      return status;
    }
    IndexedPageStore store = new IndexedPageStore(
        directory, operation.file(), wal, database, walGeneration);
    status = store.recoverFromWal();
    if (status.isOk()) {
      status = store.flush();
    }
    if (!status.isOk()) {
      store.file.close();
      return status;
    }
    result.set(store);
    return StatusCode.OK;
  }

  public StatusCode beginOperation() {
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (operationActive) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    changedPageCount = 0;
    operationActive = true;
    return StatusCode.OK;
  }

  public ByteBuffer currentPayload(int pageId) {
    return validPresentPage(pageId) ? currentPayloads[pageId] : null;
  }

  public ByteBuffer stageExisting(int pageId) {
    if (!operationActive || pageId <= 0 || pageId > MAX_PAGES) {
      return null;
    }
    if (staged[pageId]) {
      return stagingPayloads[pageId];
    }
    if (!validPresentPage(pageId)) {
      return null;
    }
    if (!addChangedPage(pageId)) {
      return null;
    }
    copyPage(currentPages[pageId], stagingPages[pageId]);
    stagedCopyBytes += PageCodec.PAGE_BYTES;
    return stagingPayloads[pageId];
  }

  /** Mutable operation view: staged state when present, otherwise current committed state. */
  public ByteBuffer operationPayload(int pageId) {
    if (!operationActive || pageId <= 0 || pageId > MAX_PAGES) {
      return null;
    }
    if (staged[pageId]) {
      return stagingPayloads[pageId];
    }
    return present[pageId] ? currentPayloads[pageId] : null;
  }

  public ByteBuffer stageNew(int pageId) {
    if (!operationActive || pageId <= 0 || pageId > MAX_PAGES || present[pageId]) {
      return null;
    }
    if (staged[pageId]) {
      return stagingPayloads[pageId];
    }
    if (!addChangedPage(pageId)) {
      return null;
    }
    ByteBuffer page = stagingPages[pageId];
    page.clear();
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      page.put(index, (byte) 0);
    }
    ByteBuffer payload = stagingPayloads[pageId];
    payload.clear();
    return payload;
  }

  public StatusCode commit(long transactionId, long commitSequence) {
    if (!operationActive
        || transactionId <= 0
        || commitSequence <= lastCommitSequence
        || changedPageCount <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int operationBytes = PAGE_OPERATION_HEADER_BYTES + changedPageCount * PageCodec.PAGE_BYTES;
    StatusCode status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    putLong(recordPayload, 0, OPERATION_MAGIC);
    putInt(recordPayload, 8, WAL_FORMAT_VERSION);
    putInt(recordPayload, 12, OPERATION_TYPE_PAGE_IMAGES);
    putInt(recordPayload, 16, changedPageCount);
    int outputOffset = PAGE_OPERATION_HEADER_BYTES;
    for (int index = 0; index < changedPageCount; index++) {
      int pageId = changedPageIds[index];
      status = PageCodec.encode(
          database,
          walGeneration,
          pageId,
          1,
          walReservation.recordStartOffset(),
          walReservation.recordEndOffset(),
          PageCodec.MAX_PAYLOAD_BYTES,
          stagingPages[pageId],
          checksum);
      if (!status.isOk()) {
        wal.cancel(walReservation);
        return status;
      }
      copyToRecord(stagingPages[pageId], recordPayload, outputOffset);
      walCopyBytes += PageCodec.PAGE_BYTES;
      outputOffset += PageCodec.PAGE_BYTES;
    }
    recordPayload.position(operationBytes);
    status = wal.publish(
        walReservation,
        transactionId,
        commitSequence,
        1,
        WAL_FORMAT_ID,
        WAL_FORMAT_VERSION,
        walAppendResult);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    int previousRowCount = present[HEAP_PAGE_ID]
        ? HeapPage.rowCount(currentPayloads[HEAP_PAGE_ID]) : 0;
    publishStagedPages();
    recordNewRowCommits(previousRowCount, commitSequence);
    lastCommitSequence = commitSequence;
    operationActive = false;
    changedPageCount = 0;
    return StatusCode.OK;
  }

  /** Commits the common no-split heap/index insert as a compact logical operation. */
  public StatusCode commitInsert(
      long transactionId,
      long commitSequence,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    if (transactionId <= 0
        || commitSequence <= lastCommitSequence
        || key == Long.MAX_VALUE
        || row == null
        || !row.hasRemaining()
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (operationActive) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    ByteBuffer heap = currentPayloads[HEAP_PAGE_ID];
    int leafPageId = findLeafPageId(key);
    if (!present[HEAP_PAGE_ID] || leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer leaf = currentPayloads[leafPageId];
    status = BTreePage.lookupLeaf(leaf, key, lookupResult);
    if (status.isOk()) {
      return StatusCode.CONFLICT;
    }
    if (status != StatusCode.CONFLICT) {
      return status;
    }
    int rowBytes = row.remaining();
    if (!HeapPage.canInsert(heap, rowBytes)
        || BTreePage.entryCount(leaf) >= BTreePage.MAX_ENTRIES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int rowId = HeapPage.rowCount(heap) + 1;
    int operationBytes = INSERT_OPERATION_HEADER_BYTES + rowBytes;
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    putLong(recordPayload, 0, OPERATION_MAGIC);
    putInt(recordPayload, 8, WAL_FORMAT_VERSION);
    putInt(recordPayload, 12, OPERATION_TYPE_INSERT);
    putLong(recordPayload, 16, key);
    putInt(recordPayload, 24, rowId);
    putInt(recordPayload, 28, rowBytes);
    putLong(recordPayload, 32, 0);
    int sourceStart = row.position();
    for (int index = 0; index < rowBytes; index++) {
      recordPayload.put(INSERT_OPERATION_HEADER_BYTES + index, row.get(sourceStart + index));
    }
    walCopyBytes += rowBytes;
    recordPayload.position(operationBytes);
    status = wal.publish(
        walReservation,
        transactionId,
        commitSequence,
        1,
        WAL_FORMAT_ID,
        WAL_FORMAT_VERSION,
        walAppendResult);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    status = applyInsertOperation(
        recordPayload,
        walAppendResult.startOffset(),
        walAppendResult.endOffset(),
        commitSequence);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    lastCommitSequence = commitSequence;
    result.setRowId(rowId);
    return StatusCode.OK;
  }

  /** Commits multiple non-splitting inserts as one compact logical WAL record. */
  public StatusCode commitInsertBatch(
      long transactionId,
      long commitSequence,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      HeapInsertResult result) {
    if (transactionId <= 0
        || commitSequence <= lastCommitSequence
        || keys == null
        || rows == null
        || rowStride <= 0
        || rowLengths == null
        || insertCount <= 1
        || insertCount > keys.length
        || insertCount > rowLengths.length
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (operationActive) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = INSERT_BATCH_HEADER_BYTES;
    int requiredHeapBytes = 0;
    for (int index = 0; index < insertCount; index++) {
      int rowBytes = rowLengths[index];
      int rowOffset = index * rowStride;
      long key = keys[index];
      if (key == Long.MAX_VALUE
          || rowBytes <= 0
          || rowBytes > rowStride
          || rows.limit() - rowOffset < rowBytes) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int previous = 0; previous < index; previous++) {
        if (keys[previous] == key) {
          return StatusCode.CONFLICT;
        }
      }
      int leafPageId = findLeafPageId(key);
      if (!present[HEAP_PAGE_ID] || leafPageId <= 0) {
        return StatusCode.CORRUPTION;
      }
      status = BTreePage.lookupLeaf(currentPayloads[leafPageId], key, lookupResult);
      if (status.isOk()) {
        return StatusCode.CONFLICT;
      }
      if (status != StatusCode.CONFLICT) {
        return status;
      }
      int earlierInLeaf = 0;
      for (int previous = 0; previous < index; previous++) {
        if (findLeafPageId(keys[previous]) == leafPageId) {
          earlierInLeaf++;
        }
      }
      if (BTreePage.entryCount(currentPayloads[leafPageId]) + earlierInLeaf
          >= BTreePage.MAX_ENTRIES) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      requiredHeapBytes += HeapPage.SLOT_BYTES + rowBytes;
      operationBytes += INSERT_BATCH_ENTRY_BYTES + rowBytes;
    }
    if (requiredHeapBytes > HeapPage.availableBytes(currentPayloads[HEAP_PAGE_ID])) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    putLong(recordPayload, 0, OPERATION_MAGIC);
    putInt(recordPayload, 8, WAL_FORMAT_VERSION);
    putInt(recordPayload, 12, OPERATION_TYPE_INSERT_BATCH);
    putInt(recordPayload, 16, insertCount);
    putInt(recordPayload, 20, 0);
    int outputOffset = INSERT_BATCH_HEADER_BYTES;
    int firstRowId = HeapPage.rowCount(currentPayloads[HEAP_PAGE_ID]) + 1;
    for (int index = 0; index < insertCount; index++) {
      int rowBytes = rowLengths[index];
      putLong(recordPayload, outputOffset, keys[index]);
      putInt(recordPayload, outputOffset + 8, firstRowId + index);
      putInt(recordPayload, outputOffset + 12, rowBytes);
      int sourceOffset = index * rowStride;
      int rowOffset = outputOffset + INSERT_BATCH_ENTRY_BYTES;
      for (int byteIndex = 0; byteIndex < rowBytes; byteIndex++) {
        recordPayload.put(rowOffset + byteIndex, rows.get(sourceOffset + byteIndex));
      }
      walCopyBytes += rowBytes;
      outputOffset = rowOffset + rowBytes;
    }
    recordPayload.position(operationBytes);
    status = wal.publish(
        walReservation,
        transactionId,
        commitSequence,
        1,
        WAL_FORMAT_ID,
        WAL_FORMAT_VERSION,
        walAppendResult);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    status = applyInsertBatchOperation(
        recordPayload,
        walAppendResult.startOffset(),
        walAppendResult.endOffset(),
        commitSequence);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    lastCommitSequence = commitSequence;
    result.setRowId(firstRowId + insertCount - 1);
    return StatusCode.OK;
  }

  /** Commits a compact atomic mix of inserts, updates, and tombstone deletes. */
  public StatusCode commitMutationBatch(
      long transactionId,
      long commitSequence,
      int[] operations,
      long[] keys,
      int[] expectedPreviousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      HeapInsertResult result) {
    if (transactionId <= 0
        || commitSequence <= lastCommitSequence
        || operations == null
        || keys == null
        || expectedPreviousRowIds == null
        || rows == null
        || rowStride <= 0
        || rowLengths == null
        || mutationCount <= 0
        || mutationCount > operations.length
        || mutationCount > keys.length
        || mutationCount > expectedPreviousRowIds.length
        || mutationCount > rowLengths.length
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (operationActive || !present[HEAP_PAGE_ID]) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = MUTATION_BATCH_HEADER_BYTES;
    int requiredHeapBytes = 0;
    for (int index = 0; index < mutationCount; index++) {
      int operation = operations[index];
      long key = keys[index];
      int previousRowId = expectedPreviousRowIds[index];
      int rowBytes = rowLengths[index];
      int rowOffset = index * rowStride;
      if ((operation != MUTATION_INSERT
              && operation != MUTATION_UPDATE
              && operation != MUTATION_DELETE)
          || key == Long.MAX_VALUE
          || rowBytes <= 0
          || rowBytes > rowStride
          || rows.limit() - rowOffset < rowBytes) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int previous = 0; previous < index; previous++) {
        if (keys[previous] == key) {
          return StatusCode.CONFLICT;
        }
      }
      int leafPageId = findLeafPageId(key);
      if (leafPageId <= 0) {
        return StatusCode.CORRUPTION;
      }
      status = BTreePage.lookupLeaf(currentPayloads[leafPageId], key, lookupResult);
      if (operation == MUTATION_INSERT) {
        if (previousRowId == 0) {
          if (status != StatusCode.CONFLICT) {
            return status.isOk() ? StatusCode.CONFLICT : status;
          }
          int earlierInLeaf = 0;
          for (int previous = 0; previous < index; previous++) {
            if (operations[previous] == MUTATION_INSERT
                && expectedPreviousRowIds[previous] == 0
                && findLeafPageId(keys[previous]) == leafPageId) {
              earlierInLeaf++;
            }
          }
          if (BTreePage.entryCount(currentPayloads[leafPageId]) + earlierInLeaf
              >= BTreePage.MAX_ENTRIES) {
            return StatusCode.RESOURCE_EXHAUSTED;
          }
        } else if (!status.isOk()
            || lookupResult.rowId() != previousRowId
            || !isDeletedRow(previousRowId)) {
          return StatusCode.CONFLICT;
        }
      } else if (!status.isOk()
          || lookupResult.rowId() != previousRowId
          || previousRowId <= 0
          || isDeletedRow(previousRowId)) {
        return StatusCode.CONFLICT;
      }
      requiredHeapBytes += HeapPage.SLOT_BYTES + rowBytes;
      operationBytes += MUTATION_BATCH_ENTRY_BYTES + rowBytes;
    }
    if (requiredHeapBytes > HeapPage.availableBytes(currentPayloads[HEAP_PAGE_ID])) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    putLong(recordPayload, 0, OPERATION_MAGIC);
    putInt(recordPayload, 8, WAL_FORMAT_VERSION);
    putInt(recordPayload, 12, OPERATION_TYPE_MUTATION_BATCH);
    putInt(recordPayload, 16, mutationCount);
    putInt(recordPayload, 20, 0);
    int outputOffset = MUTATION_BATCH_HEADER_BYTES;
    int firstRowId = HeapPage.rowCount(currentPayloads[HEAP_PAGE_ID]) + 1;
    for (int index = 0; index < mutationCount; index++) {
      int rowBytes = rowLengths[index];
      putInt(recordPayload, outputOffset, operations[index]);
      putLong(recordPayload, outputOffset + 4, keys[index]);
      putInt(recordPayload, outputOffset + 12, firstRowId + index);
      putInt(recordPayload, outputOffset + 16, expectedPreviousRowIds[index]);
      putInt(recordPayload, outputOffset + 20, rowBytes);
      int sourceOffset = index * rowStride;
      int rowOffset = outputOffset + MUTATION_BATCH_ENTRY_BYTES;
      for (int byteIndex = 0; byteIndex < rowBytes; byteIndex++) {
        recordPayload.put(rowOffset + byteIndex, rows.get(sourceOffset + byteIndex));
      }
      walCopyBytes += rowBytes;
      outputOffset = rowOffset + rowBytes;
    }
    recordPayload.position(operationBytes);
    status = wal.publish(
        walReservation,
        transactionId,
        commitSequence,
        1,
        WAL_FORMAT_ID,
        WAL_FORMAT_VERSION,
        walAppendResult);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    status = applyMutationBatchOperation(
        recordPayload,
        walAppendResult.startOffset(),
        walAppendResult.endOffset(),
        commitSequence);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    lastCommitSequence = commitSequence;
    result.setRowId(firstRowId + mutationCount - 1);
    return StatusCode.OK;
  }

  /** Rewrites retained heads as one WAL-atomic heap compaction operation. */
  public StatusCode commitVacuum(
      long transactionId,
      long commitSequence,
      io.riverdb.engine.table.IndexedVacuumResult result) {
    if (transactionId <= 0
        || commitSequence <= lastCommitSequence
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (operationActive || !present[HEAP_PAGE_ID]) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int rowsBefore = HeapPage.rowCount(currentPayloads[HEAP_PAGE_ID]);
    int retainedRows = indexedEntryCount();
    if (retainedRows < 0 || retainedRows > rowsBefore) {
      return StatusCode.CORRUPTION;
    }
    if (retainedRows == rowsBefore) {
      return StatusCode.CONFLICT;
    }
    int operationBytes = VACUUM_HEADER_BYTES + retainedRows * VACUUM_ENTRY_BYTES;
    for (int pageId = 1; pageId <= highestPageId; pageId++) {
      if (!present[pageId]
          || pageId == HEAP_PAGE_ID
          || pageId == ROOT_META_PAGE_ID
          || BTreePage.type(currentPayloads[pageId]) != BTreePage.TYPE_LEAF) {
        continue;
      }
      int entryCount = BTreePage.entryCount(currentPayloads[pageId]);
      for (int entry = 0; entry < entryCount; entry++) {
        int rowBytes = HeapPage.rowLength(
            currentPayloads[HEAP_PAGE_ID],
            BTreePage.valueAt(currentPayloads[pageId], entry));
        if (rowBytes <= 0 || operationBytes > Integer.MAX_VALUE - rowBytes) {
          return StatusCode.CORRUPTION;
        }
        operationBytes += rowBytes;
      }
    }
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = walReservation.writablePayload();
    putLong(payload, 0, OPERATION_MAGIC);
    putInt(payload, 8, WAL_FORMAT_VERSION);
    putInt(payload, 12, OPERATION_TYPE_VACUUM);
    putInt(payload, 16, retainedRows);
    putInt(payload, 20, 0);
    int outputOffset = VACUUM_HEADER_BYTES;
    for (int pageId = 1; pageId <= highestPageId; pageId++) {
      if (!present[pageId]
          || pageId == HEAP_PAGE_ID
          || pageId == ROOT_META_PAGE_ID
          || BTreePage.type(currentPayloads[pageId]) != BTreePage.TYPE_LEAF) {
        continue;
      }
      ByteBuffer leaf = currentPayloads[pageId];
      int entryCount = BTreePage.entryCount(leaf);
      for (int entry = 0; entry < entryCount; entry++) {
        int rowId = BTreePage.valueAt(leaf, entry);
        int rowBytes = HeapPage.rowLength(currentPayloads[HEAP_PAGE_ID], rowId);
        putLong(payload, outputOffset, BTreePage.keyAt(leaf, entry));
        putInt(payload, outputOffset + 8, rowId);
        putInt(payload, outputOffset + 12, rowBytes);
        putInt(payload, outputOffset + 16, isDeletedRow(rowId) ? 1 : 0);
        putInt(payload, outputOffset + 20, 0);
        status = HeapPage.copyRowTo(
            currentPayloads[HEAP_PAGE_ID],
            rowId,
            payload,
            outputOffset + VACUUM_ENTRY_BYTES);
        if (!status.isOk()) {
          wal.cancel(walReservation);
          return status;
        }
        walCopyBytes += rowBytes;
        outputOffset += VACUUM_ENTRY_BYTES + rowBytes;
      }
    }
    payload.position(operationBytes);
    status = wal.publish(
        walReservation,
        transactionId,
        commitSequence,
        1,
        WAL_FORMAT_ID,
        WAL_FORMAT_VERSION,
        walAppendResult);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    status = applyVacuumOperation(
        payload,
        walAppendResult.startOffset(),
        walAppendResult.endOffset(),
        commitSequence);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    lastCommitSequence = commitSequence;
    result.set(rowsBefore, retainedRows, commitSequence);
    return StatusCode.OK;
  }

  public StatusCode cancelOperation() {
    if (!operationActive) {
      return StatusCode.CONFLICT;
    }
    clearStagedFlags();
    operationActive = false;
    changedPageCount = 0;
    return StatusCode.OK;
  }

  public StatusCode flush() {
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    for (int pageId = 1; pageId <= highestPageId; pageId++) {
      if (!dirty[pageId]) {
        continue;
      }
      ByteBuffer page = currentPages[pageId];
      status = encodeCurrentPage(
          pageId, pageRecordStarts[pageId], pageRecordEnds[pageId]);
      if (!status.isOk()) {
        return status;
      }
      page.position(0);
      page.limit(PageCodec.PAGE_BYTES);
      status = file.write((long) (pageId - 1) * PageCodec.PAGE_BYTES, page, ioResult);
      if (!status.isOk() || ioResult.bytesTransferred() != PageCodec.PAGE_BYTES) {
        return status.isOk() ? StatusCode.IO_FAILURE : status;
      }
    }
    status = file.truncate((long) highestPageId * PageCodec.PAGE_BYTES);
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    if (status.isOk()) {
      DirectoryOperationResult forceResult = new DirectoryOperationResult();
      status = directory.force(forceResult);
    }
    if (status.isOk()) {
      for (int pageId = 1; pageId <= highestPageId; pageId++) {
        dirty[pageId] = false;
      }
    }
    return status;
  }

  public long stagedCopyBytes() {
    return stagedCopyBytes;
  }

  public long walCopyBytes() {
    return walCopyBytes;
  }

  public int highestPageId() {
    return highestPageId;
  }

  public long nextCommitSequence() {
    return wal.nextCommitSequence();
  }

  public long currentCommitSequence() {
    return wal.currentCommitSequence();
  }

  public long nextTransactionId() {
    return wal.nextTransactionId();
  }

  public long rowCommitSequence(int rowId) {
    return rowId > 0 && rowId <= MAX_ROWS ? rowCommitSequences[rowId] : 0;
  }

  public int previousRowId(int rowId) {
    return rowId > 0 && rowId <= MAX_ROWS ? previousRowIds[rowId] : 0;
  }

  public boolean isDeletedRow(int rowId) {
    return rowId > 0 && rowId <= MAX_ROWS && deletedRows[rowId];
  }

  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (operationActive || hasDirtyPages()) {
      return StatusCode.CONFLICT;
    }
    closed = true;
    return file.close();
  }

  private StatusCode recoverFromWal() {
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    boolean found = false;
    while (offset < wal.tailEnd()) {
      StatusCode status = wal.read(offset, walReadResult);
      if (!status.isOk()) {
        return status;
      }
      if (walReadResult.header().formatId() == WAL_FORMAT_ID
          && walReadResult.header().formatVersion() == WAL_FORMAT_VERSION
          && walReadResult.header().decisionCode() == 1) {
        if (walReadResult.header().commitSequence() <= lastCommitSequence) {
          return StatusCode.CORRUPTION;
        }
        status = applyOperation(
            offset, walReadResult, walReadResult.header().commitSequence());
        if (!status.isOk()) {
          return status;
        }
        lastCommitSequence = walReadResult.header().commitSequence();
        found = true;
      }
      offset = walReadResult.nextOffset();
    }
    return found ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode applyOperation(
      long recordStart,
      LocalWalReadResult record,
      long commitSequence) {
    ByteBuffer payload = record.payload();
    if (record.header().payloadBytes() < PAGE_OPERATION_HEADER_BYTES
        || getLong(payload, 0) != OPERATION_MAGIC
        || getInt(payload, 8) != WAL_FORMAT_VERSION) {
      return StatusCode.CORRUPTION;
    }
    int operationType = getInt(payload, 12);
    if (operationType == OPERATION_TYPE_INSERT) {
      return applyInsertOperation(
          payload, recordStart, record.nextOffset(), commitSequence);
    }
    if (operationType == OPERATION_TYPE_INSERT_BATCH) {
      return applyInsertBatchOperation(
          payload, recordStart, record.nextOffset(), commitSequence);
    }
    if (operationType == OPERATION_TYPE_MUTATION_BATCH) {
      return applyMutationBatchOperation(
          payload, recordStart, record.nextOffset(), commitSequence);
    }
    if (operationType == OPERATION_TYPE_VACUUM) {
      return applyVacuumOperation(
          payload, recordStart, record.nextOffset(), commitSequence);
    }
    if (operationType != OPERATION_TYPE_PAGE_IMAGES) {
      return StatusCode.CORRUPTION;
    }
    return applyPageOperation(
        payload,
        recordStart,
        record.nextOffset(),
        record.header().payloadBytes(),
        commitSequence);
  }

  private StatusCode applyPageOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      int payloadBytes,
      long commitSequence) {
    int pageCount = getInt(payload, 16);
    if (pageCount <= 0
        || pageCount > MAX_CHANGED_PAGES
        || payloadBytes != PAGE_OPERATION_HEADER_BYTES + pageCount * PageCodec.PAGE_BYTES) {
      return StatusCode.CORRUPTION;
    }
    int previousRowCount = present[HEAP_PAGE_ID]
        ? HeapPage.rowCount(currentPayloads[HEAP_PAGE_ID]) : 0;
    for (int index = 0; index < pageCount; index++) {
      int pageOffset = PAGE_OPERATION_HEADER_BYTES + index * PageCodec.PAGE_BYTES;
      StatusCode status = PageCodec.validateAt(payload, pageOffset, pageHeader, checksum);
      int pageId = (int) pageHeader.pageId();
      if (!status.isOk()
          || pageId <= 0
          || pageId > MAX_PAGES
          || pageHeader.pageGeneration() != 1
          || pageHeader.databaseHigh() != database.high()
          || pageHeader.databaseLow() != database.low()
          || pageHeader.walGeneration() != walGeneration.value()
          || pageHeader.recordStart() != recordStart
          || pageHeader.recordEnd() != recordEnd
          || duplicateRecoveryPage(pageId, index)) {
        return status.isOk() ? StatusCode.CORRUPTION : status;
      }
      recoveryPageIds[index] = pageId;
    }
    for (int index = 0; index < pageCount; index++) {
      int pageId = recoveryPageIds[index];
      int pageOffset = PAGE_OPERATION_HEADER_BYTES + index * PageCodec.PAGE_BYTES;
      copyFromRecord(payload, pageOffset, currentPages[pageId]);
      present[pageId] = true;
      dirty[pageId] = true;
      pageRecordStarts[pageId] = recordStart;
      pageRecordEnds[pageId] = recordEnd;
      highestPageId = Math.max(highestPageId, pageId);
    }
    StatusCode status = validateAppliedPages(pageCount);
    if (status.isOk()) {
      recordNewRowCommits(previousRowCount, commitSequence);
    }
    return status;
  }

  private StatusCode validateAppliedPages(int pageCount) {
    for (int index = 0; index < pageCount; index++) {
      int pageId = recoveryPageIds[index];
      StatusCode status;
      if (pageId == HEAP_PAGE_ID) {
        status = HeapPage.validate(currentPayloads[pageId]);
      } else if (pageId == ROOT_META_PAGE_ID) {
        status = BTreeRootPage.validate(currentPayloads[pageId]);
      } else {
        status = BTreePage.validate(currentPayloads[pageId]);
      }
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode applyInsertOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    if (payload.limit() < INSERT_OPERATION_HEADER_BYTES
        || getLong(payload, 0) != OPERATION_MAGIC
        || getInt(payload, 8) != WAL_FORMAT_VERSION
        || getInt(payload, 12) != OPERATION_TYPE_INSERT
        || getLong(payload, 32) != 0) {
      return StatusCode.CORRUPTION;
    }
    long key = getLong(payload, 16);
    int rowId = getInt(payload, 24);
    int rowBytes = getInt(payload, 28);
    if (key == Long.MAX_VALUE
        || rowId <= 0
        || rowBytes <= 0
        || payload.limit() != INSERT_OPERATION_HEADER_BYTES + rowBytes
        || !present[HEAP_PAGE_ID]) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer heap = currentPayloads[HEAP_PAGE_ID];
    int leafPageId = findLeafPageId(key);
    if (leafPageId <= 0
        || HeapPage.rowCount(heap) + 1 != rowId
        || !HeapPage.canInsert(heap, rowBytes)
        || BTreePage.entryCount(currentPayloads[leafPageId]) >= BTreePage.MAX_ENTRIES) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = BTreePage.lookupLeaf(currentPayloads[leafPageId], key, lookupResult);
    if (status != StatusCode.CONFLICT) {
      return StatusCode.CORRUPTION;
    }
    status = HeapPage.insertFrom(
        heap,
        payload,
        INSERT_OPERATION_HEADER_BYTES,
        rowBytes,
        appliedInsert);
    if (status.isOk()) {
      status = BTreePage.insertLeaf(currentPayloads[leafPageId], key, rowId);
    }
    if (!status.isOk() || appliedInsert.rowId() != rowId) {
      return StatusCode.INVARIANT_BROKEN;
    }
    pageRecordStarts[HEAP_PAGE_ID] = recordStart;
    pageRecordEnds[HEAP_PAGE_ID] = recordEnd;
    pageRecordStarts[leafPageId] = recordStart;
    pageRecordEnds[leafPageId] = recordEnd;
    rowCommitSequences[rowId] = commitSequence;
    previousRowIds[rowId] = 0;
    deletedRows[rowId] = false;
    dirty[HEAP_PAGE_ID] = true;
    dirty[leafPageId] = true;
    return StatusCode.OK;
  }

  private StatusCode applyInsertBatchOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    if (payload.limit() < INSERT_BATCH_HEADER_BYTES
        || getLong(payload, 0) != OPERATION_MAGIC
        || getInt(payload, 8) != WAL_FORMAT_VERSION
        || getInt(payload, 12) != OPERATION_TYPE_INSERT_BATCH
        || getInt(payload, 20) != 0
        || !present[HEAP_PAGE_ID]) {
      return StatusCode.CORRUPTION;
    }
    int insertCount = getInt(payload, 16);
    if (insertCount <= 1 || insertCount > MAX_ROWS) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer heap = currentPayloads[HEAP_PAGE_ID];
    int firstRowId = HeapPage.rowCount(heap) + 1;
    int requiredHeapBytes = 0;
    int entryOffset = INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      if (payload.limit() - entryOffset < INSERT_BATCH_ENTRY_BYTES) {
        return StatusCode.CORRUPTION;
      }
      long key = getLong(payload, entryOffset);
      int rowId = getInt(payload, entryOffset + 8);
      int rowBytes = getInt(payload, entryOffset + 12);
      if (key == Long.MAX_VALUE
          || rowId != firstRowId + index
          || rowBytes <= 0
          || payload.limit() - entryOffset - INSERT_BATCH_ENTRY_BYTES < rowBytes
          || batchContainsEarlierKey(payload, entryOffset, key)) {
        return StatusCode.CORRUPTION;
      }
      int leafPageId = findLeafPageId(key);
      if (leafPageId <= 0) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = BTreePage.lookupLeaf(
          currentPayloads[leafPageId], key, lookupResult);
      if (status != StatusCode.CONFLICT) {
        return StatusCode.CORRUPTION;
      }
      int earlierInLeaf = countEarlierBatchEntriesInLeaf(
          payload, entryOffset, leafPageId);
      if (BTreePage.entryCount(currentPayloads[leafPageId]) + earlierInLeaf
          >= BTreePage.MAX_ENTRIES) {
        return StatusCode.CORRUPTION;
      }
      requiredHeapBytes += HeapPage.SLOT_BYTES + rowBytes;
      entryOffset += INSERT_BATCH_ENTRY_BYTES + rowBytes;
    }
    if (entryOffset != payload.limit()
        || requiredHeapBytes > HeapPage.availableBytes(heap)) {
      return StatusCode.CORRUPTION;
    }
    entryOffset = INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      long key = getLong(payload, entryOffset);
      int rowId = getInt(payload, entryOffset + 8);
      int rowBytes = getInt(payload, entryOffset + 12);
      int rowOffset = entryOffset + INSERT_BATCH_ENTRY_BYTES;
      int leafPageId = findLeafPageId(key);
      StatusCode status = HeapPage.insertFrom(
          heap, payload, rowOffset, rowBytes, appliedInsert);
      if (status.isOk()) {
        status = BTreePage.insertLeaf(currentPayloads[leafPageId], key, rowId);
      }
      if (!status.isOk() || appliedInsert.rowId() != rowId) {
        return StatusCode.INVARIANT_BROKEN;
      }
      pageRecordStarts[leafPageId] = recordStart;
      pageRecordEnds[leafPageId] = recordEnd;
      dirty[leafPageId] = true;
      rowCommitSequences[rowId] = commitSequence;
      previousRowIds[rowId] = 0;
      deletedRows[rowId] = false;
      entryOffset = rowOffset + rowBytes;
    }
    pageRecordStarts[HEAP_PAGE_ID] = recordStart;
    pageRecordEnds[HEAP_PAGE_ID] = recordEnd;
    dirty[HEAP_PAGE_ID] = true;
    return StatusCode.OK;
  }

  private boolean batchContainsEarlierKey(
      ByteBuffer payload,
      int targetEntryOffset,
      long key) {
    int entryOffset = INSERT_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (getLong(payload, entryOffset) == key) {
        return true;
      }
      entryOffset += INSERT_BATCH_ENTRY_BYTES + getInt(payload, entryOffset + 12);
    }
    return false;
  }

  private StatusCode applyMutationBatchOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    if (payload.limit() < MUTATION_BATCH_HEADER_BYTES
        || getLong(payload, 0) != OPERATION_MAGIC
        || getInt(payload, 8) != WAL_FORMAT_VERSION
        || getInt(payload, 12) != OPERATION_TYPE_MUTATION_BATCH
        || getInt(payload, 20) != 0
        || !present[HEAP_PAGE_ID]) {
      return StatusCode.CORRUPTION;
    }
    int mutationCount = getInt(payload, 16);
    if (mutationCount <= 0 || mutationCount > MAX_ROWS) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer heap = currentPayloads[HEAP_PAGE_ID];
    int firstRowId = HeapPage.rowCount(heap) + 1;
    int requiredHeapBytes = 0;
    int entryOffset = MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      if (payload.limit() - entryOffset < MUTATION_BATCH_ENTRY_BYTES) {
        return StatusCode.CORRUPTION;
      }
      int operation = getInt(payload, entryOffset);
      long key = getLong(payload, entryOffset + 4);
      int rowId = getInt(payload, entryOffset + 12);
      int previousRowId = getInt(payload, entryOffset + 16);
      int rowBytes = getInt(payload, entryOffset + 20);
      if ((operation != MUTATION_INSERT
              && operation != MUTATION_UPDATE
              && operation != MUTATION_DELETE)
          || key == Long.MAX_VALUE
          || rowId != firstRowId + index
          || rowBytes <= 0
          || payload.limit() - entryOffset - MUTATION_BATCH_ENTRY_BYTES < rowBytes
          || mutationContainsEarlierKey(payload, entryOffset, key)) {
        return StatusCode.CORRUPTION;
      }
      int leafPageId = findLeafPageId(key);
      if (leafPageId <= 0) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = BTreePage.lookupLeaf(
          currentPayloads[leafPageId], key, lookupResult);
      if (operation == MUTATION_INSERT) {
        if (previousRowId == 0) {
          if (status != StatusCode.CONFLICT) {
            return StatusCode.CORRUPTION;
          }
          int earlierInLeaf = countEarlierMutationInsertsInLeaf(
              payload, entryOffset, leafPageId);
          if (BTreePage.entryCount(currentPayloads[leafPageId]) + earlierInLeaf
              >= BTreePage.MAX_ENTRIES) {
            return StatusCode.CORRUPTION;
          }
        } else if (!status.isOk()
            || lookupResult.rowId() != previousRowId
            || !isDeletedRow(previousRowId)) {
          return StatusCode.CORRUPTION;
        }
      } else if (!status.isOk()
          || lookupResult.rowId() != previousRowId
          || previousRowId <= 0
          || isDeletedRow(previousRowId)) {
        return StatusCode.CORRUPTION;
      }
      requiredHeapBytes += HeapPage.SLOT_BYTES + rowBytes;
      entryOffset += MUTATION_BATCH_ENTRY_BYTES + rowBytes;
    }
    if (entryOffset != payload.limit()
        || requiredHeapBytes > HeapPage.availableBytes(heap)) {
      return StatusCode.CORRUPTION;
    }
    entryOffset = MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      int operation = getInt(payload, entryOffset);
      long key = getLong(payload, entryOffset + 4);
      int rowId = getInt(payload, entryOffset + 12);
      int previousRowId = getInt(payload, entryOffset + 16);
      int rowBytes = getInt(payload, entryOffset + 20);
      int rowOffset = entryOffset + MUTATION_BATCH_ENTRY_BYTES;
      int leafPageId = findLeafPageId(key);
      StatusCode status = HeapPage.insertFrom(
          heap, payload, rowOffset, rowBytes, appliedInsert);
      if (status.isOk()) {
        status = operation == MUTATION_INSERT && previousRowId == 0
            ? BTreePage.insertLeaf(currentPayloads[leafPageId], key, rowId)
            : BTreePage.updateLeaf(currentPayloads[leafPageId], key, rowId);
      }
      if (!status.isOk() || appliedInsert.rowId() != rowId) {
        return StatusCode.INVARIANT_BROKEN;
      }
      pageRecordStarts[leafPageId] = recordStart;
      pageRecordEnds[leafPageId] = recordEnd;
      dirty[leafPageId] = true;
      rowCommitSequences[rowId] = commitSequence;
      previousRowIds[rowId] = previousRowId;
      deletedRows[rowId] = operation == MUTATION_DELETE;
      entryOffset = rowOffset + rowBytes;
    }
    pageRecordStarts[HEAP_PAGE_ID] = recordStart;
    pageRecordEnds[HEAP_PAGE_ID] = recordEnd;
    dirty[HEAP_PAGE_ID] = true;
    return StatusCode.OK;
  }

  private StatusCode applyVacuumOperation(
      ByteBuffer payload,
      long recordStart,
      long recordEnd,
      long commitSequence) {
    if (payload.limit() < VACUUM_HEADER_BYTES
        || getLong(payload, 0) != OPERATION_MAGIC
        || getInt(payload, 8) != WAL_FORMAT_VERSION
        || getInt(payload, 12) != OPERATION_TYPE_VACUUM
        || getInt(payload, 20) != 0
        || !present[HEAP_PAGE_ID]) {
      return StatusCode.CORRUPTION;
    }
    int retainedRows = getInt(payload, 16);
    if (retainedRows < 0
        || retainedRows > MAX_ROWS
        || indexedEntryCount() != retainedRows) {
      return StatusCode.CORRUPTION;
    }
    int requiredHeapBytes = 0;
    int entryOffset = VACUUM_HEADER_BYTES;
    for (int index = 0; index < retainedRows; index++) {
      if (payload.limit() - entryOffset < VACUUM_ENTRY_BYTES) {
        return StatusCode.CORRUPTION;
      }
      long key = getLong(payload, entryOffset);
      int oldRowId = getInt(payload, entryOffset + 8);
      int rowBytes = getInt(payload, entryOffset + 12);
      int deleted = getInt(payload, entryOffset + 16);
      if (key == Long.MAX_VALUE
          || oldRowId <= 0
          || rowBytes <= 0
          || (deleted != 0 && deleted != 1)
          || getInt(payload, entryOffset + 20) != 0
          || payload.limit() - entryOffset - VACUUM_ENTRY_BYTES < rowBytes
          || vacuumContainsEarlierKey(payload, entryOffset, key)
          || HeapPage.rowLength(currentPayloads[HEAP_PAGE_ID], oldRowId) != rowBytes
          || isDeletedRow(oldRowId) != (deleted == 1)) {
        return StatusCode.CORRUPTION;
      }
      int leafPageId = findLeafPageId(key);
      StatusCode status = leafPageId <= 0
          ? StatusCode.CORRUPTION
          : BTreePage.lookupLeaf(currentPayloads[leafPageId], key, lookupResult);
      if (!status.isOk() || lookupResult.rowId() != oldRowId) {
        return StatusCode.CORRUPTION;
      }
      requiredHeapBytes += HeapPage.SLOT_BYTES + rowBytes;
      entryOffset += VACUUM_ENTRY_BYTES + rowBytes;
    }
    if (entryOffset != payload.limit()
        || requiredHeapBytes > PageCodec.MAX_PAYLOAD_BYTES - HeapPage.HEADER_BYTES) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = beginOperation();
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer heap = stageExisting(HEAP_PAGE_ID);
    if (heap == null) {
      cancelOperation();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = HeapPage.initialize(heap);
    entryOffset = VACUUM_HEADER_BYTES;
    for (int index = 0; status.isOk() && index < retainedRows; index++) {
      long key = getLong(payload, entryOffset);
      int rowBytes = getInt(payload, entryOffset + 12);
      int leafPageId = findLeafPageId(key);
      ByteBuffer leaf = stageExisting(leafPageId);
      if (leaf == null) {
        status = StatusCode.RESOURCE_EXHAUSTED;
        break;
      }
      status = HeapPage.insertFrom(
          heap,
          payload,
          entryOffset + VACUUM_ENTRY_BYTES,
          rowBytes,
          appliedInsert);
      if (status.isOk()) {
        status = BTreePage.updateLeaf(leaf, key, appliedInsert.rowId());
      }
      entryOffset += VACUUM_ENTRY_BYTES + rowBytes;
    }
    if (!status.isOk()) {
      cancelOperation();
      return status;
    }
    publishStagedPages(recordStart, recordEnd);
    for (int rowId = 1; rowId <= MAX_ROWS; rowId++) {
      rowCommitSequences[rowId] = 0;
      previousRowIds[rowId] = 0;
      deletedRows[rowId] = false;
    }
    entryOffset = VACUUM_HEADER_BYTES;
    for (int rowId = 1; rowId <= retainedRows; rowId++) {
      int rowBytes = getInt(payload, entryOffset + 12);
      rowCommitSequences[rowId] = commitSequence;
      deletedRows[rowId] = getInt(payload, entryOffset + 16) == 1;
      entryOffset += VACUUM_ENTRY_BYTES + rowBytes;
    }
    operationActive = false;
    changedPageCount = 0;
    return StatusCode.OK;
  }

  private boolean vacuumContainsEarlierKey(
      ByteBuffer payload,
      int targetEntryOffset,
      long key) {
    int entryOffset = VACUUM_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (getLong(payload, entryOffset) == key) {
        return true;
      }
      entryOffset += VACUUM_ENTRY_BYTES + getInt(payload, entryOffset + 12);
    }
    return false;
  }

  private int indexedEntryCount() {
    int count = 0;
    for (int pageId = 1; pageId <= highestPageId; pageId++) {
      if (!present[pageId] || pageId == ROOT_META_PAGE_ID) {
        continue;
      }
      ByteBuffer page = currentPayloads[pageId];
      if (pageId == HEAP_PAGE_ID) {
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

  private boolean mutationContainsEarlierKey(
      ByteBuffer payload,
      int targetEntryOffset,
      long key) {
    int entryOffset = MUTATION_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (getLong(payload, entryOffset + 4) == key) {
        return true;
      }
      entryOffset += MUTATION_BATCH_ENTRY_BYTES + getInt(payload, entryOffset + 20);
    }
    return false;
  }

  private int countEarlierMutationInsertsInLeaf(
      ByteBuffer payload,
      int targetEntryOffset,
      int leafPageId) {
    int count = 0;
    int entryOffset = MUTATION_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (getInt(payload, entryOffset) == MUTATION_INSERT
          && getInt(payload, entryOffset + 16) == 0
          && findLeafPageId(getLong(payload, entryOffset + 4)) == leafPageId) {
        count++;
      }
      entryOffset += MUTATION_BATCH_ENTRY_BYTES + getInt(payload, entryOffset + 20);
    }
    return count;
  }

  private int countEarlierBatchEntriesInLeaf(
      ByteBuffer payload,
      int targetEntryOffset,
      int leafPageId) {
    int count = 0;
    int entryOffset = INSERT_BATCH_HEADER_BYTES;
    while (entryOffset < targetEntryOffset) {
      if (findLeafPageId(getLong(payload, entryOffset)) == leafPageId) {
        count++;
      }
      entryOffset += INSERT_BATCH_ENTRY_BYTES + getInt(payload, entryOffset + 12);
    }
    return count;
  }

  private void recordNewRowCommits(int previousRowCount, long commitSequence) {
    if (!present[HEAP_PAGE_ID]) {
      return;
    }
    int currentRowCount = HeapPage.rowCount(currentPayloads[HEAP_PAGE_ID]);
    for (int rowId = previousRowCount + 1; rowId <= currentRowCount; rowId++) {
      rowCommitSequences[rowId] = commitSequence;
      previousRowIds[rowId] = 0;
      deletedRows[rowId] = false;
    }
  }

  private StatusCode encodeCurrentPage(int pageId, long recordStart, long recordEnd) {
    return PageCodec.encode(
        database,
        walGeneration,
        pageId,
        1,
        recordStart,
        recordEnd,
        PageCodec.MAX_PAYLOAD_BYTES,
        currentPages[pageId],
        checksum);
  }

  private int findLeafPageId(long key) {
    if (!present[ROOT_META_PAGE_ID]) {
      return 0;
    }
    int rootPageId = BTreeRootPage.rootPageId(currentPayloads[ROOT_META_PAGE_ID]);
    if (!validPresentPage(rootPageId)) {
      return 0;
    }
    ByteBuffer root = currentPayloads[rootPageId];
    if (BTreePage.type(root) == BTreePage.TYPE_LEAF) {
      return rootPageId;
    }
    int leafPageId = BTreePage.childForKey(root, key);
    return validPresentPage(leafPageId) ? leafPageId : 0;
  }

  private boolean addChangedPage(int pageId) {
    if (changedPageCount >= MAX_CHANGED_PAGES) {
      return false;
    }
    changedPageIds[changedPageCount++] = pageId;
    staged[pageId] = true;
    return true;
  }

  private void publishStagedPages() {
    publishStagedPages(walAppendResult.startOffset(), walAppendResult.endOffset());
  }

  private void publishStagedPages(long recordStart, long recordEnd) {
    for (int index = 0; index < changedPageCount; index++) {
      int pageId = changedPageIds[index];
      ByteBuffer page = currentPages[pageId];
      currentPages[pageId] = stagingPages[pageId];
      stagingPages[pageId] = page;
      ByteBuffer payload = currentPayloads[pageId];
      currentPayloads[pageId] = stagingPayloads[pageId];
      stagingPayloads[pageId] = payload;
      present[pageId] = true;
      dirty[pageId] = true;
      pageRecordStarts[pageId] = recordStart;
      pageRecordEnds[pageId] = recordEnd;
      staged[pageId] = false;
      highestPageId = Math.max(highestPageId, pageId);
    }
  }

  private void clearStagedFlags() {
    for (int index = 0; index < changedPageCount; index++) {
      staged[changedPageIds[index]] = false;
    }
  }

  private boolean duplicateRecoveryPage(int pageId, int count) {
    for (int index = 0; index < count; index++) {
      if (recoveryPageIds[index] == pageId) {
        return true;
      }
    }
    return false;
  }

  private boolean validPresentPage(int pageId) {
    return pageId > 0 && pageId <= MAX_PAGES && present[pageId];
  }

  private boolean hasDirtyPages() {
    for (int pageId = 1; pageId <= highestPageId; pageId++) {
      if (dirty[pageId]) {
        return true;
      }
    }
    return false;
  }

  private StatusCode admission() {
    if (failed) {
      return StatusCode.FENCED;
    }
    return closed ? StatusCode.CLOSED : StatusCode.OK;
  }

  private static boolean validInput(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      IndexedPageStoreOpenResult result) {
    return directory != null
        && wal != null
        && database != null
        && database.isValid()
        && walGeneration != null
        && walGeneration.isValid()
        && result != null
        && database.equals(wal.databaseIncarnation())
        && walGeneration.equals(wal.walGeneration());
  }

  private static ByteBuffer payloadView(ByteBuffer page) {
    page.clear();
    page.position(PageCodec.HEADER_BYTES);
    page.limit(PageCodec.PAGE_BYTES);
    return page.slice();
  }

  private static void copyPage(ByteBuffer source, ByteBuffer target) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(index, source.get(index));
    }
    target.position(0);
    target.limit(PageCodec.PAGE_BYTES);
  }

  private static void copyToRecord(ByteBuffer source, ByteBuffer target, int offset) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(offset + index, source.get(index));
    }
  }

  private static void copyFromRecord(ByteBuffer source, int offset, ByteBuffer target) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(index, source.get(offset + index));
    }
    target.position(0);
    target.limit(PageCodec.PAGE_BYTES);
  }

  private static void putInt(ByteBuffer target, int offset, int value) {
    target.put(offset, (byte) value);
    target.put(offset + 1, (byte) (value >>> 8));
    target.put(offset + 2, (byte) (value >>> 16));
    target.put(offset + 3, (byte) (value >>> 24));
  }

  private static int getInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset))
        | Byte.toUnsignedInt(source.get(offset + 1)) << 8
        | Byte.toUnsignedInt(source.get(offset + 2)) << 16
        | Byte.toUnsignedInt(source.get(offset + 3)) << 24;
  }

  private static void putLong(ByteBuffer target, int offset, long value) {
    putInt(target, offset, (int) value);
    putInt(target, offset + 4, (int) (value >>> 32));
  }

  private static long getLong(ByteBuffer source, int offset) {
    return Integer.toUnsignedLong(getInt(source, offset))
        | Integer.toUnsignedLong(getInt(source, offset + 4)) << 32;
  }
}
