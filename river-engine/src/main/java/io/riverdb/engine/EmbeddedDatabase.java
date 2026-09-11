package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointControlStore;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.checkpoint.EmbeddedCheckpoint;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.engine.runtime.DatabaseProviderLease;
import io.riverdb.engine.runtime.DatabaseRetainedLease;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.engine.runtime.RuntimeResourceRoot;
import io.riverdb.engine.table.IndexedGroupCommitCoordinator;
import io.riverdb.engine.table.IndexedGroupCommitTelemetry;
import io.riverdb.engine.table.IndexedSessionContext;
import io.riverdb.engine.table.IndexedSessionRegistry;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedTableStore;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedVacuum;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.tx.LockDeadlockDiagnosticsSnapshot;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalMetrics;
import java.nio.file.Path;

/** Minimal embedded lifecycle over the first durable indexed transaction kernel. */
public final class EmbeddedDatabase {
  private final NioDurableDirectory directory;
  private final NioDurableDirectory[] followerDirectories;
  private final LocalWal wal;
  private final LocalWal[] followerWals;
  private final IndexedTable table;
  private final TransactionManager transactions;
  private final IndexedGroupCommitCoordinator groupCommit;
  private final IndexedVacuum vacuum;
  private final EmbeddedCheckpoint checkpoint;
  private final DatabaseResourceGovernor resourceGovernor;
  private final DatabaseProviderLease providerLease;
  private final DatabaseRetainedLease runtimeCapacityLease = new DatabaseRetainedLease();
  private final IndexedSessionRegistry sessions;
  private final IndexedSessionContext sessionContext;
  private final EmbeddedPerformanceCapture performanceCapture;
  private volatile boolean closing;
  private volatile boolean closed;

  EmbeddedDatabase(
      NioDurableDirectory openedDirectory,
      NioDurableDirectory[] openedFollowerDirectories,
      LocalWal openedWal,
      LocalWal[] openedFollowerWals,
      IndexedTableStore openedStore,
      IndexedTable openedTable,
      TransactionManager transactionManager,
      IndexedSessionContext indexedSessions,
      CheckpointControlStore checkpointControl,
      long checkpointId,
      DatabaseProviderLease databaseProviders) {
    directory = openedDirectory;
    followerDirectories = openedFollowerDirectories;
    wal = openedWal;
    followerWals = openedFollowerWals;
    table = openedTable;
    transactions = transactionManager;
    sessionContext = indexedSessions;
    vacuum = indexedSessions.vacuum();
    checkpoint = new EmbeddedCheckpoint(
        transactions,
        openedDirectory,
        openedWal,
        openedStore,
        openedTable,
        checkpointControl,
        checkpointId);
    resourceGovernor = databaseProviders.governor();
    providerLease = databaseProviders;
    sessions = indexedSessions.registry();
    groupCommit = indexedSessions.groupCommit();
    performanceCapture = new EmbeddedPerformanceCapture(openedTable, transactions);
  }

  public static StatusCode create(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      EmbeddedDatabaseOpenResult result) {
    return EmbeddedDatabaseOpener.open(
        resourceRoot,
        resourcePlan,
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        true,
        null,
        1,
        EmbeddedLockDiagnosticsConfig.disabled(),
        result);
  }

  public static StatusCode create(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      EmbeddedDatabaseOpenResult result) {
    return create(
        resourceRoot, resourcePlan, directoryPath, database, generation,
        maximumActiveTransactions, lockWaitTimeoutNanos,
        EmbeddedLockDiagnosticsConfig.disabled(), result);
  }

  public static StatusCode create(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      EmbeddedDatabaseOpenResult result) {
    return EmbeddedDatabaseOpener.open(
        resourceRoot, resourcePlan, directoryPath, database, generation,
        maximumActiveTransactions, lockWaitTimeoutNanos, true, null, 1,
        lockDiagnostics, result);
  }

