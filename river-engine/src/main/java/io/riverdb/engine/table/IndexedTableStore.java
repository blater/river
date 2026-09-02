package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.wal.local.LocalWal;
import java.nio.ByteBuffer;

/** Single-owner bounded page store whose WAL operations atomically cover heap and index state. */
public final class IndexedTableStore extends IndexedRelationalStoreAccess {
  public static final String FILE_NAME = "river.indexed.pages";
  public static final String ROW_DIRECTORY_FILE_NAME = IndexedRowDirectory.FILE_NAME;
  public static final String VERSION_DIRECTORY_FILE_NAME = IndexedVersionDirectory.FILE_NAME;
  public static final int WAL_FORMAT_ID = IndexedWalCodec.FORMAT_ID;
  public static final int WAL_FORMAT_VERSION = IndexedWalCodec.FORMAT_VERSION;
  public static final int MAX_PAGES = IndexedTableLimits.MAX_PAGES;
  public static final int MAX_CHANGED_PAGES = IndexedTableLimits.MAX_CHANGED_PAGES;
  public static final int VACUUM_COMMIT_PAYLOAD_BYTES =
      IndexedWalCodec.VACUUM_COMMIT_PAYLOAD_BYTES;

  private static final long BOOTSTRAP_TRANSACTION_ID = 1;
  static final int MAX_OPERATION_ROWS = IndexedTableLimits.MAX_OPERATION_ROWS;

  private final DurableFile file;
  private final LocalWal wal;
  private final DatabaseIncarnation database;
  private final IndexedTableKernel kernel;
  private final IndexedCheckpointCoordinator checkpoints;
  private final IndexedWalRecovery recovery;
  private final IndexedPageOperationCommitter pageCommitter;
  private final IndexedPageSet pages;
  private final IndexedLogicalRowIdRegistry logicalRowIds = new IndexedLogicalRowIdRegistry();
  final IndexedStorePhase phase = new IndexedStorePhase();
  boolean failed;
  private boolean closed;
  private boolean baseLoaded;
  volatile long lastCommitSequence;

  IndexedTableStore(
      DurableDirectory durableDirectory,
      DurableFile durableFile,
      DurableFile rowDirectoryFile,
      DurableFile versionDirectoryFile,
      LocalWal localWal,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration generation) {
    this(
        durableDirectory, durableFile, rowDirectoryFile, versionDirectoryFile,
        localWal, databaseIncarnation, generation, IndexedPageCacheConfig.DEFAULT);
  }

  IndexedTableStore(
      DurableDirectory durableDirectory,
      DurableFile durableFile,
      DurableFile rowDirectoryFile,
      DurableFile versionDirectoryFile,
      LocalWal localWal,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration generation,
      IndexedPageCacheConfig pageCacheConfig) {
    file = durableFile;
    wal = localWal;
    database = databaseIncarnation;
    pages = new IndexedPageSet(
        file, versionDirectoryFile, database, generation, pageCacheConfig);
    kernel = pages.createKernel(rowDirectoryFile, versionDirectoryFile);
    checkpoints = new IndexedCheckpointCoordinator(
        durableDirectory, file, wal, kernel, pages,
        kernel.versionState(), database, phase, generation, logicalRowIds);
    recovery = new IndexedWalRecovery(wal, pages, kernel, database, phase, logicalRowIds);
    initializeRelationalServices(
        this, kernel, wal, pages, phase, recovery, logicalRowIds);
    pageCommitter = new IndexedPageOperationCommitter(wal, kernel, pages, phase, database);
  }

