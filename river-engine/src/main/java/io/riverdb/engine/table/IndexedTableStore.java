package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.storage.btree.BTreeLookupResult;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.wal.local.LocalWal;
import java.nio.ByteBuffer;

/** Single-owner bounded page store whose WAL operations atomically cover heap and index state. */
public final class IndexedTableStore {
  public static final String FILE_NAME = "river.indexed.pages";
  public static final int WAL_FORMAT_ID = IndexedWalCodec.FORMAT_ID;
  public static final int WAL_FORMAT_VERSION = IndexedWalCodec.FORMAT_VERSION;
  public static final int MAX_PAGES = IndexedTableLimits.MAX_PAGES;
  public static final int MAX_CHANGED_PAGES = IndexedTableLimits.MAX_CHANGED_PAGES;
  /** Legacy int-typed admission hint retained for source compatibility. */
  public static final int MAX_ROWS = Integer.MAX_VALUE - 1;
  /** Full logical row identity domain supported by indexed tables. */
  public static final long MAX_LOGICAL_ROWS = IndexedTableLimits.MAX_ROWS;
  public static final int VACUUM_COMMIT_PAYLOAD_BYTES =
      IndexedWalCodec.VACUUM_COMMIT_PAYLOAD_BYTES;

  private static final int HEAP_PAGE_ID = IndexedTableKernel.HEAP_PAGE_ID;
  private static final int ROOT_META_PAGE_ID = IndexedTableKernel.ROOT_META_PAGE_ID;
  private static final long BOOTSTRAP_TRANSACTION_ID = 1;
  static final int MAX_OPERATION_ROWS = IndexedTableLimits.MAX_OPERATION_ROWS;

  private final DurableFile file;
  private final LocalWal wal;
  private final DatabaseIncarnation database;
  private final IndexedTableKernel kernel;
  private final IndexedCheckpointCoordinator checkpoints;
  private final IndexedWalRecovery recovery;
  private final IndexedTableCommitCoordinator commits;
  private final IndexedPageOperationCommitter pageCommitter;
  private final IndexedLogicalCommitter logicalCommitter;
  private final IndexedPreparedCommitGroup preparedGroup;
  private final IndexedVacuumCoordinator vacuumCoordinator;
  private final IndexedPageSet pages = new IndexedPageSet();
  private final IndexedStorePhase phase = new IndexedStorePhase();
  private boolean failed;
  private boolean closed;
  private boolean baseLoaded;
  private volatile long lastCommitSequence;

  IndexedTableStore(
      DurableDirectory durableDirectory,
      DurableFile durableFile,
      LocalWal localWal,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration generation) {
    file = durableFile;
    wal = localWal;
    database = databaseIncarnation;
    kernel = new IndexedTableKernel(pages);
    checkpoints = new IndexedCheckpointCoordinator(
        durableDirectory, file, wal, kernel, pages, database, phase, generation);
    recovery = new IndexedWalRecovery(wal, pages, kernel, database, phase);
    commits = new IndexedTableCommitCoordinator(this, kernel);
    pageCommitter = new IndexedPageOperationCommitter(wal, kernel, pages, phase, database);
    logicalCommitter = new IndexedLogicalCommitter(wal, kernel, pages, phase);
    preparedGroup = new IndexedPreparedCommitGroup(wal, kernel, recovery, phase);
    vacuumCoordinator = new IndexedVacuumCoordinator(wal, kernel, pages, phase, recovery);
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
      int space,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    return commits.insert(transactionId, space, key, row, result);
  }

  StatusCode commitInsert(
      long transactionId,
      int space,
      long key,
      ByteBuffer row,
      IndexedCommitResult result) {
    return commits.commitInsert(transactionId, space, key, row, result);
  }

  StatusCode commitInserts(
      long transactionId,
      int[] spaces,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      IndexedCommitResult result) {
    return commits.commitInserts(
        transactionId, spaces, keys, rows, rowStride, rowLengths, insertCount, result);
  }

  StatusCode commitMutations(
      long transactionId,
      int[] operations,
      int[] spaces,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      IndexedCommitResult result) {
    return commits.commitMutations(
        transactionId,
        operations,
        spaces,
        keys,
        previousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        result);
  }

  StatusCode commitMutations(
      long transactionId,
      PendingMutationBuffer mutations,
      IndexedCommitResult result) {
    return commits.commitMutations(transactionId, mutations, result);
  }



  StatusCode appendPreparedWrites(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    return preparedGroup.append(transactionId, commitSequence, mutations, result);
  }

  StatusCode cancelPreparedInsertGroup() {
    return cancelPreparedInsertPreflight();
  }