  public static StatusCode createWithDurableWalQuorum(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directoryPath,
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      EmbeddedDatabaseOpenResult result) {
    return EmbeddedDatabaseOpener.open(
        resourceRoot, resourcePlan, directoryPath, database, generation,
        maximumActiveTransactions, lockWaitTimeoutNanos, true,
        followerDirectoryPaths, requiredDurableNodes,
        EmbeddedLockDiagnosticsConfig.disabled(), result);
  }

  public static StatusCode openExisting(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      EmbeddedDatabaseOpenResult result) {
    if (lockDiagnostics == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return EmbeddedDatabaseOpener.open(
        resourceRoot,
        resourcePlan,
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        false,
        null,
        1,
        lockDiagnostics,
        result);
  }

  public static StatusCode openExisting(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      EmbeddedDatabaseOpenResult result) {
    if (lockDiagnostics == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return EmbeddedDatabaseOpener.open(
        resourceRoot, resourcePlan, directoryPath, database, generation,
        maximumActiveTransactions, lockWaitTimeoutNanos, false, null, 1,
        lockDiagnostics, result);
  }

  public static StatusCode openWithDurableWalQuorum(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directoryPath,
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      EmbeddedDatabaseOpenResult result) {
    return EmbeddedDatabaseOpener.open(
        resourceRoot, resourcePlan, directoryPath, database, generation,
        maximumActiveTransactions, lockWaitTimeoutNanos, false,
        followerDirectoryPaths, requiredDurableNodes,
        EmbeddedLockDiagnosticsConfig.disabled(), result);
  }

  public synchronized StatusCode createSession(
      int maximumRowBytes, EmbeddedSessionOpenResult result) {
    if (maximumRowBytes <= 0
        || maximumRowBytes > TableSchema.MAXIMUM_ROW_BYTES || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (closing || closed) {
      return StatusCode.CLOSED;
    }
    if (checkpoint.isFenced()) {
      return StatusCode.FENCED;
    }
    io.riverdb.engine.table.IndexedTransactionSessionOpenResult opened =
        new io.riverdb.engine.table.IndexedTransactionSessionOpenResult();
    StatusCode status = sessionContext.openSession(maximumRowBytes, opened);
    if (status.isOk()) result.set(opened.session());
    return status;
  }

  /** Revalidates a retained database-owned session before it is borrowed again. */
  public StatusCode admitSession(IndexedTransactionSession session) {
    if (closing || closed) return StatusCode.CLOSED;
    if (!ownsSession(session)) return StatusCode.INVALID_EXTERNAL_INPUT;
    return checkpoint.isFenced() ? StatusCode.FENCED : StatusCode.OK;
  }

  /** Authenticates a session before another database-owned service may stage into it. */
  public boolean ownsSession(IndexedTransactionSession session) {
    return session != null && sessions.contains(session) && session.belongsTo(table);
  }

  public StatusCode vacuum(TransactionOutcome result) {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (checkpoint.isFenced()) {
      return StatusCode.FENCED;
    }
    return vacuum.run(result);
  }

  public StatusCode checkpoint(CheckpointResult result) {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (wal.hasDurableQuorum()) {
      return StatusCode.CONFLICT;
    }
    return checkpoint.run(result);
  }

  public int requiredDurableNodeCount() {
    return wal.requiredDurableNodeCount();
  }

  public int availableDurableNodeCount() {
    return wal.availableDurableNodeCount();
  }

  public long quorumDurableCommitSequence() {
    return wal.quorumDurableCommitSequence();
  }

  public long replicatedWalPayloadBytes() {
    return wal.replicatedPayloadBytes();
  }

  public long lockWaitsEntered() { return transactions.lockWaitsEntered(); }

  public long lockWaitsActuallyBlocked() { return transactions.lockWaitsActuallyBlocked(); }

  public long lockWaitBlockedNanos() { return transactions.lockWaitBlockedNanos(); }

  public long activeLockCount() { return transactions.activeLockCount(); }

  public long waitingLockCount() { return transactions.waitingLockCount(); }

  public long lockWaitsGranted() { return transactions.lockWaitsGranted(); }

  public long lockWaitsTimedOut() { return transactions.lockWaitsTimedOut(); }

  public long lockWaitsDeadlocked() { return transactions.lockWaitsDeadlocked(); }

  public long lockWaitsCancelled() { return transactions.lockWaitsCancelled(); }

  public boolean lockEscalationSupported() { return transactions.lockEscalationSupported(); }

  public long lockEscalationCount() { return transactions.lockEscalationCount(); }

  public StatusCode appendDeadlockDiagnostics(StringBuilder target) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    LockDeadlockDiagnosticsSnapshot snapshot;
    try {
      snapshot = transactions.newDeadlockDiagnosticsSnapshot();
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = transactions.snapshotDeadlockDiagnostics(snapshot);
    if (!status.isOk()) return status;
    EmbeddedDeadlockDiagnostics.append(target, snapshot);
    return StatusCode.OK;
  }

  /** Appends a cold snapshot of commit-path and WAL-force telemetry. */
  public StatusCode appendCommitDiagnostics(StringBuilder target) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    IndexedGroupCommitTelemetry commits = new IndexedGroupCommitTelemetry();
    StatusCode status = table.copyCommitTelemetry(commits);
    if (!status.isOk()) return status;
    LocalWalMetrics forces = new LocalWalMetrics();
    status = table.copyWalMetrics(forces);
    if (!status.isOk()) return status;
    EmbeddedCommitDiagnostics.append(target, commits, forces);
    return StatusCode.OK;
  }

  public StatusCode beginPerformanceCapture() {
    return closed ? StatusCode.CLOSED : performanceCapture.begin();
  }

  public StatusCode endPerformanceCapture(StringBuilder target) {
    return closed ? StatusCode.CLOSED : performanceCapture.end(target);
  }

  public StatusCode cancelPerformanceCapture() {
    return performanceCapture.cancelIfActive();
  }

  public int activeTransactionCount() {
    return transactions.activeTransactionCount();
  }

  public int retainedSnapshotCount() {
    return transactions.retainedSnapshotCount();
  }

  public long currentCommitSequence() {
    return table.currentCommitSequence();
  }

  public long automaticVacuumRuns() {
    return vacuum.automaticRuns();
  }

  public long automaticVacuumDeferrals() {
    return vacuum.automaticDeferrals();
  }

  public long automaticVacuumPressureRejections() {
    return vacuum.automaticPressureRejections();
  }

  public long automaticVacuumRowsReclaimed() {
    return vacuum.automaticRowsReclaimed();
  }

  public long liveResourceWriteEntries() {
    return resourceGovernor == null ? 0 : resourceGovernor.liveWriteEntries();
  }

  public long liveResourceAccountedBytes() {
    return resourceGovernor == null ? 0 : resourceGovernor.liveAccountedBytes();
  }

  public long retainedDatabaseAccountedBytes() {
    return resourceGovernor == null ? 0
        : resourceGovernor.retainedDatabaseAccountedBytes();
  }

  /** Reserves one database-lifetime runtime capacity before its providers allocate. */
  public synchronized StatusCode retainRuntimeCapacity(long bytes) {
    if (closed) return StatusCode.CLOSED;
    if (resourceGovernor == null || bytes <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    return resourceGovernor.ensureRetainedDatabaseAccountedBytes(
        bytes, runtimeCapacityLease);
  }

  public boolean resourceGoverned() { return resourceGovernor != null; }

  public long resourceWriteEntryCapacity() {
    return resourceGovernor == null ? 0 : resourceGovernor.plan().writeEntryCapacity();
  }

  public long resourceStagedPageCapacity() {
    return resourceGovernor == null ? 0 : resourceGovernor.plan().stagedPageCapacity();
  }

  public long resourcePageCacheRetainedBytes() {
    return resourceGovernor == null ? 0
        : resourceGovernor.plan().indexedPageCache().maximumRetainedBytes();
  }

  public synchronized StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (transactions.activeTransactionCount() != 0) {
      return StatusCode.CONFLICT;
    }
    closing = true;
    StatusCode status = closePrimaryServices();
    for (LocalWal followerWal : followerWals) {
      status = firstFailure(status, closeStatus(followerWal.close()));
    }
    for (NioDurableDirectory followerDirectory : followerDirectories) {
      status = firstFailure(status, closeStatus(followerDirectory.close()));
    }
    if (status.isOk()) {
      status = closeStatus(directory.close());
    }
    if (status.isOk()) status = releaseResources();
    if (status.isOk()) {
      closed = true;
    }
    return status;
  }

  private StatusCode closePrimaryServices() {
    StatusCode status = closeStatus(performanceCapture.cancelIfActive());
    if (!status.isOk()) return status;
    status = sessions.closeAll();
    if (!status.isOk()) return status;
    status = closeStatus(groupCommit.close());
    if (!status.isOk()) return status;
    status = closeStatus(table.flush());
    if (!status.isOk()) return status;
    status = closeStatus(table.close());
    if (!status.isOk()) return status;
    return closeStatus(wal.close());
  }

  private StatusCode releaseResources() {
    if (resourceGovernor == null) return StatusCode.OK;
    StatusCode status = StatusCode.OK;
    if (runtimeCapacityLease.active()) {
      status = resourceGovernor.releaseRetainedDatabaseAccountedBytes(
          runtimeCapacityLease);
    }
    if (status.isOk() && providerLease.active()) {
      status = resourceGovernor.releaseDatabaseProviders(providerLease);
    }
    if (status.isOk()) status = closeStatus(resourceGovernor.close());
    return status;
  }

  /** Authoritative real primary-database root retained by the opened directory adapter. */
  public Path primaryDirectoryRoot() {
    return directory.root();
  }

  /** Durable database identity used to isolate runtime-owned scratch namespaces. */
  public DatabaseIncarnation databaseIncarnation() {
    return wal.databaseIncarnation();
  }

  /**
   * Extinguishes every unpublished resource after a higher-level open fails.
   *
   * <p>This is not a user close operation: it deliberately keeps going after a flush, active
   * operation, or file-close failure because no database handle will be published for a retry.
   * Closing the durable directory invalidates any remaining child handles; recovery owns any
   * durable WAL state on the next open.
   */
  public StatusCode closeAfterOpenFailure() {
    if (closed) return StatusCode.CLOSED;
    StatusCode sessionClose = sessions.closeAll();
    StatusCode first = sessionClose == StatusCode.CONFLICT ? StatusCode.OK : sessionClose;
    first = firstFailure(first, table.flush());
    first = firstFailure(first, table.close());
    first = firstFailure(first, wal.close());
    for (LocalWal followerWal : followerWals) {
      first = firstFailure(first, followerWal.close());
    }
    for (NioDurableDirectory followerDirectory : followerDirectories) {
      first = firstFailure(first, followerDirectory.close());
    }
    first = firstFailure(first, directory.close());
    if (resourceGovernor != null) {
      if (runtimeCapacityLease.active()) {
        first = firstFailure(
            first,
            resourceGovernor.releaseRetainedDatabaseAccountedBytes(runtimeCapacityLease));
      }
      first = firstFailure(first, resourceGovernor.abandonAfterOpenFailure());
    }
    closed = true;
    return first;
  }

  private static StatusCode firstFailure(StatusCode first, StatusCode next) {
    return first.isOk() ? next : first;
  }

  private static StatusCode closeStatus(StatusCode status) {
    return status == StatusCode.CLOSED ? StatusCode.OK : status;
  }

}