  StatusCode initialize() {
    StatusCode status = beginBootstrap();
    if (!status.isOk()) {
      return status;
    }
    status = kernel.initializePages();
    if (status.isOk()) {
      status = commitBootstrap();
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

  StatusCode admitLogicalRowIds(long objectId, long publishedFloor) {
    return logicalRowIds.admit(objectId, publishedFloor);
  }

  StatusCode reserveLogicalRowIds(
      long objectId, int count, IndexedLogicalRowIdReservation result) {
    return logicalRowIds.reserve(objectId, count, result);
  }

  StatusCode preflightHybridGroup(
      IndexedTransactionSession[] sessions, int count,
      long oldestVisibleCommitSequence) {
    return relationalServices().preflightHybridGroup(
        sessions, count, oldestVisibleCommitSequence);
  }

  StatusCode appendHybridGroup(
      IndexedTransactionSession[] sessions, long[] commitSequences, int count) {
    return relationalServices().appendHybridGroup(sessions, commitSequences, count);
  }

  StatusCode forceHybridGroup() {
    return relationalServices().forceHybridGroup();
  }

  StatusCode cancelCommitGroup() {
    return relationalServices().cancelHybridGroup();
  }

  boolean commitGroupDecisionAppended() {
    return relationalServices().hybridDecisionAppended();
  }

  StatusCode prepareForcedGroupPublication() {
    return relationalServices().prepareHybridGroupPublication();
  }

  StatusCode installPreparedGroupPublication() {
    return relationalServices().installHybridGroupPublication();
  }


  StatusCode vacuum(long transactionId, IndexedVacuumResult result) {
    if (transactionId <= BOOTSTRAP_TRANSACTION_ID || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return commitVacuum(transactionId, nextCommitSequence(), result);
  }



  StatusCode fetchByKey(
      long space, long key, io.riverdb.storage.heap.HeapRowResult result) {
    return kernel.fetchByKeyAt(lastCommitSequence, space, key, result);
  }

  StatusCode fetchByKeyAt(
      long visibleCommitSequence,
      long space,
      long key,
      io.riverdb.storage.heap.HeapRowResult result) {
    return kernel.fetchByKeyAt(visibleCommitSequence, space, key, result);
  }

  StatusCode fetchVersionedByKeyAt(
      long visibleCommitSequence, long space, long key,
      IndexedVersionedRowResult result) {
    return kernel.fetchVersionedByKeyAt(visibleCommitSequence, space, key, result);
  }

  StatusCode fetchCurrentSuccessor(
      long space, long key, long candidateRowId, IndexedVersionedRowResult result) {
    return kernel.fetchCurrentSuccessor(space, key, candidateRowId, result);
  }

  int firstLeafPageIdAt(long visibleCommitSequence, long space, long lowerKey) {
    return kernel.findLeafPageIdAt(visibleCommitSequence, space, lowerKey);
  }

  StatusCode snapshotLookupStatus() { return kernel.snapshotLookupStatus(); }

  StatusCode nextScan(IndexedScanCursor cursor, IndexedScanResult result) {
    return kernel.nextScan(cursor, result);
  }

  StatusCode prepareMutation(
      long visibleCommitSequence,
      long space,
      long key,
      IndexedMutationTarget result) {
    return kernel.prepareMutation(visibleCommitSequence, space, key, result);
  }

  StatusCode prepareInsert(
      long visibleCommitSequence,
      long space,
      long key,
      IndexedMutationTarget result) {
    return kernel.prepareInsert(visibleCommitSequence, space, key, result);
  }

  int rootPageId() {
    return kernel.rootPageId();
  }

  int nextPageId() {
    return kernel.nextPageId();
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

  private StatusCode beginBootstrap() {
    StatusCode status = admission();
    return status.isOk() ? pageCommitter.beginBootstrap() : status;
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
    return kernel.remainingVersionCapacity();
  }

  private StatusCode commitBootstrap() {
    long commitSequence = nextCommitSequence();
    StatusCode status = pageCommitter.commitBootstrap(
        commitSequence, checkpoints.generation());
    if (!status.isOk()) return cancelSafePageFailure(status);
    return published(status, commitSequence);
  }

  private StatusCode cancelSafePageFailure(StatusCode status) {
    if (!pageCommitter.failed() && phase.operationActive()) cancelOperation();
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
      status = relationalServices().commitVacuum(
          transactionId,
          commitSequence,
          lastCommitSequence,
          checkpoints.generation(),
          result);
    }
    if (!status.isOk() && relationalServices().vacuumFailureFences()) {
      failed = true;
    }
    return published(status, commitSequence);
  }

  private StatusCode published(StatusCode status, long commitSequence) {
    if (status.isOk()) lastCommitSequence = commitSequence;
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
    return status.isOk() ? relationalServices().vacuumStatus() : status;
  }

  StatusCode cancelOperation() {
    if (!phase.operationActive()) {
      return StatusCode.CONFLICT;
    }
    if (phase.vacuumOperationActive()) {
      return recovery.cancelVacuumOperation();
    }
    clearStagedFlags();
    pages.cancelPreparedBatch();
    phase.reset();
    pages.resetChanges();
    kernel.clearOperationVersions();
    return StatusCode.OK;
  }

  StatusCode flush() {
    StatusCode status = validatedAdmission();
    return status.isOk() ? checkpoints.flush() : status;
  }

  /** Forces an immutable zero-suffix page base in the next WAL lineage. */
  public StatusCode rebaseForCheckpoint(WalGeneration nextGeneration) {
    StatusCode status = validatedAdmission();
    return status.isOk() ? checkpoints.rebase(nextGeneration) : status;
  }

  public StatusCode captureCheckpointState(
      CheckpointState state,
      long checkpointId,
      long maximumTransactionId) {
    StatusCode status = validatedAdmission();
    return status.isOk()
        ? checkpoints.capture(state, checkpointId, maximumTransactionId) : status;
  }

  long stagedCopyBytes() {
    return pages.stagedCopyBytes();
  }

  long walCopyBytes() {
    return pageCommitter.copiedBytes()
        + relationalServices().walCopiedPayloadBytes()
        + relationalServices().vacuumCopiedBytes();
  }

  long relationalCompilationCopyBytes() {
    return relationalServices().compilationCopiedPayloadBytes();
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

  StatusCode readVersion(long rowId, IndexedVersionRecord result) {
    return kernel.readVersion(rowId, result);
  }

  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (phase.operationActive() || phase.commitGroupActive() || hasDirtyPages()) {
      return StatusCode.CONFLICT;
    }
    closed = true;
    kernel.closeCheckpointVersions();
    StatusCode sidecarClose = kernel.closeSidecars();
    StatusCode fileClose = file.close();
    return sidecarClose.isOk() ? fileClose : sidecarClose;
  }

  StatusCode recoverFromWal() {
    StatusCode status = recovery.recover(
        checkpoints.generation(), baseLoaded, lastCommitSequence);
    if (status.isOk()) status = kernel.validate();
    if (status.isOk()) {
      lastCommitSequence = recovery.recoveredCommitSequence();
    }
    return status;
  }

  WalGeneration walGeneration() { return checkpoints.generation(); }

  StatusCode loadCheckpoint(CheckpointState checkpoint) {
    StatusCode status = loadLogicalRowIdFloors(checkpoint);
    if (status.isOk()) status = checkpoints.load(checkpoint);
    if (!status.isOk()) {
      return status;
    }
    lastCommitSequence = checkpoint.commitSequence();
    baseLoaded = true;
    return StatusCode.OK;
  }

  private StatusCode loadLogicalRowIdFloors(CheckpointState checkpoint) {
    if (checkpoint == null || checkpoint.logicalRowIdSource() == null) {
      return StatusCode.CORRUPTION;
    }
    io.riverdb.engine.checkpoint.CheckpointLogicalRowIdSource source =
        checkpoint.logicalRowIdSource();
    source.rewind();
    for (int index = 0; index < source.floorCount(); index++) {
      long objectId = source.nextObjectId();
      long floor = source.nextExclusive();
      StatusCode status = logicalRowIds.load(objectId, floor);
      if (!status.isOk()) return status == StatusCode.INVALID_EXTERNAL_INPUT
          ? StatusCode.CORRUPTION : status;
    }
    return source.nextObjectId() == -1 ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private void clearStagedFlags() {
    pages.clearStagedFlags();
  }

  private boolean hasDirtyPages() {
    return pages.hasDirtyPages() || kernel.sidecarsDirty();
  }

  StatusCode admission() {
    return IndexedTableAdmission.status(
        kernel.rowDirectoryStatus(),
        kernel.versionDirectoryStatus(),
        failed,
        pageCommitter.failed(),
        checkpoints.failed(),
        closed);
  }

  @Override
  StatusCode relationalAdmission() { return admission(); }

  private StatusCode validatedAdmission() {
    StatusCode status = admission();
    return status.isOk() ? kernel.validate() : status;
  }

  void closeOpenFile() {
    kernel.closeSidecars();
    pages.closeStagingFile();
    file.close();
  }

}