  StatusCode publishForcedGroup() {
    return publishForcedInserts();
  }

  StatusCode vacuum(long transactionId, IndexedVacuumResult result) {
    return commits.vacuum(transactionId, result);
  }

  StatusCode insertCommitted(
      long transactionId,
      long commitSequence,
      int space,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    return commits.insertCommitted(
        transactionId, commitSequence, space, key, row, result);
  }



  StatusCode fetchByKey(
      int space, long key, io.riverdb.storage.heap.HeapRowResult result) {
    return kernel.fetchByKeyAt(lastCommitSequence, space, key, result);
  }

  StatusCode fetchByKeyAt(
      long visibleCommitSequence,
      int space,
      long key,
      io.riverdb.storage.heap.HeapRowResult result) {
    return kernel.fetchByKeyAt(visibleCommitSequence, space, key, result);
  }

  int firstLeafPageId(int space, long lowerKey) {
    return kernel.findLeafPageId(space, lowerKey);
  }

  StatusCode nextScan(IndexedScanCursor cursor, IndexedScanResult result) {
    return kernel.nextScan(cursor, result);
  }

  StatusCode prepareMutation(
      long visibleCommitSequence,
      int space,
      long key,
      IndexedMutationTarget result) {
    return kernel.prepareMutation(visibleCommitSequence, space, key, result);
  }

  StatusCode prepareInsert(
      long visibleCommitSequence,
      int space,
      long key,
      IndexedMutationTarget result) {
    return kernel.prepareInsert(visibleCommitSequence, space, key, result);
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
    return status.isOk() ? pageCommitter.begin() : status;
  }

  StatusCode fetchRow(long rowId, io.riverdb.storage.heap.HeapRowResult result) {
    return kernel.fetchRow(rowId, result);
  }

  int rowLength(long rowId) {
    return kernel.rowLength(rowId);
  }

  StatusCode copyRowTo(long rowId, ByteBuffer destination, int destinationOffset) {
    return kernel.copyRowTo(rowId, destination, destinationOffset);
  }

  long rowCount() {
    return kernel.rowCount();
  }

  /** Returns the number of superseded heap versions in constant time. */
  int obsoleteVersionCount() {
    return kernel.obsoleteVersionCount();
  }

  long remainingVersionCapacity() {
    return MAX_LOGICAL_ROWS - kernel.rowCount();
  }

  StatusCode commit(long transactionId, long commitSequence) {
    StatusCode status = pageCommitter.commit(
        transactionId, commitSequence, lastCommitSequence, checkpoints.generation());
    if (status.isOk()) {
      lastCommitSequence = commitSequence;
    }
    return status;
  }

