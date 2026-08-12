package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.storage.btree.BTreeLookupResult;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalForceResult;
import io.riverdb.wal.local.LocalWalReadResult;
import io.riverdb.wal.local.LocalWalReservation;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Single-owner bounded page store whose WAL operations atomically cover heap and index state. */
public final class IndexedTableStore {
  public static final String FILE_NAME = "river.indexed.pages";
  public static final int WAL_FORMAT_ID = IndexedWalCodec.FORMAT_ID;
  public static final int WAL_FORMAT_VERSION = IndexedWalCodec.FORMAT_VERSION;
  public static final int MAX_PAGES = 512;
  public static final int MAX_CHANGED_PAGES = 63;
  public static final int MAX_ROWS = CheckpointState.MAXIMUM_ROWS;
  public static final int VACUUM_COMMIT_PAYLOAD_BYTES =
      IndexedWalCodec.VACUUM_COMMIT_PAYLOAD_BYTES;

  private static final int HEAP_PAGE_ID = IndexedTableKernel.HEAP_PAGE_ID;
  private static final int ROOT_META_PAGE_ID = IndexedTableKernel.ROOT_META_PAGE_ID;
  private static final int MAX_PREPARED_INSERT_ROWS = LocalWal.MAX_PENDING_RECORDS * 64;
  static final int MAX_OPERATION_ROWS = MAX_PREPARED_INSERT_ROWS;

  private final DurableDirectory directory;
  private final DurableFile file;
  private final LocalWal wal;
  private final DatabaseIncarnation database;
  private final IndexedTableKernel kernel;
  private WalGeneration walGeneration;
  private final IndexedPageSet pages = new IndexedPageSet();
  private final int[] recoveryPageIds = new int[MAX_CHANGED_PAGES];
  private final long[] preparedKeys = new long[MAX_PREPARED_INSERT_ROWS];
  private final int[] preparedLeafPageIds = new int[MAX_PREPARED_INSERT_ROWS];
  private final boolean[] preparedNewIndexEntries = new boolean[MAX_PREPARED_INSERT_ROWS];
  private final long[] preparedRecordStarts = new long[LocalWal.MAX_PENDING_RECORDS];
  private final long[] preparedCommitSequences = new long[LocalWal.MAX_PENDING_RECORDS];
  private final long[] preparedTransactionIds = new long[LocalWal.MAX_PENDING_RECORDS];
  private final long[] vacuumRecordStarts = new long[LocalWal.MAX_PENDING_RECORDS];
  private final CRC32C checksum = new CRC32C();
  private final IoResult ioResult = new IoResult();
  private final FileSizeResult fileSizeResult = new FileSizeResult();
  private final PageHeader pageHeader = new PageHeader();
  private final LocalWalReservation walReservation = new LocalWalReservation();
  private final LocalWalAppendResult walAppendResult = new LocalWalAppendResult();
  private final LocalWalForceResult walForceResult = new LocalWalForceResult();
  private final LocalWalReadResult walReadResult = new LocalWalReadResult();
  private int preparedKeyCount;
  private int preparedRecordCount;
  private int preparedRowCount;
  private int preparedHeapBytes;
  private int vacuumExpectedRows;
  private int vacuumAppliedRows;
  private int vacuumExpectedChunks;
  private int vacuumAppliedChunks;
  private final IndexedStorePhase phase = new IndexedStorePhase();
  private boolean failed;
  private boolean closed;
  private boolean baseLoaded;
  private long walCopyBytes;
  private long vacuumTransactionId;
  private long vacuumRecordStart;
  private volatile long lastCommitSequence;

  private IndexedTableStore(
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
    kernel = new IndexedTableKernel(this);
  }

  IndexedTableKernel kernel() {
    return kernel;
  }

  IndexedPageSet pages() {
    return pages;
  }

