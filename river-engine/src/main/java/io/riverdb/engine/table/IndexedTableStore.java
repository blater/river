package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.engine.runtime.DatabaseProviderLease;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.engine.runtime.DatabaseStoreLease;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalMetrics;
import io.riverdb.tx.TransactionManager;

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
  private final DurableFile file;
  private final LocalWal wal;
  private final DatabaseIncarnation database;
  final IndexedTableKernel kernel;
  private final IndexedCheckpointCoordinator checkpoints;
  private final IndexedGroupCommitMetrics commitMetrics = new IndexedGroupCommitMetrics();
  private final IndexedWalRecovery recovery;
  private final IndexedPageOperationCommitter pageCommitter;
  final IndexedPageSet pages;
  private final DatabaseProviderLease providerLease;
  private final DatabaseStoreLease storeLease;
  private final IndexedDurableVersionAdmission durableVersions =
      new IndexedDurableVersionAdmission();
  private final IndexedLogicalRowIdRegistry logicalRowIds = new IndexedLogicalRowIdRegistry();
  final IndexedStorePhase phase = new IndexedStorePhase();
  boolean failed;
  private boolean closing;
  private boolean closed;
  private boolean checkpointVersionsClosed;
  private boolean sidecarsClosed;
  private boolean fileClosed;
  private boolean providerReleased;
  private boolean baseLoaded;
  volatile long lastCommitSequence;
  // First visible CSN whose durability is still owned by the single commit writer.
  long pendingDurabilitySequence;

  IndexedTableStore(
      DurableDirectory durableDirectory,
      DurableFile durableFile,
      DurableFile rowDirectoryFile,
      DurableFile versionDirectoryFile,
      LocalWal localWal,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration generation,
      DatabaseProviderLease databaseProviders,
      DatabaseStoreLease storeOwnership) {
    file = durableFile;
    wal = localWal;
    database = databaseIncarnation;
    providerLease = databaseProviders;
    storeLease = storeOwnership;
    pages = new IndexedPageSet(
        file, versionDirectoryFile, database, generation,
        databaseProviders.plan().indexedPageCache());
    kernel = pages.createKernel(
        rowDirectoryFile, versionDirectoryFile, databaseProviders.plan());
    checkpoints = new IndexedCheckpointCoordinator(
        durableDirectory, file, wal, kernel, pages,
        kernel.versionState(), database, phase, generation, logicalRowIds);
    recovery = new IndexedWalRecovery(wal, pages, kernel, database, phase, logicalRowIds);
    initializeRelationalServices(
        this, kernel, wal, pages, phase, recovery, logicalRowIds, commitMetrics);
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

  StatusCode transactionAdmissionStatus() {
    StatusCode status = admission();
    return status.isOk() ? durableVersions.transactionAdmissionStatus() : status;
  }

  StatusCode admitDurableVersionOperations(int required) {
    return durableVersions.admit(
        kernel.rowCount(), kernel.obsoleteVersionCount(), required);
  }

  StatusCode completeVersionMaintenance() {
    return durableVersions.maintenanceCompleted(
        kernel.rowCount(), kernel.obsoleteVersionCount());
  }

  StatusCode admitLogicalRowIds(long objectId, long publishedFloor) {
    return logicalRowIds.admit(objectId, publishedFloor);
  }

  StatusCode reserveLogicalRowIds(
      long objectId, int count, IndexedLogicalRowIdReservation result) {
    return logicalRowIds.reserve(objectId, count, result);
  }

  StatusCode fenceCommitWriter() {
    StatusCode status = relationalServices().cancelHybridGroup();
    failed = true;
    return status;
  }

  IndexedGroupCommitMetrics commitMetrics() { return commitMetrics; }

  DatabaseResourceGovernor resourceGovernor() { return providerLease.governor(); }

  boolean matches(TransactionManager manager) {
    return manager != null && manager.managesDatabase(database.high(), database.low());
  }

  StatusCode copyCommitMetrics(IndexedGroupCommitTelemetry result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    commitMetrics.copyTo(result);
    return StatusCode.OK;
  }

  StatusCode copyWalMetrics(LocalWalMetrics result) {
    return wal.copyMetrics(result);
  }

  StatusCode beginPerformanceCapture() {
    StatusCode status = commitMetrics.beginCapture();
    if (!status.isOk()) return status;
    status = wal.beginMetricsCapture();
    if (!status.isOk()) commitMetrics.cancelCapture();
    return status;
  }

  StatusCode endPerformanceCapture(
      IndexedGroupCommitTelemetry commitResult,
      LocalWalMetrics walResult) {
    if (commitResult == null || walResult == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode commitStatus = commitMetrics.endCapture(commitResult);
    StatusCode walStatus = wal.endMetricsCapture(walResult);
    return commitStatus.isOk() ? walStatus : commitStatus;
  }

  StatusCode cancelPerformanceCapture() {
    StatusCode commitStatus = commitMetrics.cancelCapture();
    StatusCode walStatus = wal.cancelMetricsCapture();
    return commitStatus.isOk() ? walStatus : commitStatus;
  }

  StatusCode prepareGroupPublication() {
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


  public static StatusCode create(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      DatabaseProviderLease providerLease,
      IndexedTableStoreOpenResult result) {
    return IndexedTableStoreFactory.create(
        directory, wal, database, walGeneration, providerLease, result);
  }

  public static StatusCode open(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      DatabaseProviderLease providerLease,
      IndexedTableStoreOpenResult result) {
    return IndexedTableStoreFactory.open(
        directory, wal, database, walGeneration, providerLease, true, result);
  }

  public static StatusCode openExisting(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      DatabaseProviderLease providerLease,
      IndexedTableStoreOpenResult result) {
    return IndexedTableStoreFactory.open(
        directory, wal, database, walGeneration, providerLease, false, result);
  }

  public static StatusCode openCheckpoint(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      CheckpointState checkpoint,
      DatabaseProviderLease providerLease,
      IndexedTableStoreOpenResult result) {
    return IndexedTableStoreFactory.openCheckpoint(
        directory, wal, database, checkpoint, providerLease, result);
  }

  public static String checkpointFileName(WalGeneration generation) {
    return generation == null || !generation.isValid()
        ? "" : FILE_NAME + ".checkpoint." + generation.value();
  }

  private StatusCode beginBootstrap() {
    StatusCode status = admission();
    return status.isOk() ? pageCommitter.beginBootstrap() : status;
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

  public int activeStagedPageCapacity() {
    return pages.changedPageCapacity();
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

  public synchronized StatusCode close() {
    StatusCode status = beginClose();
    if (!status.isOk()) return status;
    // Attempt every resource even when an earlier close failed; retry only unfinished owners.
    StatusCode versionsStatus = closeCheckpointVersions();
    StatusCode sidecarsStatus = closeSidecars();
    StatusCode fileStatus = closeFile();
    StatusCode providerStatus = releaseProvider();
    closed = checkpointVersionsClosed && sidecarsClosed && fileClosed && providerReleased;
    if (!versionsStatus.isOk()) return versionsStatus;
    if (!sidecarsStatus.isOk()) return sidecarsStatus;
    if (!fileStatus.isOk()) return fileStatus;
    if (!providerStatus.isOk()) return providerStatus;
    return closed ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  private StatusCode beginClose() {
    if (closed) return StatusCode.CLOSED;
    if (closing) return StatusCode.OK;
    if (phase.operationActive() || phase.commitGroupActive() || hasDirtyPages()) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = pages.detach();
    if (status.isOk()) closing = true;
    return status;
  }

  private StatusCode closeCheckpointVersions() {
    if (checkpointVersionsClosed) return StatusCode.OK;
    StatusCode status = kernel.closeCheckpointVersions();
    checkpointVersionsClosed = closeCompleted(status);
    return checkpointVersionsClosed ? StatusCode.OK : status;
  }

  private StatusCode closeSidecars() {
    if (sidecarsClosed) return StatusCode.OK;
    StatusCode status = kernel.closeSidecars();
    sidecarsClosed = closeCompleted(status);
    return sidecarsClosed ? StatusCode.OK : status;
  }

  private StatusCode closeFile() {
    if (fileClosed) return StatusCode.OK;
    StatusCode status = file.close();
    fileClosed = closeCompleted(status);
    return fileClosed ? StatusCode.OK : status;
  }

  private StatusCode releaseProvider() {
    if (providerReleased) return StatusCode.OK;
    StatusCode status = providerLease.releaseStore(storeLease);
    providerReleased = status.isOk();
    return status;
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
        closing || closed);
  }

  @Override
  StatusCode relationalAdmission() { return admission(); }

  private StatusCode validatedAdmission() {
    StatusCode status = admission();
    return status.isOk() ? kernel.validate() : status;
  }

  void closeOpenFile() {
    kernel.closeCheckpointVersions();
    kernel.closeSidecars();
    pages.abandon();
    pages.closeStagingFile();
    file.close();
    if (providerLease.storeClaimed()) providerLease.releaseStore(storeLease);
  }

  private static boolean closeCompleted(StatusCode status) {
    return status.isOk() || status == StatusCode.CLOSED;
  }

}