  /** Commits the common no-split heap/index insert as a compact logical operation. */
  StatusCode commitInsert(
      long transactionId,
      long commitSequence,
      int space,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    if (!IndexedLogicalRequestValidator.validInsert(
        transactionId, commitSequence, lastCommitSequence,
        space, key, row, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    status = logicalCommitter.commitInsert(
        transactionId, commitSequence, space, key, row, result);
    if (status.isOk()) {
      lastCommitSequence = commitSequence;
    }
    return status;
  }

  /** Commits multiple non-splitting inserts as one compact logical WAL record. */
  StatusCode commitInsertBatch(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (!IndexedLogicalRequestValidator.validPending(
        transactionId, commitSequence, lastCommitSequence, mutations, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    status = logicalCommitter.commitInsertBatch(
        transactionId, commitSequence, mutations, result);
    if (status.isOk()) {
      lastCommitSequence = commitSequence;
    }
    return status;
  }

  /** Commits multiple non-splitting inserts as one compact logical WAL record. */
  StatusCode commitInsertBatch(
      long transactionId,
      long commitSequence,
      int[] spaces,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      HeapInsertResult result) {
    if (!IndexedLogicalRequestValidator.validRawInsert(
        transactionId,
        commitSequence,
        lastCommitSequence,
        spaces,
        keys,
        rows,
        rowStride,
        rowLengths,
        insertCount,
        result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    status = logicalCommitter.commitInsertBatch(
        transactionId,
        commitSequence,
        spaces,
        keys,
        rows,
        rowStride,
        rowLengths,
        insertCount,
        result);
    if (status.isOk()) {
      lastCommitSequence = commitSequence;
    }
    return status;
  }

  /** Starts bounded validation for a group of independent insert-only transactions. */
  StatusCode beginPreparedInsertGroup() {
    StatusCode status = admission();
    return status.isOk() ? preparedGroup.begin() : status;
  }

  StatusCode preflightPreparedWrites(PendingMutationBuffer mutations) {
    return preparedGroup.preflight(mutations);
  }

  StatusCode finishPreparedInsertPreflight(int transactionCount) {
    return preparedGroup.finishPreflight(transactionCount);
  }

  /** Forces every prepared insert transaction without publishing any page or index state. */
  StatusCode forcePreparedInserts() {
    return preparedGroup.force();
  }

  /** Publishes an already-forced insert group in commit order. */
  StatusCode publishForcedInserts() {
    StatusCode status = preparedGroup.publish(checkpoints.generation(), lastCommitSequence);
    if (status.isOk()) {
      lastCommitSequence = preparedGroup.publishedCommitSequence();
    }
    return status;
  }

  StatusCode cancelPreparedInsertPreflight() {
    return preparedGroup.cancel();
  }

  /** Commits a compact atomic mix of inserts, updates, and tombstone deletes. */
  StatusCode commitMutationBatch(
      long transactionId,
      long commitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    if (!IndexedLogicalRequestValidator.validPending(
        transactionId, commitSequence, lastCommitSequence, mutations, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    status = logicalCommitter.commitMutations(
        transactionId, commitSequence, mutations, result);
    if (status.isOk()) {
      lastCommitSequence = commitSequence;
    }
    return status;
  }

  /** Commits a compact atomic mix of inserts, updates, and tombstone deletes. */
  StatusCode commitMutationBatch(
      long transactionId,
      long commitSequence,
      int[] operations,
      int[] spaces,
      long[] keys,
      int[] expectedPreviousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      HeapInsertResult result) {
    if (!IndexedLogicalRequestValidator.validRawMutation(
        transactionId,
        commitSequence,
        lastCommitSequence,
        operations,
        spaces,
        keys,
        expectedPreviousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = admission();
    if (!status.isOk()) {
      return status;
    }
    status = logicalCommitter.commitMutations(
        transactionId,
        commitSequence,
        operations,
        spaces,
        keys,
        expectedPreviousRowIds,
        rows,
        rowStride,
        rowLengths,
        mutationCount,
        result);
    if (status.isOk()) {
      lastCommitSequence = commitSequence;
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
      status = vacuumCoordinator.commit(
          transactionId,
          commitSequence,
          lastCommitSequence,
          checkpoints.generation(),
          result);
    }
    if (!status.isOk() && vacuumCoordinator.failureFences()) {
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
    return status.isOk() ? vacuumCoordinator.status() : status;
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
    return status.isOk() ? checkpoints.flush() : status;
  }

  /** Forces an immutable zero-suffix page base in the next WAL lineage. */
  public StatusCode rebaseForCheckpoint(WalGeneration nextGeneration) {
    StatusCode status = admission();
    return status.isOk() ? checkpoints.rebase(nextGeneration) : status;
  }

  public StatusCode captureCheckpointState(
      CheckpointState state,
      long checkpointId,
      long maximumTransactionId) {
    return checkpoints.capture(state, checkpointId, maximumTransactionId);
  }

  long stagedCopyBytes() {
    return pages.stagedCopyBytes();
  }

  long walCopyBytes() {
    return pageCommitter.copiedBytes()
        + logicalCommitter.walCopyBytes()
        + preparedGroup.walCopyBytes()
        + vacuumCoordinator.copiedBytes();
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

  long previousRowId(long rowId) {
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
    StatusCode status = recovery.recover(
        checkpoints.generation(), baseLoaded, lastCommitSequence);
    if (status.isOk()) {
      lastCommitSequence = recovery.recoveredCommitSequence();
    }
    return status;
  }

  StatusCode loadCheckpoint(CheckpointState checkpoint) {
    StatusCode status = checkpoints.load(checkpoint);
    if (!status.isOk()) {
      return status;
    }
    lastCommitSequence = checkpoint.commitSequence();
    baseLoaded = true;
    return StatusCode.OK;
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

  private boolean addChangedPage(int pageId) {
    int maximumChangedPages = phase.vacuumOperationActive() ? MAX_PAGES : MAX_CHANGED_PAGES;
    return pages.addChangedPage(pageId, maximumChangedPages);
  }

  private void clearStagedFlags() {
    pages.clearStagedFlags();
  }

  private boolean validPresentPage(int pageId) {
    return pages.validPresentPage(pageId);
  }

  private boolean hasDirtyPages() {
    return pages.hasDirtyPages();
  }

  private StatusCode admission() {
    if (failed
        || pageCommitter.failed()
        || logicalCommitter.failed()
        || checkpoints.failed()
        || preparedGroup.failed()) {
      return StatusCode.FENCED;
    }
    return closed ? StatusCode.CLOSED : StatusCode.OK;
  }

  void closeOpenFile() {
    file.close();
  }

}