  public static StatusCode create(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      IndexedTableStoreOpenResult result) {
    if (!validInput(directory, wal, database, walGeneration, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.createFile(FILE_NAME, operation);
    if (!status.isOk()) {
      return status;
    }
    result.set(new IndexedTableStore(
        directory, operation.file(), wal, database, walGeneration));
    return StatusCode.OK;
  }

  public static StatusCode open(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      IndexedTableStoreOpenResult result) {
    return open(directory, wal, database, walGeneration, true, result);
  }

  public static StatusCode openExisting(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      IndexedTableStoreOpenResult result) {
    return open(directory, wal, database, walGeneration, false, result);
  }

  public static StatusCode openCheckpoint(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      CheckpointState checkpoint,
      IndexedTableStoreOpenResult result) {
    if (checkpoint == null
        || !checkpoint.isAvailable()
        || !checkpoint.database().equals(database)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    WalGeneration generation = checkpoint.walGeneration();
    if (!validInput(directory, wal, database, generation, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.reopen(FILE_NAME, operation);
    if (!status.isOk()) {
      return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    }
    IndexedTableStore store = new IndexedTableStore(
        directory, operation.file(), wal, database, generation);
    status = store.loadCheckpoint(checkpoint);
    if (status.isOk()) {
      status = store.recoverFromWal();
    }
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

  public static String checkpointFileName(WalGeneration generation) {
    return generation == null || !generation.isValid()
        ? "" : FILE_NAME + ".checkpoint." + generation.value();
  }

  private static StatusCode open(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      boolean createWhenMissing,
      IndexedTableStoreOpenResult result) {
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
    IndexedTableStore store = new IndexedTableStore(
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

  StatusCode beginOperation() {
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (phase.operationActive() || phase.preparedInsertGroupActive()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    pages.resetChanges();
    kernel.beginOperationState();
    return phase.beginStaged() ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  StatusCode fetchRow(int rowId, io.riverdb.storage.heap.HeapRowResult result) {
    return kernel.fetchRow(rowId, result);
  }

  int rowLength(int rowId) {
    return kernel.rowLength(rowId);
  }

  StatusCode copyRowTo(int rowId, ByteBuffer destination, int destinationOffset) {
    return kernel.copyRowTo(rowId, destination, destinationOffset);
  }

  int rowCount() {
    return kernel.rowCount();
  }

  /** Returns the number of superseded heap versions in constant time. */
  int obsoleteVersionCount() {
    return kernel.obsoleteVersionCount();
  }

  int remainingVersionCapacity() {
    return MAX_ROWS - kernel.rowCount();
  }

  StatusCode commit(long transactionId, long commitSequence) {
    if (!phase.operationActive()
        || transactionId <= 0
        || commitSequence <= lastCommitSequence
        || pages.changedPageCount() <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int operationBytes = IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES
        + pages.changedPageCount() * PageCodec.PAGE_BYTES
        + kernel.operationVersionCount() * IndexedWalCodec.PAGE_OPERATION_VERSION_BYTES;
    StatusCode status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    IndexedWalCodec.encodePageOperationHeader(
        recordPayload, pages.changedPageCount(), kernel.operationVersionCount());
    int outputOffset = IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES;
    for (int index = 0; index < pages.changedPageCount(); index++) {
      int pageId = pages.changedPageId(index);
      status = pages.encodeStaged(
          pageId,
          database,
          walGeneration,
          walReservation.recordStartOffset(),
          walReservation.recordEndOffset(),
          checksum);
      if (!status.isOk()) {
        wal.cancel(walReservation);
        return status;
      }
      pages.copyStagedToRecord(pageId, recordPayload, outputOffset);
      walCopyBytes += PageCodec.PAGE_BYTES;
      outputOffset += PageCodec.PAGE_BYTES;
    }
    for (int index = 0; index < kernel.operationVersionCount(); index++) {
      IndexedWalCodec.encodePageOperationVersion(
          recordPayload,
          outputOffset,
          kernel.operationPreviousRowId(index),
          kernel.operationDeleted(index));
      outputOffset += IndexedWalCodec.PAGE_OPERATION_VERSION_BYTES;
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
    int previousRowCount = kernel.rowCount();
    publishStagedPages();
    StatusCode locations = kernel.rebuildRowLocations();
    if (!locations.isOk()
        || kernel.rowCount() != kernel.operationRowCount()
        || kernel.rowCount() - previousRowCount != kernel.operationVersionCount()) {
      failed = true;
      return locations.isOk() ? StatusCode.INVARIANT_BROKEN : locations;
    }
    kernel.recordOperationVersions(previousRowCount, commitSequence);
    lastCommitSequence = commitSequence;
    phase.reset();
    pages.resetChanges();
    kernel.clearOperationVersions();
    return StatusCode.OK;
  }

  /** Commits the common no-split heap/index insert as a compact logical operation. */
  StatusCode commitInsert(
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
    if (phase.operationActive() || phase.preparedInsertGroupActive()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int rowBytes = row.remaining();
    status = kernel.validateNewIndexEntry(key, 0);
    if (!status.isOk()) {
      return status;
    }
    if (!kernel.canAppendRow(rowBytes)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int rowId = kernel.rowCount() + 1;
    int operationBytes = IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES + rowBytes;
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    IndexedWalCodec.encodeInsertHeader(recordPayload, key, rowId, rowBytes);
    int sourceStart = row.position();
    for (int index = 0; index < rowBytes; index++) {
      recordPayload.put(IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES + index, row.get(sourceStart + index));
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
    status = kernel.applyInsertOperation(
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
  StatusCode commitInsertBatch(
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
    if (phase.operationActive() || phase.preparedInsertGroupActive()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
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
      int leafPageId = kernel.findLeafPageId(key);
      int earlierInLeaf = 0;
      for (int previous = 0; previous < index; previous++) {
        if (kernel.findLeafPageId(keys[previous]) == leafPageId) {
          earlierInLeaf++;
        }
      }
      status = kernel.validateNewIndexEntryAt(leafPageId, key, earlierInLeaf);
      if (!status.isOk()) {
        return status;
      }
      operationBytes += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES + rowBytes;
    }
    if (!canAppendRows(rowLengths, insertCount)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    IndexedWalCodec.encodeInsertBatchHeader(recordPayload, insertCount);
    int outputOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    int firstRowId = kernel.rowCount() + 1;
    for (int index = 0; index < insertCount; index++) {
      int rowBytes = rowLengths[index];
      IndexedWalCodec.encodeInsertBatchEntry(
          recordPayload, outputOffset, keys[index], firstRowId + index, rowBytes);
      int sourceOffset = index * rowStride;
      int rowOffset = outputOffset + IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES;
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
    status = kernel.applyInsertBatchOperation(
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

  /** Starts bounded validation for a group of independent insert-only transactions. */
  StatusCode beginPreparedInsertGroup() {
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (phase.operationActive() || phase.preparedInsertGroupActive()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    preparedKeyCount = 0;
    preparedRecordCount = 0;
    preparedRowCount = 0;
    preparedHeapBytes = 0;
    return phase.beginPreparedPreflight() ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  /** Adds one transaction's insert set to cumulative capacity and uniqueness validation. */
  StatusCode preflightPreparedInsertBatch(
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount) {
    if (!phase.preparedInsertGroupActive()
        || phase.preparedInsertEncoding()
        || keys == null
        || rows == null
        || rowStride <= 0
        || rowLengths == null
        || insertCount <= 0
        || insertCount > keys.length
        || insertCount > rowLengths.length
        || preparedKeyCount + insertCount > preparedKeys.length
        || kernel.rowCount() + preparedKeyCount + insertCount > MAX_ROWS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int originalKeyCount = preparedKeyCount;
    int originalHeapBytes = preparedHeapBytes;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < insertCount; index++) {
      long key = keys[index];
      int rowBytes = rowLengths[index];
      int rowOffset = index * rowStride;
      if (key == Long.MAX_VALUE
          || rowBytes <= 0
          || rowBytes > rowStride
          || rows.limit() - rowOffset < rowBytes) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
        break;
      }
      for (int previous = 0; previous < preparedKeyCount; previous++) {
        if (preparedKeys[previous] == key) {
          status = StatusCode.CONFLICT;
          break;
        }
      }
      int leafPageId = status.isOk() ? kernel.findLeafPageId(key) : 0;
      int entriesInLeaf = 0;
      for (int previous = 0; status.isOk() && previous < preparedKeyCount; previous++) {
        if (preparedLeafPageIds[previous] == leafPageId) {
          entriesInLeaf++;
        }
      }
      if (status.isOk()) {
        status = kernel.validateNewIndexEntryAt(leafPageId, key, entriesInLeaf);
        leafPageId = kernel.validatedLeafPageId();
      }
      int required = HeapPage.SLOT_BYTES + rowBytes;
      if (status.isOk()
          && preparedHeapBytes + required
              > kernel.currentHeapAvailableBytes()) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      if (status.isOk()) {
        preparedKeys[preparedKeyCount] = key;
        preparedLeafPageIds[preparedKeyCount] = leafPageId;
        preparedNewIndexEntries[preparedKeyCount] = true;
        preparedKeyCount++;
        preparedHeapBytes += required;
      }
    }
    if (!status.isOk()) {
      for (int index = originalKeyCount; index < preparedKeyCount; index++) {
        preparedKeys[index] = 0;
        preparedLeafPageIds[index] = 0;
        preparedNewIndexEntries[index] = false;
      }
      preparedKeyCount = originalKeyCount;
      preparedHeapBytes = originalHeapBytes;
    }
    return status;
  }

  /** Adds one transaction's mixed write set to cumulative group validation. */
  StatusCode preflightPreparedMutationBatch(
      int[] operations,
      long[] keys,
      int[] expectedPreviousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount) {
    if (!phase.preparedInsertGroupActive()
        || phase.preparedInsertEncoding()
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
        || preparedKeyCount + mutationCount > preparedKeys.length
        || kernel.rowCount() + preparedKeyCount + mutationCount > MAX_ROWS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int originalKeyCount = preparedKeyCount;
    int originalHeapBytes = preparedHeapBytes;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < mutationCount; index++) {
      int operation = operations[index];
      long key = keys[index];
      int previousRowId = expectedPreviousRowIds[index];
      int rowBytes = rowLengths[index];
      int rowOffset = index * rowStride;
      if ((operation != IndexedWalCodec.MUTATION_INSERT
              && operation != IndexedWalCodec.MUTATION_UPDATE
              && operation != IndexedWalCodec.MUTATION_DELETE)
          || key == Long.MAX_VALUE
          || rowBytes <= 0
          || rowBytes > rowStride
          || rows.limit() - rowOffset < rowBytes) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
        break;
      }
      for (int previous = 0; previous < preparedKeyCount; previous++) {
        if (preparedKeys[previous] == key) {
          status = StatusCode.CONFLICT;
          break;
        }
      }
      int leafPageId = status.isOk() ? kernel.findLeafPageId(key) : 0;
      boolean newIndexEntry = operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0;
      int entriesInLeaf = 0;
      for (int previous = 0; status.isOk() && previous < preparedKeyCount; previous++) {
        if (preparedNewIndexEntries[previous]
            && preparedLeafPageIds[previous] == leafPageId) {
          entriesInLeaf++;
        }
      }
      if (status.isOk()) {
        status = kernel.validateMutationTargetAt(
            leafPageId, operation, key, previousRowId, entriesInLeaf);
        leafPageId = kernel.validatedLeafPageId();
      }
      int required = HeapPage.SLOT_BYTES + rowBytes;
      if (status.isOk()
          && preparedHeapBytes + required
              > kernel.currentHeapAvailableBytes()) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      if (status.isOk()) {
        preparedKeys[preparedKeyCount] = key;
        preparedLeafPageIds[preparedKeyCount] = leafPageId;
        preparedNewIndexEntries[preparedKeyCount] = newIndexEntry;
        preparedKeyCount++;
        preparedHeapBytes += required;
      }
    }
    if (!status.isOk()) {
      for (int index = originalKeyCount; index < preparedKeyCount; index++) {
        preparedKeys[index] = 0;
        preparedLeafPageIds[index] = 0;
        preparedNewIndexEntries[index] = false;
      }
      preparedKeyCount = originalKeyCount;
      preparedHeapBytes = originalHeapBytes;
    }
    return status;
  }

  StatusCode finishPreparedInsertPreflight(int transactionCount) {
    if (!phase.preparedInsertGroupActive()
        || phase.preparedInsertEncoding()
        || transactionCount <= 0
        || transactionCount > LocalWal.MAX_PENDING_RECORDS
        || preparedKeyCount <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return phase.beginPreparedEncoding() ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  /** Encodes and appends one preflighted transaction without forcing or publishing pages. */
  StatusCode appendPreparedInsertBatch(
      long transactionId,
      long commitSequence,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      HeapInsertResult result) {
    if (!phase.preparedInsertGroupActive()
        || !phase.preparedInsertEncoding()
        || preparedRecordCount >= LocalWal.MAX_PENDING_RECORDS
        || transactionId <= 0
        || commitSequence != wal.nextCommitSequence()
        || keys == null
        || rows == null
        || rowLengths == null
        || insertCount <= 0
        || insertCount > keys.length
        || insertCount > rowLengths.length
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int operationBytes = insertCount == 1
        ? IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES + rowLengths[0]
        : IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    if (insertCount > 1) {
      for (int index = 0; index < insertCount; index++) {
        operationBytes += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES + rowLengths[index];
      }
    }
    StatusCode status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    ByteBuffer payload = walReservation.writablePayload();
    int firstRowId = kernel.rowCount() + preparedRowCount + 1;
    if (insertCount == 1) {
      IndexedWalCodec.encodeInsertHeader(payload, keys[0], firstRowId, rowLengths[0]);
      copyPreparedRow(rows, 0, payload, IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES, rowLengths[0]);
    } else {
      IndexedWalCodec.encodeInsertBatchHeader(payload, insertCount);
      int outputOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
      for (int index = 0; index < insertCount; index++) {
        int rowBytes = rowLengths[index];
        IndexedWalCodec.encodeInsertBatchEntry(
            payload, outputOffset, keys[index], firstRowId + index, rowBytes);
        copyPreparedRow(
            rows,
            index * rowStride,
            payload,
            outputOffset + IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES,
            rowBytes);
        outputOffset += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES + rowBytes;
      }
    }
    payload.position(operationBytes);
    status = wal.appendUnforced(
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
    preparedRecordStarts[preparedRecordCount] = walAppendResult.startOffset();
    preparedCommitSequences[preparedRecordCount] = commitSequence;
    preparedTransactionIds[preparedRecordCount] = transactionId;
    preparedRecordCount++;
    preparedRowCount += insertCount;
    result.setRowId(firstRowId + insertCount - 1);
    return StatusCode.OK;
  }

  /** Encodes and appends one preflighted mixed write set without forcing or publishing. */
  StatusCode appendPreparedMutationBatch(
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
    if (!phase.preparedInsertGroupActive()
        || !phase.preparedInsertEncoding()
        || preparedRecordCount >= LocalWal.MAX_PENDING_RECORDS
        || transactionId <= 0
        || commitSequence != wal.nextCommitSequence()
        || operations == null
        || keys == null
        || expectedPreviousRowIds == null
        || rows == null
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
    int operationBytes = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      operationBytes += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES + rowLengths[index];
    }
    StatusCode status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    ByteBuffer payload = walReservation.writablePayload();
    IndexedWalCodec.encodeMutationBatchHeader(payload, mutationCount);
    int outputOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    int firstRowId = kernel.rowCount() + preparedRowCount + 1;
    for (int index = 0; index < mutationCount; index++) {
      int rowBytes = rowLengths[index];
      IndexedWalCodec.encodeMutationBatchEntry(
          payload,
          outputOffset,
          operations[index],
          keys[index],
          firstRowId + index,
          expectedPreviousRowIds[index],
          rowBytes);
      copyPreparedRow(
          rows,
          index * rowStride,
          payload,
          outputOffset + IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES,
          rowBytes);
      outputOffset += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES + rowBytes;
    }
    payload.position(operationBytes);
    status = wal.appendUnforced(
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
    preparedRecordStarts[preparedRecordCount] = walAppendResult.startOffset();
    preparedCommitSequences[preparedRecordCount] = commitSequence;
    preparedTransactionIds[preparedRecordCount] = transactionId;
    preparedRecordCount++;
    preparedRowCount += mutationCount;
    result.setRowId(firstRowId + mutationCount - 1);
    return StatusCode.OK;
  }

  /** Forces every prepared insert transaction without publishing any page or index state. */
  StatusCode forcePreparedInserts() {
    if (!phase.preparedInsertGroupActive()
        || !phase.preparedInsertEncoding()
        || phase.preparedInsertForced()
        || preparedRecordCount <= 0
        || preparedRowCount != preparedKeyCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = wal.forcePending(walForceResult);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    return phase.markPreparedForced() ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  /** Publishes an already-forced insert group in commit order. */
  StatusCode publishForcedInserts() {
    if (!phase.preparedInsertGroupActive()
        || !phase.preparedInsertEncoding()
        || !phase.preparedInsertForced()
        || preparedRecordCount <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < preparedRecordCount; index++) {
      status = wal.readForcedRecord(index, walReadResult);
      if (status.isOk()
          && (walReadResult.header().transactionId() != preparedTransactionIds[index]
              || walReadResult.header().commitSequence()
                  != preparedCommitSequences[index])) {
        status = StatusCode.CORRUPTION;
      }
      if (status.isOk()) {
        status = applyOperation(
            preparedRecordStarts[index],
            walReadResult,
            preparedCommitSequences[index]);
      }
      if (status.isOk()) {
        lastCommitSequence = preparedCommitSequences[index];
      }
    }
    StatusCode release = wal.releaseForcedBatch();
    if (status.isOk()) {
      status = release;
    }
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    clearPreparedInsertGroup();
    return StatusCode.OK;
  }

  StatusCode cancelPreparedInsertPreflight() {
    if (!phase.preparedInsertGroupActive() || preparedRecordCount != 0) {
      return StatusCode.CONFLICT;
    }
    clearPreparedInsertGroup();
    return StatusCode.OK;
  }

  /** Commits a compact atomic mix of inserts, updates, and tombstone deletes. */
  StatusCode commitMutationBatch(
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
    if (phase.operationActive() || phase.preparedInsertGroupActive() || !pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      int operation = operations[index];
      long key = keys[index];
      int previousRowId = expectedPreviousRowIds[index];
      int rowBytes = rowLengths[index];
      int rowOffset = index * rowStride;
      if ((operation != IndexedWalCodec.MUTATION_INSERT
              && operation != IndexedWalCodec.MUTATION_UPDATE
              && operation != IndexedWalCodec.MUTATION_DELETE)
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
      int leafPageId = kernel.findLeafPageId(key);
      int earlierInLeaf = 0;
      if (operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0) {
        for (int previous = 0; previous < index; previous++) {
          if (operations[previous] == IndexedWalCodec.MUTATION_INSERT
              && expectedPreviousRowIds[previous] == 0
              && kernel.findLeafPageId(keys[previous]) == leafPageId) {
            earlierInLeaf++;
          }
        }
      }
      status = kernel.validateMutationTargetAt(
          leafPageId, operation, key, previousRowId, earlierInLeaf);
      if (!status.isOk()) {
        return status;
      }
      operationBytes += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES + rowBytes;
    }
    if (!canAppendRows(rowLengths, mutationCount)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    IndexedWalCodec.encodeMutationBatchHeader(recordPayload, mutationCount);
    int outputOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    int firstRowId = kernel.rowCount() + 1;
    for (int index = 0; index < mutationCount; index++) {
      int rowBytes = rowLengths[index];
      IndexedWalCodec.encodeMutationBatchEntry(
          recordPayload,
          outputOffset,
          operations[index],
          keys[index],
          firstRowId + index,
          expectedPreviousRowIds[index],
          rowBytes);
      int sourceOffset = index * rowStride;
      int rowOffset = outputOffset + IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES;
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
    status = kernel.applyMutationBatchOperation(
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

  /** Rewrites retained heads as one forced, multi-record WAL-atomic compaction batch. */
  StatusCode commitVacuum(
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
    if (phase.operationActive() || phase.preparedInsertGroupActive() || !pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = vacuumPreflight();
    if (!status.isOk()) {
      return status;
    }
    int rowsBefore = kernel.rowCount();
    int retainedRows = kernel.indexedEntryCount();
    if (retainedRows < 0 || retainedRows > rowsBefore) {
      return StatusCode.CORRUPTION;
    }
    if (retainedRows == rowsBefore) {
      return StatusCode.CONFLICT;
    }
    int chunkCount = kernel.vacuumChunkCount();
    if (chunkCount <= 0 || chunkCount >= LocalWal.MAX_PENDING_RECORDS) {
      return chunkCount < 0 ? StatusCode.CORRUPTION : StatusCode.RESOURCE_EXHAUSTED;
    }
    int firstRow = 0;
    boolean forced = false;
    for (int chunk = 0; status.isOk() && chunk < chunkCount; chunk++) {
      int chunkRows = vacuumChunkRowCount(firstRow);
      int chunkBytes = vacuumChunkPayloadBytes(firstRow, chunkRows);
      if (chunkRows <= 0 || chunkBytes <= IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES) {
        status = StatusCode.CORRUPTION;
        break;
      }
      status = wal.reserve(chunkBytes, walReservation);
      if (status.isOk()) {
        status = encodeVacuumChunk(
            walReservation.writablePayload(),
            retainedRows,
            firstRow,
            chunkRows,
            chunk,
            chunkCount,
            chunkBytes);
        if (!status.isOk()) {
          wal.cancel(walReservation);
        }
      }
      if (status.isOk()) {
        status = wal.appendUnforced(
            walReservation,
            transactionId,
            0,
            0,
            WAL_FORMAT_ID,
            WAL_FORMAT_VERSION,
            walAppendResult);
      }
      if (status.isOk()) {
        vacuumRecordStarts[chunk] = walAppendResult.startOffset();
        firstRow += chunkRows;
      }
    }
    if (status.isOk() && firstRow != retainedRows) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      status = wal.reserve(VACUUM_COMMIT_PAYLOAD_BYTES, walReservation);
    }
    if (status.isOk()) {
      ByteBuffer payload = walReservation.writablePayload();
      IndexedWalCodec.encodeVacuumCommit(payload, retainedRows, chunkCount, rowsBefore);
      payload.position(VACUUM_COMMIT_PAYLOAD_BYTES);
      status = wal.appendUnforced(
          walReservation,
          transactionId,
          commitSequence,
          1,
          WAL_FORMAT_ID,
          WAL_FORMAT_VERSION,
          walAppendResult);
      if (status.isOk()) {
        vacuumRecordStarts[chunkCount] = walAppendResult.startOffset();
      }
    }
    if (status.isOk()) {
      status = wal.forcePending(walForceResult);
      forced = status.isOk();
    }
    for (int record = 0; status.isOk() && record <= chunkCount; record++) {
      status = wal.readForcedRecord(record, walReadResult);
      if (status.isOk()) {
        status = applyOperation(
            vacuumRecordStarts[record],
            walReadResult,
            walReadResult.header().commitSequence());
      }
    }
    if (forced) {
      StatusCode release = wal.releaseForcedBatch();
      if (status.isOk()) {
        status = release;
      }
    }
    clearVacuumRecordStarts();
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    lastCommitSequence = commitSequence;
    result.set(rowsBefore, retainedRows, commitSequence);
    return StatusCode.OK;
  }

  /** Checks whether the current quiescent compaction fits one bounded WAL append batch. */
  StatusCode vacuumPreflight() {
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (phase.operationActive() || phase.preparedInsertGroupActive() || !pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int retainedRows = kernel.indexedEntryCount();
    if (retainedRows < 0 || retainedRows > kernel.rowCount()) {
      return StatusCode.CORRUPTION;
    }
    if (retainedRows == kernel.rowCount()) {
      return StatusCode.CONFLICT;
    }
    int chunkCount = vacuumChunkCount();
    return chunkCount < 0
        ? StatusCode.CORRUPTION
        : chunkCount > 0 && chunkCount < LocalWal.MAX_PENDING_RECORDS
            ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private int vacuumChunkCount() {
    return kernel.vacuumChunkCount();
  }

  private int vacuumChunkRowCount(int firstRow) {
    return kernel.vacuumChunkRowCount(firstRow);
  }

  private int vacuumChunkPayloadBytes(int firstRow, int rowLimit) {
    return kernel.vacuumChunkPayloadBytes(firstRow, rowLimit);
  }

  private StatusCode encodeVacuumChunk(
      ByteBuffer payload,
      int retainedRows,
      int firstRow,
      int rowLimit,
      int chunk,
      int chunkCount,
      int payloadBytes) {
    StatusCode status = kernel.encodeVacuumChunk(
        payload, retainedRows, firstRow, rowLimit, chunk, chunkCount, payloadBytes);
    if (status.isOk()) {
      walCopyBytes += payloadBytes
          - IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES
          - rowLimit * IndexedWalCodec.VACUUM_ENTRY_BYTES;
    }
    return status;
  }

  private void clearVacuumRecordStarts() {
    for (int index = 0; index < vacuumRecordStarts.length; index++) {
      vacuumRecordStarts[index] = 0;
    }
  }

  StatusCode cancelOperation() {
    if (!phase.operationActive()) {
      return StatusCode.CONFLICT;
    }
    if (phase.vacuumOperationActive()) {
      cancelVacuumOperation();
      return StatusCode.OK;
    }
    clearStagedFlags();
    phase.reset();
    pages.resetChanges();
    kernel.clearOperationVersions();
    return StatusCode.OK;
  }

  StatusCode flush() {
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (phase.preparedInsertGroupActive()) {
      return StatusCode.RETRY;
    }
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isDirty(pageId)) {
        continue;
      }
      status = encodeCurrentPage(
          pageId, pages.recordStart(pageId), pages.recordEnd(pageId));
      if (!status.isOk()) {
        return status;
      }
      status = pages.writeCurrent(
          file, pageId, (long) (pageId - 1) * PageCodec.PAGE_BYTES, ioResult);
      if (!status.isOk() || ioResult.bytesTransferred() != PageCodec.PAGE_BYTES) {
        return status.isOk() ? StatusCode.IO_FAILURE : status;
      }
    }
    status = file.truncate((long) pages.highestPageId() * PageCodec.PAGE_BYTES);
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    if (status.isOk()) {
      DirectoryOperationResult forceResult = new DirectoryOperationResult();
      status = directory.force(forceResult);
    }
    if (status.isOk()) {
      for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
        pages.markClean(pageId);
      }
    }
    return status;
  }

  /** Forces an immutable zero-suffix page base in the next WAL lineage. */
  public StatusCode rebaseForCheckpoint(WalGeneration nextGeneration) {
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (phase.operationActive()
        || phase.preparedInsertGroupActive()
        || nextGeneration == null
        || !nextGeneration.isValid()
        || nextGeneration.value() <= walGeneration.value()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    String checkpointFileName = checkpointFileName(nextGeneration);
    DirectoryOperationResult operation = new DirectoryOperationResult();
    status = directory.createFile(checkpointFileName, operation);
    if (status == StatusCode.CONFLICT) {
      status = directory.remove(checkpointFileName, operation);
      if (status.isOk()) {
        status = directory.force(operation);
      }
      if (status.isOk()) {
        status = directory.createFile(checkpointFileName, operation);
      }
    }
    if (!status.isOk()) {
      return status;
    }
    DurableFile checkpointFile = operation.file();
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isPresent(pageId)) {
        checkpointFile.close();
        return StatusCode.CORRUPTION;
      }
      status = pages.encodeCurrent(pageId, database, nextGeneration, 0, 0, checksum);
      if (!status.isOk()) {
        checkpointFile.close();
        failed = true;
        return status;
      }
      status = pages.writeCurrent(
          checkpointFile,
          pageId,
          (long) (pageId - 1) * PageCodec.PAGE_BYTES,
          ioResult);
      if (!status.isOk() || ioResult.bytesTransferred() != PageCodec.PAGE_BYTES) {
        checkpointFile.close();
        failed = true;
        return status.isOk() ? StatusCode.IO_FAILURE : status;
      }
    }
    status = checkpointFile.truncate((long) pages.highestPageId() * PageCodec.PAGE_BYTES);
    if (status.isOk()) {
      status = checkpointFile.force(ForceMode.CONTENT_AND_METADATA);
    }
    StatusCode close = checkpointFile.close();
    if (status.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      status = directory.force(new DirectoryOperationResult());
    }
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    walGeneration = nextGeneration;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      pages.markRebased(pageId);
    }
    return StatusCode.OK;
  }

  public StatusCode captureCheckpointState(
      CheckpointState state,
      long checkpointId,
      long maximumTransactionId) {
    if (state == null
        || checkpointId <= 0
        || maximumTransactionId <= 0
        || !pages.isPresent(HEAP_PAGE_ID)
        || hasDirtyPages()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int checkpointRows = kernel.rowCount();
    state.reset();
    StatusCode status = state.set(
        database,
        walGeneration,
        checkpointId,
        wal.currentCommitSequence(),
        maximumTransactionId,
        pages.highestPageId(),
        checkpointRows);
    if (!status.isOk()) {
      return status;
    }
    for (int rowId = 1; rowId <= checkpointRows; rowId++) {
      status = state.setRowVersion(
          rowId,
          kernel.rowCommitSequence(rowId),
          kernel.previousRowId(rowId),
          kernel.isDeletedRow(rowId));
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  long stagedCopyBytes() {
    return pages.stagedCopyBytes();
  }

  long walCopyBytes() {
    return walCopyBytes;
  }

  int highestPageId() {
    return pages.highestPageId();
  }

  long nextCommitSequence() {
    return wal.nextCommitSequence();
  }

  long currentCommitSequence() {
    return lastCommitSequence;
  }

  long nextTransactionId() {
    return wal.nextTransactionId();
  }

  long rowCommitSequence(int rowId) {
    return kernel.rowCommitSequence(rowId);
  }

  int previousRowId(int rowId) {
    return kernel.previousRowId(rowId);
  }

  boolean isDeletedRow(int rowId) {
    return kernel.isDeletedRow(rowId);
  }

  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (phase.operationActive() || phase.preparedInsertGroupActive() || hasDirtyPages()) {
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
          && walReadResult.header().formatVersion() == WAL_FORMAT_VERSION) {
        int decisionCode = walReadResult.header().decisionCode();
        if (decisionCode != 0 && decisionCode != 1) {
          return StatusCode.CORRUPTION;
        }
        if (decisionCode == 1
            && walReadResult.header().commitSequence() <= lastCommitSequence) {
          return StatusCode.CORRUPTION;
        }
        status = applyOperation(
            offset, walReadResult, walReadResult.header().commitSequence());
        if (!status.isOk()) {
          return status;
        }
        if (decisionCode == 1) {
          lastCommitSequence = walReadResult.header().commitSequence();
          found = true;
        }
      }
      offset = walReadResult.nextOffset();
    }
    if (phase.vacuumOperationActive()) {
      cancelVacuumOperation();
    }
    return found || baseLoaded ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode loadCheckpoint(CheckpointState checkpoint) {
    if (checkpoint.pageCount() <= 0
        || checkpoint.pageCount() > MAX_PAGES
        || checkpoint.rowCount() < 0
        || checkpoint.rowCount() > MAX_ROWS) {
      return StatusCode.CORRUPTION;
    }
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.reopen(
        checkpointFileName(checkpoint.walGeneration()), operation);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.CORRUPTION;
    }
    if (!status.isOk()) {
      return status;
    }
    DurableFile checkpointFile = operation.file();
    status = checkpointFile.size(fileSizeResult);
    long expectedBytes = (long) checkpoint.pageCount() * PageCodec.PAGE_BYTES;
    long checkpointBytes = status.isOk() ? fileSizeResult.sizeBytes() : 0;
    if (!status.isOk() || checkpointBytes > expectedBytes) {
      checkpointFile.close();
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    boolean repaired = false;
    for (int pageId = 1; pageId <= checkpoint.pageCount(); pageId++) {
      ensurePageBuffers(pageId);
      long pageOffset = (long) (pageId - 1) * PageCodec.PAGE_BYTES;
      boolean loaded = false;
      if (pageOffset + PageCodec.PAGE_BYTES <= checkpointBytes) {
        status = pages.readCurrent(checkpointFile, pageId, pageOffset, ioResult);
        if (!status.isOk()) {
          checkpointFile.close();
          return status;
        }
        if (ioResult.bytesTransferred() == PageCodec.PAGE_BYTES) {
          loaded = validateCheckpointPage(pageId, walGeneration.value()).isOk();
        }
      }
      if (!loaded) {
        status = repairCheckpointPage(checkpointFile, pageId, pageOffset);
        if (!status.isOk()) {
          checkpointFile.close();
          return status;
        }
        repaired = true;
      }
      pages.installPresent(pageId);
    }
    if (repaired) {
      status = checkpointFile.truncate(expectedBytes);
      if (status.isOk()) {
        status = checkpointFile.force(ForceMode.CONTENT_AND_METADATA);
      }
      if (!status.isOk()) {
        checkpointFile.close();
        return status;
      }
    }
    status = kernel.rebuildRowLocations();
    if (!status.isOk() || kernel.rowCount() != checkpoint.rowCount()) {
      checkpointFile.close();
      return StatusCode.CORRUPTION;
    }
    status = checkpointFile.close();
    if (!status.isOk()) {
      return status;
    }
    kernel.loadCheckpointVersions(checkpoint);
    lastCommitSequence = checkpoint.commitSequence();
    baseLoaded = true;
    return StatusCode.OK;
  }

  private StatusCode repairCheckpointPage(
      DurableFile checkpointFile,
      int pageId,
      long pageOffset) {
    if (walGeneration.value() <= 1) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = pages.readCurrent(file, pageId, pageOffset, ioResult);
    if (!status.isOk() || ioResult.bytesTransferred() != PageCodec.PAGE_BYTES) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    status = validateCheckpointPage(pageId, 0);
    if (!status.isOk()) {
      return StatusCode.CORRUPTION;
    }
    status = pages.encodeCurrent(pageId, database, walGeneration, 0, 0, checksum);
    if (!status.isOk()) {
      return status;
    }
    status = pages.writeCurrent(checkpointFile, pageId, pageOffset, ioResult);
    return status.isOk() && ioResult.bytesTransferred() != PageCodec.PAGE_BYTES
        ? StatusCode.IO_FAILURE : status;
  }

  private StatusCode validateCheckpointPage(int pageId, long expectedWalGeneration) {
    StatusCode status = pages.validateCurrent(pageId, pageHeader, checksum);
    if (!status.isOk()
        || pageHeader.databaseHigh() != database.high()
        || pageHeader.databaseLow() != database.low()
        || pageHeader.pageId() != pageId
        || pageHeader.pageGeneration() != 1) {
      return StatusCode.CORRUPTION;
    }
    if ((expectedWalGeneration == 0
            && pageHeader.walGeneration() >= walGeneration.value())
        || (expectedWalGeneration != 0
            && pageHeader.walGeneration() != expectedWalGeneration)) {
      return StatusCode.CORRUPTION;
    }
    if (expectedWalGeneration == walGeneration.value()
        && (pageHeader.recordStart() != 0 || pageHeader.recordEnd() != 0)) {
      return StatusCode.CORRUPTION;
    }
    return kernel.validateCurrentPage(pageId);
  }

  private StatusCode applyOperation(
      long recordStart,
      LocalWalReadResult record,
      long commitSequence) {
    ByteBuffer payload = record.payload();
    if (record.header().payloadBytes() < IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES
        || !IndexedWalCodec.hasCommonHeader(payload)) {
      return StatusCode.CORRUPTION;
    }
    int operationType = IndexedWalCodec.operationType(payload);
    if (phase.vacuumOperationActive()
        && operationType != IndexedWalCodec.OPERATION_TYPE_VACUUM_CHUNK
        && operationType != IndexedWalCodec.OPERATION_TYPE_VACUUM_COMMIT) {
      return StatusCode.CORRUPTION;
    }
    int decisionCode = record.header().decisionCode();
    if (operationType == IndexedWalCodec.OPERATION_TYPE_VACUUM_CHUNK) {
      return decisionCode == 0 && commitSequence == 0
          ? applyVacuumChunk(
              payload, recordStart, record.header().transactionId())
          : StatusCode.CORRUPTION;
    }
    if (operationType == IndexedWalCodec.OPERATION_TYPE_VACUUM_COMMIT) {
      return decisionCode == 1
          ? applyVacuumCommit(
              payload,
              record.nextOffset(),
              record.header().transactionId(),
              commitSequence)
          : StatusCode.CORRUPTION;
    }
    if (decisionCode != 1) {
      return StatusCode.CORRUPTION;
    }
    if (operationType == IndexedWalCodec.OPERATION_TYPE_INSERT) {
      return kernel.applyInsertOperation(
          payload, recordStart, record.nextOffset(), commitSequence);
    }
    if (operationType == IndexedWalCodec.OPERATION_TYPE_INSERT_BATCH) {
      return kernel.applyInsertBatchOperation(
          payload, recordStart, record.nextOffset(), commitSequence);
    }
    if (operationType == IndexedWalCodec.OPERATION_TYPE_MUTATION_BATCH) {
      return kernel.applyMutationBatchOperation(
          payload, recordStart, record.nextOffset(), commitSequence);
    }
    if (operationType != IndexedWalCodec.OPERATION_TYPE_PAGE_IMAGES) {
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
    StatusCode structural = IndexedWalCodec.validatePageOperation(
        payload, MAX_CHANGED_PAGES, MAX_OPERATION_ROWS);
    if (!structural.isOk()) {
      return structural;
    }
    int pageCount = IndexedWalCodec.pageOperationPageCount(payload);
    int versionCount = IndexedWalCodec.pageOperationVersionCount(payload);
    int previousRowCount = kernel.rowCount();
    for (int index = 0; index < pageCount; index++) {
      int pageOffset = IndexedWalCodec.pageOperationPageOffset(index);
      StatusCode status = pages.validateRecord(payload, pageOffset, pageHeader, checksum);
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
          || IndexedWalCodec.containsEarlierPageId(recoveryPageIds, index, pageId)) {
        return status.isOk() ? StatusCode.CORRUPTION : status;
      }
      recoveryPageIds[index] = pageId;
    }
    for (int index = 0; index < pageCount; index++) {
      int pageId = recoveryPageIds[index];
      int pageOffset = IndexedWalCodec.pageOperationPageOffset(index);
      ensurePageBuffers(pageId);
      pages.installFromRecord(payload, pageOffset, pageId, recordStart, recordEnd);
    }
    StatusCode status = kernel.validateAppliedPages(recoveryPageIds, pageCount);
    if (status.isOk()) {
      status = kernel.rebuildRowLocations();
    }
    if (status.isOk() && kernel.rowCount() - previousRowCount != versionCount) {
      status = StatusCode.CORRUPTION;
    }
    int versionOffset = IndexedWalCodec.pageOperationVersionsOffset(pageCount);
    if (status.isOk()) {
      status = kernel.applyRecoveredVersions(
          payload, versionOffset, previousRowCount, versionCount, commitSequence);
    }
    return status;
  }

  private StatusCode applyVacuumChunk(
      ByteBuffer payload,
      long recordStart,
      long transactionId) {
    StatusCode structural = IndexedWalCodec.validateVacuumChunk(
        payload, MAX_ROWS, LocalWal.MAX_PENDING_RECORDS - 1);
    if (!structural.isOk()) {
      return structural;
    }
    if (transactionId <= 0
        || !pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.CORRUPTION;
    }
    int retainedRows = IndexedWalCodec.vacuumRetainedRows(payload);
    int firstRow = IndexedWalCodec.vacuumFirstRow(payload);
    int chunkRows = IndexedWalCodec.vacuumRowCount(payload);
    int chunk = IndexedWalCodec.vacuumChunk(payload);
    int chunkCount = IndexedWalCodec.vacuumChunkCount(payload);
    StatusCode status;
    if (chunk == 0) {
      if (firstRow != 0
          || phase.vacuumOperationActive()
          || kernel.indexedEntryCount() != retainedRows) {
        return StatusCode.CORRUPTION;
      }
      status = beginVacuumOperation(
          retainedRows, chunkCount, transactionId, recordStart);
    } else {
      status = !phase.vacuumOperationActive()
              || transactionId != vacuumTransactionId
              || retainedRows != vacuumExpectedRows
              || chunkCount != vacuumExpectedChunks
              || chunk != vacuumAppliedChunks
              || firstRow != vacuumAppliedRows
          ? StatusCode.CORRUPTION : StatusCode.OK;
    }
    int entryOffset = IndexedWalCodec.VACUUM_CHUNK_HEADER_BYTES;
    for (int index = 0; status.isOk() && index < chunkRows; index++) {
      int rowBytes = IndexedWalCodec.vacuumEntryRowBytes(payload, entryOffset);
      int compactedRowId = vacuumAppliedRows + 1;
      status = kernel.applyVacuumEntry(payload, entryOffset, compactedRowId);
      if (status.isOk()) {
        vacuumAppliedRows++;
      }
      entryOffset += IndexedWalCodec.VACUUM_ENTRY_BYTES + rowBytes;
    }
    if (status.isOk() && entryOffset != payload.limit()) {
      status = StatusCode.CORRUPTION;
    }
    if (!status.isOk()) {
      cancelVacuumOperation();
      return status;
    }
    vacuumAppliedChunks++;
    return StatusCode.OK;
  }

  private StatusCode beginVacuumOperation(
      int retainedRows,
      int chunkCount,
      long transactionId,
      long recordStart) {
    if (phase.operationActive() || phase.preparedInsertGroupActive()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    pages.resetChanges();
    if (!phase.beginVacuumApply()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    vacuumExpectedRows = retainedRows;
    vacuumExpectedChunks = chunkCount;
    vacuumTransactionId = transactionId;
    vacuumRecordStart = recordStart;
    StatusCode status = kernel.beginVacuumApply();
    if (!status.isOk()) {
      cancelVacuumOperation();
    }
    return status;
  }

  private StatusCode applyVacuumCommit(
      ByteBuffer payload,
      long recordEnd,
      long transactionId,
      long commitSequence) {
    StatusCode structural = IndexedWalCodec.validateVacuumCommit(
        payload, MAX_ROWS, LocalWal.MAX_PENDING_RECORDS - 1);
    if (!structural.isOk()) {
      cancelVacuumOperation();
      return structural;
    }
    int retainedRows = IndexedWalCodec.vacuumRetainedRows(payload);
    int chunkCount = IndexedWalCodec.vacuumCommitChunkCount(payload);
    int rowsBefore = IndexedWalCodec.vacuumCommitRowsBefore(payload);
    if (!phase.vacuumOperationActive()
        || transactionId != vacuumTransactionId
        || commitSequence <= lastCommitSequence
        || retainedRows != vacuumExpectedRows
        || chunkCount != vacuumExpectedChunks
        || vacuumAppliedRows != retainedRows
        || vacuumAppliedChunks != chunkCount
        || rowsBefore != kernel.rowCount()) {
      cancelVacuumOperation();
      return StatusCode.CORRUPTION;
    }
    publishStagedPages(vacuumRecordStart, recordEnd);
    StatusCode status = kernel.rebuildRowLocations();
    if (!status.isOk() || kernel.rowCount() != retainedRows) {
      cancelVacuumOperation();
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    kernel.publishVacuumVersions(retainedRows, commitSequence);
    finishVacuumOperation();
    return StatusCode.OK;
  }

  private void cancelVacuumOperation() {
    clearStagedFlags();
    kernel.cancelVacuumVersions(vacuumAppliedRows);
    finishVacuumOperation();
  }

  private void finishVacuumOperation() {
    phase.reset();
    pages.resetChanges();
    vacuumExpectedRows = 0;
    vacuumAppliedRows = 0;
    vacuumExpectedChunks = 0;
    vacuumAppliedChunks = 0;
    kernel.resetVacuumApply();
    vacuumTransactionId = 0;
    vacuumRecordStart = 0;
  }

  private boolean canAppendRows(int[] rowLengths, int count) {
    return kernel.canAppendRows(rowLengths, count);
  }

  boolean canAppendEncodedRows(
      ByteBuffer payload,
      int firstEntryOffset,
      int count,
      int rowLengthOffset,
      int entryBytes) {
    return kernel.canAppendEncodedRows(
        payload, firstEntryOffset, count, rowLengthOffset, entryBytes);
  }

  private StatusCode encodeCurrentPage(int pageId, long recordStart, long recordEnd) {
    return pages.encodeCurrent(
        pageId, database, walGeneration, recordStart, recordEnd, checksum);
  }

  private boolean addChangedPage(int pageId) {
    int maximumChangedPages = phase.vacuumOperationActive() ? MAX_PAGES : MAX_CHANGED_PAGES;
    return pages.addChangedPage(pageId, maximumChangedPages);
  }

  private void publishStagedPages() {
    publishStagedPages(walAppendResult.startOffset(), walAppendResult.endOffset());
  }

  private void publishStagedPages(long recordStart, long recordEnd) {
    pages.publish(recordStart, recordEnd);
  }

  private void clearStagedFlags() {
    pages.clearStagedFlags();
  }

  private void copyPreparedRow(
      ByteBuffer source,
      int sourceOffset,
      ByteBuffer target,
      int targetOffset,
      int rowBytes) {
    for (int index = 0; index < rowBytes; index++) {
      target.put(targetOffset + index, source.get(sourceOffset + index));
    }
    walCopyBytes += rowBytes;
  }

  private void clearPreparedInsertGroup() {
    for (int index = 0; index < preparedKeyCount; index++) {
      preparedKeys[index] = 0;
      preparedLeafPageIds[index] = 0;
      preparedNewIndexEntries[index] = false;
    }
    for (int index = 0; index < preparedRecordCount; index++) {
      preparedRecordStarts[index] = 0;
      preparedCommitSequences[index] = 0;
      preparedTransactionIds[index] = 0;
    }
    preparedKeyCount = 0;
    preparedRecordCount = 0;
    preparedRowCount = 0;
    preparedHeapBytes = 0;
    phase.reset();
  }

  private boolean validPresentPage(int pageId) {
    return pages.validPresentPage(pageId);
  }

  private boolean hasDirtyPages() {
    return pages.hasDirtyPages();
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
      IndexedTableStoreOpenResult result) {
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

  private void ensurePageBuffers(int pageId) {
    pages.ensureBuffers(pageId);
  }

}
