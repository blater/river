package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
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
  public static final int MAX_PAGES = IndexedTableLimits.MAX_PAGES;
  public static final int MAX_CHANGED_PAGES = IndexedTableLimits.MAX_CHANGED_PAGES;
  public static final int MAX_ROWS = IndexedTableLimits.MAX_ROWS;
  public static final int VACUUM_COMMIT_PAYLOAD_BYTES =
      IndexedWalCodec.VACUUM_COMMIT_PAYLOAD_BYTES;

  private static final int HEAP_PAGE_ID = IndexedTableKernel.HEAP_PAGE_ID;
  private static final int ROOT_META_PAGE_ID = IndexedTableKernel.ROOT_META_PAGE_ID;
  private static final long BOOTSTRAP_TRANSACTION_ID = 1;
  private static final int MAX_PREPARED_INSERT_ROWS = IndexedTableLimits.MAX_OPERATION_ROWS;
  static final int MAX_OPERATION_ROWS = IndexedTableLimits.MAX_OPERATION_ROWS;

  private final DurableDirectory directory;
  private final DurableFile file;
  private final LocalWal wal;
  private final DatabaseIncarnation database;
  private final IndexedTableKernel kernel;
  private final IndexedCheckpointWriter checkpointWriter;
  private final IndexedCheckpointLoader checkpointLoader;
  private final IndexedWalRecovery recovery;
  private final IndexedVacuumWriter vacuumWriter;
  private WalGeneration walGeneration;
  private final IndexedPageSet pages = new IndexedPageSet();
  private final long[] preparedKeys = new long[MAX_PREPARED_INSERT_ROWS];
  private final int[] preparedLeafPageIds = new int[MAX_PREPARED_INSERT_ROWS];
  private final boolean[] preparedNewIndexEntries = new boolean[MAX_PREPARED_INSERT_ROWS];
  private final long[] preparedRecordStarts = new long[LocalWal.MAX_PENDING_RECORDS];
  private final long[] preparedCommitSequences = new long[LocalWal.MAX_PENDING_RECORDS];
  private final long[] preparedTransactionIds = new long[LocalWal.MAX_PENDING_RECORDS];
  private final CRC32C checksum = new CRC32C();
  private final IoResult ioResult = new IoResult();
  private final LocalWalReservation walReservation = new LocalWalReservation();
  private final LocalWalAppendResult walAppendResult = new LocalWalAppendResult();
  private final LocalWalForceResult walForceResult = new LocalWalForceResult();
  private final LocalWalReadResult walReadResult = new LocalWalReadResult();
  private int preparedKeyCount;
  private int preparedRecordCount;
  private int preparedRowCount;
  private int preparedHeapBytes;
  private final IndexedStorePhase phase = new IndexedStorePhase();
  private boolean failed;
  private boolean closed;
  private boolean baseLoaded;
  private long walCopyBytes;
  private volatile long lastCommitSequence;

  IndexedTableStore(
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
    kernel = new IndexedTableKernel(pages);
    checkpointWriter = new IndexedCheckpointWriter(directory, pages, database);
    checkpointLoader = new IndexedCheckpointLoader(
        directory, file, pages, kernel, database);
    recovery = new IndexedWalRecovery(wal, pages, kernel, database, phase);
    vacuumWriter = new IndexedVacuumWriter(wal, kernel, recovery);
  }

  StatusCode initialize() {
    StatusCode status = beginOperation();
    if (!status.isOk()) {
      return status;
    }
    status = kernel.initializePages();
    if (status.isOk()) {
      status = commit(BOOTSTRAP_TRANSACTION_ID, nextCommitSequence());
    }
    if (status.isOk()) {
      status = flush();
    }
    if (!status.isOk()) {
      cancelOperation();
    }
    return status;
  }

  StatusCode validate() {
    return kernel.validate();
  }

  StatusCode insert(
      long transactionId,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    return insertCommitted(transactionId, nextCommitSequence(), key, row, result);
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
    long commitSequence = nextCommitSequence();
    HeapInsertResult inserted = kernel.heapInsertResult();
    StatusCode status = insertCommitted(
        transactionId, commitSequence, key, row, inserted);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
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
    long commitSequence = nextCommitSequence();
    HeapInsertResult inserted = kernel.heapInsertResult();
    StatusCode status = commitInsertBatch(
        transactionId,
        commitSequence,
        keys,
        rows,
        rowStride,
        rowLengths,
        insertCount,
        inserted);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
      return StatusCode.OK;
    }
    if (status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    status = beginOperation();
    if (!status.isOk()) {
      return status;
    }
    status = kernel.stageInsertBatch(
        keys, rows, rowStride, rowLengths, insertCount, inserted);
    if (!status.isOk()) {
      cancelOperation();
      return status;
    }
    status = commit(transactionId, commitSequence);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
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
    long commitSequence = nextCommitSequence();
    HeapInsertResult inserted = kernel.heapInsertResult();
    StatusCode status = commitMutationBatch(
        transactionId,
        commitSequence,
        operations,
        keys,
        previousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        inserted);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
      return StatusCode.OK;
    }
    if (status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    status = beginOperation();
    if (!status.isOk()) {
      return status;
    }
    status = kernel.stageMutationBatch(
        operations,
        keys,
        previousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        inserted);
    if (!status.isOk()) {
      cancelOperation();
      return status;
    }
    status = commit(transactionId, commitSequence);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
    }
    return status;
  }

  StatusCode commitMutations(
      long transactionId,
      PendingMutationBuffer mutations,
      IndexedCommitResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID
        || mutations == null
        || mutations.count() <= 0
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long commitSequence = nextCommitSequence();
    HeapInsertResult inserted = kernel.heapInsertResult();
    boolean mixed = mutations.containsNonInsertMutation();
    StatusCode status = mixed
        ? commitMutationBatch(transactionId, commitSequence, mutations, inserted)
        : commitInsertBatch(transactionId, commitSequence, mutations, inserted);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
      return StatusCode.OK;
    }
    if (status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    status = beginOperation();
    if (!status.isOk()) {
      return status;
    }
    status = mixed
        ? kernel.stageMutationBatch(mutations, inserted)
        : kernel.stageInsertBatch(mutations, inserted);
    if (!status.isOk()) {
      cancelOperation();
      return status;
    }
    status = commit(transactionId, commitSequence);
    if (status.isOk()) {
      result.set(inserted.rowId(), commitSequence);
    }
    return status;
  }

  StatusCode appendPreparedWrites(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    return mutations.containsNonInsertMutation()
        ? appendPreparedMutationBatch(transactionId, commitSequence, mutations, result)
        : appendPreparedInsertBatch(transactionId, commitSequence, mutations, result);
  }

  StatusCode cancelPreparedInsertGroup() {
    return cancelPreparedInsertPreflight();
  }

  StatusCode publishForcedGroup() {
    return publishForcedInserts();
  }

  StatusCode vacuum(long transactionId, IndexedVacuumResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return commitVacuum(transactionId, nextCommitSequence(), result);
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
    int leafPageId = kernel.findLeafPageId(key);
    StatusCode status = kernel.validateNewIndexEntryAt(leafPageId, key, 0);
    if (!status.isOk() && status != StatusCode.RESOURCE_EXHAUSTED) {
      return status;
    }
    if (!kernel.canAppendRow(row.remaining())) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) {
      return commitInsert(transactionId, commitSequence, key, row, result);
    }
    status = beginOperation();
    if (!status.isOk()) {
      return status;
    }
    status = kernel.stageInsert(leafPageId, key, row);
    if (!status.isOk()) {
      cancelOperation();
      return status;
    }
    status = commit(transactionId, commitSequence);
    if (status.isOk()) {
      result.setRowId(kernel.heapInsertResult().rowId());
    }
    return status;
  }

  StatusCode fetchByKey(long key, io.riverdb.storage.heap.HeapRowResult result) {
    return kernel.fetchByKeyAt(lastCommitSequence, key, result);
  }

  StatusCode fetchByKeyAt(
      long visibleCommitSequence,
      long key,
      io.riverdb.storage.heap.HeapRowResult result) {
    return kernel.fetchByKeyAt(visibleCommitSequence, key, result);
  }

  int firstLeafPageId(long lowerKey) {
    return kernel.findLeafPageId(lowerKey);
  }

  StatusCode nextScan(IndexedScanCursor cursor, IndexedScanResult result) {
    return kernel.nextScan(cursor, result);
  }

  StatusCode prepareMutation(
      long visibleCommitSequence,
      long key,
      IndexedMutationTarget result) {
    return kernel.prepareMutation(visibleCommitSequence, key, result);
  }

  StatusCode prepareInsert(
      long visibleCommitSequence,
      long key,
      IndexedMutationTarget result) {
    return kernel.prepareInsert(visibleCommitSequence, key, result);
  }

  int rootPageId() {
    return kernel.rootPageId();
  }

  int pageCount() {
    return pages.highestPageId();
  }

  int treeHeight() {
    return kernel.treeHeight();
  }

  public static StatusCode create(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      IndexedTableStoreOpenResult result) {
    return IndexedTableStoreFactory.create(
        directory, wal, database, walGeneration, result);
  }

  public static StatusCode open(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      IndexedTableStoreOpenResult result) {
    return IndexedTableStoreFactory.open(
        directory, wal, database, walGeneration, true, result);
  }

  public static StatusCode openExisting(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      IndexedTableStoreOpenResult result) {
    return IndexedTableStoreFactory.open(
        directory, wal, database, walGeneration, false, result);
  }

  public static StatusCode openCheckpoint(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      CheckpointState checkpoint,
      IndexedTableStoreOpenResult result) {
    return IndexedTableStoreFactory.openCheckpoint(
        directory, wal, database, checkpoint, result);
  }

  public static String checkpointFileName(WalGeneration generation) {
    return generation == null || !generation.isValid()
        ? "" : FILE_NAME + ".checkpoint." + generation.value();
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
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (transactionId <= 0
        || commitSequence <= lastCommitSequence
        || mutations == null
        || mutations.count() <= 0
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
    status = validatePendingInsertBatch(mutations);
    if (!status.isOk()) {
      return status;
    }
    if (!kernel.canAppendRows(mutations)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = pendingInsertOperationBytes(mutations);
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    int firstRowId = kernel.rowCount() + 1;
    encodePendingInsertRecord(
        mutations, recordPayload, operationBytes, firstRowId);
    status = publishInsertRecord(
        transactionId,
        commitSequence,
        recordPayload,
        mutations.count() == 1);
    if (!status.isOk()) {
      return status;
    }
    lastCommitSequence = commitSequence;
    result.setRowId(firstRowId + mutations.count() - 1);
    return StatusCode.OK;
  }

  private StatusCode validatePendingInsertBatch(PendingMutationBuffer mutations) {
    for (int index = 0; index < mutations.count(); index++) {
      StatusCode status = validatePendingInsert(mutations, index);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode validatePendingInsert(
      PendingMutationBuffer mutations, int index) {
    int rowBytes = mutations.rowLengthAt(index);
    long key = mutations.keyAt(index);
    if (key == Long.MAX_VALUE
        || rowBytes <= 0
        || rowBytes > mutations.rowStride()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int leafPageId = kernel.findLeafPageId(key);
    int earlierInLeaf = 0;
    for (int previous = 0; previous < index; previous++) {
      if (mutations.keyAt(previous) == key) {
        return StatusCode.CONFLICT;
      }
      if (kernel.findLeafPageId(mutations.keyAt(previous)) == leafPageId) {
        earlierInLeaf++;
      }
    }
    return kernel.validateNewIndexEntryAt(leafPageId, key, earlierInLeaf);
  }

  private int pendingInsertOperationBytes(PendingMutationBuffer mutations) {
    if (mutations.count() == 1) {
      return IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES
          + mutations.rowLengthAt(0);
    }
    int operationBytes = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutations.count(); index++) {
      operationBytes += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES
          + mutations.rowLengthAt(index);
    }
    return operationBytes;
  }

  private void encodePendingInsertRecord(
      PendingMutationBuffer mutations,
      ByteBuffer recordPayload,
      int operationBytes,
      int firstRowId) {
    if (mutations.count() == 1) {
      IndexedWalCodec.encodeInsertHeader(
          recordPayload, mutations.keyAt(0), firstRowId, mutations.rowLengthAt(0));
      mutations.copyRowTo(0, recordPayload, IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES);
      walCopyBytes += mutations.rowLengthAt(0);
    } else {
      IndexedWalCodec.encodeInsertBatchHeader(recordPayload, mutations.count());
      int outputOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
      for (int index = 0; index < mutations.count(); index++) {
        int rowBytes = mutations.rowLengthAt(index);
        IndexedWalCodec.encodeInsertBatchEntry(
            recordPayload, outputOffset, mutations.keyAt(index), firstRowId + index, rowBytes);
        int rowOffset = outputOffset + IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES;
        mutations.copyRowTo(index, recordPayload, rowOffset);
        walCopyBytes += rowBytes;
        outputOffset = rowOffset + rowBytes;
      }
    }
    recordPayload.position(operationBytes);
  }

  private StatusCode publishInsertRecord(
      long transactionId,
      long commitSequence,
      ByteBuffer recordPayload,
      boolean singleInsert) {
    StatusCode status = wal.publish(
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
    status = singleInsert
        ? kernel.applyInsertOperation(
            recordPayload,
            walAppendResult.startOffset(),
            walAppendResult.endOffset(),
            commitSequence)
        : kernel.applyInsertBatchOperation(
            recordPayload,
            walAppendResult.startOffset(),
            walAppendResult.endOffset(),
            commitSequence);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
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
    status = validateRawInsertBatch(
        keys, rows, rowStride, rowLengths, insertCount);
    if (!status.isOk()) {
      return status;
    }
    if (!canAppendRows(rowLengths, insertCount)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = rawInsertOperationBytes(rowLengths, insertCount);
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    int firstRowId = kernel.rowCount() + 1;
    encodeRawInsertBatch(
        recordPayload,
        keys,
        rows,
        rowStride,
        rowLengths,
        insertCount,
        firstRowId,
        operationBytes);
    status = publishInsertRecord(
        transactionId, commitSequence, recordPayload, false);
    if (!status.isOk()) {
      return status;
    }
    lastCommitSequence = commitSequence;
    result.setRowId(firstRowId + insertCount - 1);
    return StatusCode.OK;
  }

  private StatusCode validateRawInsertBatch(
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount) {
    for (int index = 0; index < insertCount; index++) {
      StatusCode status = validateRawInsert(
          keys, rows, rowStride, rowLengths, index);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode validateRawInsert(
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int index) {
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
    return kernel.validateNewIndexEntryAt(leafPageId, key, earlierInLeaf);
  }

  private static int rawInsertOperationBytes(int[] rowLengths, int insertCount) {
    int bytes = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      bytes += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES + rowLengths[index];
    }
    return bytes;
  }

  private void encodeRawInsertBatch(
      ByteBuffer payload,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      int firstRowId,
      int operationBytes) {
    IndexedWalCodec.encodeInsertBatchHeader(payload, insertCount);
    int outputOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    for (int index = 0; index < insertCount; index++) {
      int rowBytes = rowLengths[index];
      IndexedWalCodec.encodeInsertBatchEntry(
          payload, outputOffset, keys[index], firstRowId + index, rowBytes);
      int rowOffset = outputOffset + IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES;
      copyRawMutationRow(rows, index * rowStride, payload, rowOffset, rowBytes);
      outputOffset = rowOffset + rowBytes;
    }
    payload.position(operationBytes);
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

  StatusCode preflightPreparedWrites(PendingMutationBuffer mutations) {
    if (mutations == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return mutations.containsNonInsertMutation()
        ? preflightPreparedMutationBatch(mutations)
        : preflightPreparedInsertBatch(mutations);
  }

  private StatusCode preflightPreparedInsertBatch(PendingMutationBuffer mutations) {
    if (!phase.preparedInsertGroupActive()
        || phase.preparedInsertEncoding()
        || mutations.count() <= 0
        || preparedKeyCount + mutations.count() > preparedKeys.length
        || kernel.rowCount() + preparedKeyCount + mutations.count() > MAX_ROWS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int originalKeyCount = preparedKeyCount;
    int originalHeapBytes = preparedHeapBytes;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < mutations.count(); index++) {
      long key = mutations.keyAt(index);
      int rowBytes = mutations.rowLengthAt(index);
      status = validatePreparedInput(key, rowBytes, mutations.rowStride());
      int leafPageId = status.isOk() ? kernel.findLeafPageId(key) : 0;
      int entriesInLeaf = status.isOk()
          ? preparedEntriesInLeaf(leafPageId, false) : 0;
      status = status.isOk()
          ? kernel.validateNewIndexEntryAt(leafPageId, key, entriesInLeaf) : status;
      leafPageId = status.isOk() ? kernel.validatedLeafPageId() : leafPageId;
      int required = HeapPage.SLOT_BYTES + rowBytes;
      status = validatePreparedHeap(status, required);
      if (status.isOk()) {
        addPreparedKey(key, leafPageId, true, required);
      }
    }
    rollbackPreparedPreflight(originalKeyCount, originalHeapBytes, status);
    return status;
  }

  private StatusCode preflightPreparedMutationBatch(PendingMutationBuffer mutations) {
    if (!validPreparedPreflight(mutations)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int originalKeyCount = preparedKeyCount;
    int originalHeapBytes = preparedHeapBytes;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < mutations.count(); index++) {
      int operation = mutations.operationAt(index);
      long key = mutations.keyAt(index);
      int previousRowId = mutations.previousRowIdAt(index);
      int rowBytes = mutations.rowLengthAt(index);
      status = validPreparedMutation(operation)
          ? validatePreparedInput(key, rowBytes, mutations.rowStride())
          : StatusCode.INVALID_EXTERNAL_INPUT;
      int leafPageId = status.isOk() ? kernel.findLeafPageId(key) : 0;
      boolean newIndexEntry = operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0;
      int entriesInLeaf = status.isOk()
          ? preparedEntriesInLeaf(leafPageId, true) : 0;
      if (status.isOk()) {
        status = kernel.validateMutationTargetAt(
            leafPageId, operation, key, previousRowId, entriesInLeaf);
        leafPageId = kernel.validatedLeafPageId();
      }
      int required = HeapPage.SLOT_BYTES + rowBytes;
      status = validatePreparedHeap(status, required);
      if (status.isOk()) {
        addPreparedKey(key, leafPageId, newIndexEntry, required);
      }
    }
    rollbackPreparedPreflight(originalKeyCount, originalHeapBytes, status);
    return status;
  }

  private boolean validPreparedPreflight(PendingMutationBuffer mutations) {
    return phase.preparedInsertGroupActive()
        && !phase.preparedInsertEncoding()
        && mutations.count() > 0
        && preparedKeyCount + mutations.count() <= preparedKeys.length
        && kernel.rowCount() + preparedKeyCount + mutations.count() <= MAX_ROWS;
  }

  private StatusCode validatePreparedInput(long key, int rowBytes, int rowStride) {
    if (key == Long.MAX_VALUE || rowBytes <= 0 || rowBytes > rowStride) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int previous = 0; previous < preparedKeyCount; previous++) {
      if (preparedKeys[previous] == key) {
        return StatusCode.CONFLICT;
      }
    }
    return StatusCode.OK;
  }

  private boolean validPreparedMutation(int operation) {
    return operation == IndexedWalCodec.MUTATION_INSERT
        || operation == IndexedWalCodec.MUTATION_UPDATE
        || operation == IndexedWalCodec.MUTATION_DELETE;
  }

  private int preparedEntriesInLeaf(int leafPageId, boolean newEntriesOnly) {
    int entries = 0;
    for (int previous = 0; previous < preparedKeyCount; previous++) {
      if ((!newEntriesOnly || preparedNewIndexEntries[previous])
          && preparedLeafPageIds[previous] == leafPageId) {
        entries++;
      }
    }
    return entries;
  }

  private StatusCode validatePreparedHeap(StatusCode status, int required) {
    return status.isOk()
            && preparedHeapBytes + required > kernel.currentHeapAvailableBytes()
        ? StatusCode.RESOURCE_EXHAUSTED : status;
  }

  private void addPreparedKey(
      long key, int leafPageId, boolean newIndexEntry, int required) {
    preparedKeys[preparedKeyCount] = key;
    preparedLeafPageIds[preparedKeyCount] = leafPageId;
    preparedNewIndexEntries[preparedKeyCount] = newIndexEntry;
    preparedKeyCount++;
    preparedHeapBytes += required;
  }

  private void rollbackPreparedPreflight(
      int originalKeyCount,
      int originalHeapBytes,
      StatusCode status) {
    if (status.isOk()) {
      return;
    }
    for (int index = originalKeyCount; index < preparedKeyCount; index++) {
      preparedKeys[index] = 0;
      preparedLeafPageIds[index] = 0;
      preparedNewIndexEntries[index] = false;
    }
    preparedKeyCount = originalKeyCount;
    preparedHeapBytes = originalHeapBytes;
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
  private StatusCode appendPreparedInsertBatch(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (!phase.preparedInsertGroupActive()
        || !phase.preparedInsertEncoding()
        || preparedRecordCount >= LocalWal.MAX_PENDING_RECORDS
        || transactionId <= 0
        || commitSequence != wal.nextCommitSequence()
        || mutations.count() <= 0
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int operationBytes = mutations.count() == 1
        ? IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES + mutations.rowLengthAt(0)
        : IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
    if (mutations.count() > 1) {
      for (int index = 0; index < mutations.count(); index++) {
        operationBytes += IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES
            + mutations.rowLengthAt(index);
      }
    }
    StatusCode status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    ByteBuffer payload = walReservation.writablePayload();
    int firstRowId = kernel.rowCount() + preparedRowCount + 1;
    if (mutations.count() == 1) {
      IndexedWalCodec.encodeInsertHeader(
          payload, mutations.keyAt(0), firstRowId, mutations.rowLengthAt(0));
      mutations.copyRowTo(0, payload, IndexedWalCodec.INSERT_OPERATION_HEADER_BYTES);
      walCopyBytes += mutations.rowLengthAt(0);
    } else {
      IndexedWalCodec.encodeInsertBatchHeader(payload, mutations.count());
      int outputOffset = IndexedWalCodec.INSERT_BATCH_HEADER_BYTES;
      for (int index = 0; index < mutations.count(); index++) {
        int rowBytes = mutations.rowLengthAt(index);
        IndexedWalCodec.encodeInsertBatchEntry(
            payload, outputOffset, mutations.keyAt(index), firstRowId + index, rowBytes);
        int rowOffset = outputOffset + IndexedWalCodec.INSERT_BATCH_ENTRY_BYTES;
        mutations.copyRowTo(index, payload, rowOffset);
        walCopyBytes += rowBytes;
        outputOffset = rowOffset + rowBytes;
      }
    }
    return finishPreparedAppend(
        transactionId, commitSequence, operationBytes, mutations.count(), firstRowId, result);
  }

  private StatusCode appendPreparedMutationBatch(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (!phase.preparedInsertGroupActive()
        || !phase.preparedInsertEncoding()
        || preparedRecordCount >= LocalWal.MAX_PENDING_RECORDS
        || transactionId <= 0
        || commitSequence != wal.nextCommitSequence()
        || mutations.count() <= 0
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int operationBytes = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutations.count(); index++) {
      operationBytes += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES
          + mutations.rowLengthAt(index);
    }
    StatusCode status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    ByteBuffer payload = walReservation.writablePayload();
    IndexedWalCodec.encodeMutationBatchHeader(payload, mutations.count());
    int outputOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    int firstRowId = kernel.rowCount() + preparedRowCount + 1;
    for (int index = 0; index < mutations.count(); index++) {
      int rowBytes = mutations.rowLengthAt(index);
      IndexedWalCodec.encodeMutationBatchEntry(
          payload,
          outputOffset,
          mutations.operationAt(index),
          mutations.keyAt(index),
          firstRowId + index,
          mutations.previousRowIdAt(index),
          rowBytes);
      int rowOffset = outputOffset + IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES;
      mutations.copyRowTo(index, payload, rowOffset);
      walCopyBytes += rowBytes;
      outputOffset = rowOffset + rowBytes;
    }
    return finishPreparedAppend(
        transactionId, commitSequence, operationBytes, mutations.count(), firstRowId, result);
  }

  private StatusCode finishPreparedAppend(
      long transactionId,
      long commitSequence,
      int operationBytes,
      int mutationCount,
      int firstRowId,
      HeapInsertResult result) {
    ByteBuffer payload = walReservation.writablePayload();
    payload.position(operationBytes);
    StatusCode status = wal.appendUnforced(
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
        status = recovery.applyOperation(
            preparedRecordStarts[index],
            walReadResult,
            walGeneration,
            lastCommitSequence);
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
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (transactionId <= 0
        || commitSequence <= lastCommitSequence
        || mutations == null
        || mutations.count() <= 0
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    if (phase.operationActive()
        || phase.preparedInsertGroupActive()
        || !pages.isPresent(HEAP_PAGE_ID)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = validatePendingMutationBatch(mutations);
    if (!status.isOk()) {
      return status;
    }
    if (!kernel.canAppendRows(mutations)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = pendingMutationOperationBytes(mutations);
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    int firstRowId = kernel.rowCount() + 1;
    encodePendingMutationBatch(
        mutations, recordPayload, firstRowId, operationBytes);
    status = publishMutationBatch(transactionId, commitSequence, recordPayload);
    if (!status.isOk()) {
      return status;
    }
    lastCommitSequence = commitSequence;
    result.setRowId(firstRowId + mutations.count() - 1);
    return StatusCode.OK;
  }

  private StatusCode validatePendingMutationBatch(PendingMutationBuffer mutations) {
    for (int index = 0; index < mutations.count(); index++) {
      StatusCode status = validatePendingMutation(mutations, index);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode validatePendingMutation(
      PendingMutationBuffer mutations,
      int index) {
    int operation = mutations.operationAt(index);
    long key = mutations.keyAt(index);
    int previousRowId = mutations.previousRowIdAt(index);
    int rowBytes = mutations.rowLengthAt(index);
    if (!validMutationOperation(operation)
        || key == Long.MAX_VALUE
        || rowBytes <= 0
        || rowBytes > mutations.rowStride()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int previous = 0; previous < index; previous++) {
      if (mutations.keyAt(previous) == key) {
        return StatusCode.CONFLICT;
      }
    }
    int leafPageId = kernel.findLeafPageId(key);
    int earlierInLeaf = earlierPendingInsertsInLeaf(
        mutations, operation, previousRowId, leafPageId, index);
    return kernel.validateMutationTargetAt(
        leafPageId, operation, key, previousRowId, earlierInLeaf);
  }

  private int earlierPendingInsertsInLeaf(
      PendingMutationBuffer mutations,
      int operation,
      int previousRowId,
      int leafPageId,
      int index) {
    if (operation != IndexedWalCodec.MUTATION_INSERT || previousRowId != 0) {
      return 0;
    }
    int count = 0;
    for (int previous = 0; previous < index; previous++) {
      if (mutations.operationAt(previous) == IndexedWalCodec.MUTATION_INSERT
          && mutations.previousRowIdAt(previous) == 0
          && kernel.findLeafPageId(mutations.keyAt(previous)) == leafPageId) {
        count++;
      }
    }
    return count;
  }

  private static int pendingMutationOperationBytes(PendingMutationBuffer mutations) {
    int operationBytes = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutations.count(); index++) {
      operationBytes += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES
          + mutations.rowLengthAt(index);
    }
    return operationBytes;
  }

  private void encodePendingMutationBatch(
      PendingMutationBuffer mutations,
      ByteBuffer payload,
      int firstRowId,
      int operationBytes) {
    IndexedWalCodec.encodeMutationBatchHeader(payload, mutations.count());
    int outputOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutations.count(); index++) {
      int rowBytes = mutations.rowLengthAt(index);
      IndexedWalCodec.encodeMutationBatchEntry(
          payload,
          outputOffset,
          mutations.operationAt(index),
          mutations.keyAt(index),
          firstRowId + index,
          mutations.previousRowIdAt(index),
          rowBytes);
      int rowOffset = outputOffset + IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES;
      mutations.copyRowTo(index, payload, rowOffset);
      walCopyBytes += rowBytes;
      outputOffset = rowOffset + rowBytes;
    }
    payload.position(operationBytes);
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
    status = validateRawMutationBatch(
        operations,
        keys,
        expectedPreviousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount);
    if (!status.isOk()) {
      return status;
    }
    if (!canAppendRows(rowLengths, mutationCount)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int operationBytes = rawMutationOperationBytes(rowLengths, mutationCount);
    status = wal.reserve(operationBytes, walReservation);
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer recordPayload = walReservation.writablePayload();
    int firstRowId = kernel.rowCount() + 1;
    encodeRawMutationBatch(
        recordPayload,
        operations,
        keys,
        expectedPreviousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        firstRowId,
        operationBytes);
    status = publishMutationBatch(transactionId, commitSequence, recordPayload);
    if (!status.isOk()) {
      return status;
    }
    lastCommitSequence = commitSequence;
    result.setRowId(firstRowId + mutationCount - 1);
    return StatusCode.OK;
  }

  private StatusCode validateRawMutationBatch(
      int[] operations,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount) {
    for (int index = 0; index < mutationCount; index++) {
      StatusCode status = validateRawMutation(
          operations,
          keys,
          previousRowIds,
          rows,
          rowStride,
          rowLengths,
          index);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode validateRawMutation(
      int[] operations,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int index) {
    int operation = operations[index];
    long key = keys[index];
    int previousRowId = previousRowIds[index];
    int rowBytes = rowLengths[index];
    int rowOffset = index * rowStride;
    if (!validMutationOperation(operation)
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
    int earlierInLeaf = earlierRawInsertsInLeaf(
        operations, keys, previousRowIds, operation, previousRowId, leafPageId, index);
    return kernel.validateMutationTargetAt(
        leafPageId, operation, key, previousRowId, earlierInLeaf);
  }

  private static boolean validMutationOperation(int operation) {
    return operation == IndexedWalCodec.MUTATION_INSERT
        || operation == IndexedWalCodec.MUTATION_UPDATE
        || operation == IndexedWalCodec.MUTATION_DELETE;
  }

  private int earlierRawInsertsInLeaf(
      int[] operations,
      long[] keys,
      int[] previousRowIds,
      int operation,
      int previousRowId,
      int leafPageId,
      int index) {
    if (operation != IndexedWalCodec.MUTATION_INSERT || previousRowId != 0) {
      return 0;
    }
    int count = 0;
    for (int previous = 0; previous < index; previous++) {
      if (operations[previous] == IndexedWalCodec.MUTATION_INSERT
          && previousRowIds[previous] == 0
          && kernel.findLeafPageId(keys[previous]) == leafPageId) {
        count++;
      }
    }
    return count;
  }

  private static int rawMutationOperationBytes(int[] rowLengths, int mutationCount) {
    int operationBytes = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      operationBytes += IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES + rowLengths[index];
    }
    return operationBytes;
  }

  private void encodeRawMutationBatch(
      ByteBuffer payload,
      int[] operations,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      int firstRowId,
      int operationBytes) {
    IndexedWalCodec.encodeMutationBatchHeader(payload, mutationCount);
    int outputOffset = IndexedWalCodec.MUTATION_BATCH_HEADER_BYTES;
    for (int index = 0; index < mutationCount; index++) {
      int rowBytes = rowLengths[index];
      IndexedWalCodec.encodeMutationBatchEntry(
          payload,
          outputOffset,
          operations[index],
          keys[index],
          firstRowId + index,
          previousRowIds[index],
          rowBytes);
      int sourceOffset = index * rowStride;
      int rowOffset = outputOffset + IndexedWalCodec.MUTATION_BATCH_ENTRY_BYTES;
      copyRawMutationRow(rows, sourceOffset, payload, rowOffset, rowBytes);
      outputOffset = rowOffset + rowBytes;
    }
    payload.position(operationBytes);
  }

  private void copyRawMutationRow(
      ByteBuffer rows,
      int sourceOffset,
      ByteBuffer payload,
      int rowOffset,
      int rowBytes) {
    for (int byteIndex = 0; byteIndex < rowBytes; byteIndex++) {
      payload.put(rowOffset + byteIndex, rows.get(sourceOffset + byteIndex));
    }
    walCopyBytes += rowBytes;
  }

  private StatusCode publishMutationBatch(
      long transactionId,
      long commitSequence,
      ByteBuffer payload) {
    StatusCode status = wal.publish(
        walReservation,
        transactionId,
        commitSequence,
        1,
        WAL_FORMAT_ID,
        WAL_FORMAT_VERSION,
        walAppendResult);
    if (status.isOk()) {
      status = kernel.applyMutationBatchOperation(
          payload,
          walAppendResult.startOffset(),
          walAppendResult.endOffset(),
          commitSequence);
    }
    if (!status.isOk()) {
      failed = true;
    }
    return status;
  }

  /** Rewrites retained heads as one forced, multi-record WAL-atomic compaction batch. */
  StatusCode commitVacuum(
      long transactionId,
      long commitSequence,
      io.riverdb.engine.table.IndexedVacuumResult result) {
    if (!validVacuumRequest(transactionId, commitSequence, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (status.isOk()) {
      status = vacuumStatus();
    }
    if (status.isOk()) {
      status = vacuumWriter.commit(
          transactionId,
          commitSequence,
          lastCommitSequence,
          walGeneration,
          result);
    }
    if (!status.isOk() && vacuumWriter.failureFences()) {
      failed = true;
    }
    if (status.isOk()) {
      lastCommitSequence = commitSequence;
    }
    return status;
  }

  private boolean validVacuumRequest(
      long transactionId,
      long commitSequence,
      IndexedVacuumResult result) {
    return transactionId > 0
        && commitSequence > lastCommitSequence
        && result != null;
  }

  /** Checks whether the current quiescent compaction fits one bounded WAL append batch. */
  StatusCode vacuumPreflight() {
    StatusCode status = admission();
    return status.isOk() ? vacuumStatus() : status;
  }

  private StatusCode vacuumStatus() {
    if (phase.operationActive()
        || phase.preparedInsertGroupActive()
        || !pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int retainedRows = kernel.indexedEntryCount();
    if (retainedRows < 0 || retainedRows > kernel.rowCount()) {
      return StatusCode.CORRUPTION;
    }
    if (retainedRows == kernel.rowCount()) {
      return StatusCode.CONFLICT;
    }
    int chunkCount = kernel.vacuumChunkCount();
    if (chunkCount < 0) {
      return StatusCode.CORRUPTION;
    }
    return chunkCount > 0 && chunkCount < LocalWal.MAX_PENDING_RECORDS
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  StatusCode cancelOperation() {
    if (!phase.operationActive()) {
      return StatusCode.CONFLICT;
    }
    if (phase.vacuumOperationActive()) {
      recovery.cancelVacuumOperation();
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
    status = writeDirtyPages();
    if (!status.isOk()) {
      return status;
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
      markAllPagesClean();
    }
    return status;
  }

  private StatusCode writeDirtyPages() {
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isDirty(pageId)) {
        continue;
      }
      StatusCode status = encodeCurrentPage(
          pageId, pages.recordStart(pageId), pages.recordEnd(pageId));
      if (status.isOk()) {
        status = pages.writeCurrent(
            file, pageId, (long) (pageId - 1) * PageCodec.PAGE_BYTES, ioResult);
      }
      if (!status.isOk() || ioResult.bytesTransferred() != PageCodec.PAGE_BYTES) {
        return status.isOk() ? StatusCode.IO_FAILURE : status;
      }
    }
    return StatusCode.OK;
  }

  private void markAllPagesClean() {
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      pages.markClean(pageId);
    }
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
    status = checkpointWriter.write(nextGeneration);
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
    return walCopyBytes + vacuumWriter.copiedBytes();
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

  StatusCode recoverFromWal() {
    StatusCode status = recovery.recover(walGeneration, baseLoaded, lastCommitSequence);
    if (status.isOk()) {
      lastCommitSequence = recovery.recoveredCommitSequence();
    }
    return status;
  }

  StatusCode loadCheckpoint(CheckpointState checkpoint) {
    StatusCode status = checkpointLoader.load(checkpoint, walGeneration);
    if (!status.isOk()) {
      return status;
    }
    lastCommitSequence = checkpoint.commitSequence();
    baseLoaded = true;
    return StatusCode.OK;
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

  void closeOpenFile() {
    file.close();
  }

}
