package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointControlStore;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.checkpoint.EmbeddedCheckpoint;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.engine.runtime.RuntimeResourceRoot;
import io.riverdb.engine.table.IndexedGroupCommitCoordinator;
import io.riverdb.engine.table.IndexedSessionRegistry;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedTableStore;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedVacuum;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.tx.LockMemoryEnvelope;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;
import java.nio.file.Path;

/** Minimal embedded lifecycle over the first durable indexed transaction kernel. */
public final class EmbeddedDatabase {
  private static final int MAXIMUM_ROW_BYTES = 8192;

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
  private final IndexedSessionRegistry sessions;
  private long retainedRuntimeCapacityBytes;
  private volatile boolean closed;

  EmbeddedDatabase(
      NioDurableDirectory openedDirectory,
      NioDurableDirectory[] openedFollowerDirectories,
      LocalWal openedWal,
      LocalWal[] openedFollowerWals,
      IndexedTableStore openedStore,
      IndexedTable openedTable,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      CheckpointControlStore checkpointControl,
      long checkpointId,
      DatabaseResourceGovernor governor) {
    directory = openedDirectory;
    followerDirectories = openedFollowerDirectories;
    wal = openedWal;
    followerWals = openedFollowerWals;
    table = openedTable;
    transactions = governor == null
        ? new TransactionManager(
            openedWal.databaseIncarnation().high(),
            openedWal.databaseIncarnation().low(),
            openedTable.nextTransactionId(),
            maximumActiveTransactions,
            lockWaitTimeoutNanos)
        : new TransactionManager(
            openedWal.databaseIncarnation().high(),
            openedWal.databaseIncarnation().low(),
            openedTable.nextTransactionId(),
            maximumActiveTransactions,
            new LockMemoryEnvelope(governor.plan().lockProviderBytes()),
            lockWaitTimeoutNanos);
    vacuum = new IndexedVacuum(transactions, table);
    checkpoint = new EmbeddedCheckpoint(
        transactions,
        openedDirectory,
        openedWal,
        openedStore,
        openedTable,
        checkpointControl,
        checkpointId);
    resourceGovernor = governor;
    sessions = new IndexedSessionRegistry(
        governor == null ? maximumActiveTransactions : governor.plan().maximumOwners());
    groupCommit = new IndexedGroupCommitCoordinator(transactions, openedTable);
  }

  public static StatusCode create(
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      EmbeddedDatabaseOpenResult result) {
    return open(
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        true,
        null,
        1,
        result);
  }

  public static StatusCode create(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      EmbeddedDatabaseOpenResult result) {
    return open(
        resourceRoot,
        resourcePlan,
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        true,
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
    return open(
        resourceRoot, resourcePlan, directoryPath, database, generation,
        maximumActiveTransactions, lockWaitTimeoutNanos, true, result);
  }

  public static StatusCode createWithDurableWalQuorum(
      Path directoryPath,
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      EmbeddedDatabaseOpenResult result) {
    return open(
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        true,
        followerDirectoryPaths,
        requiredDurableNodes,
        result);
  }

  public static StatusCode createWithDurableWalQuorum(
      Path directoryPath,
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      EmbeddedDatabaseOpenResult result) {
    return open(
        directoryPath, database, generation, maximumActiveTransactions,
        lockWaitTimeoutNanos, true, followerDirectoryPaths, requiredDurableNodes, result);
  }

  public static StatusCode openExisting(
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      EmbeddedDatabaseOpenResult result) {
    return open(
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        false,
        null,
        1,
        result);
  }

  public static StatusCode openExisting(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      EmbeddedDatabaseOpenResult result) {
    return open(
        resourceRoot,
        resourcePlan,
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        false,
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
      EmbeddedDatabaseOpenResult result) {
    return open(
        resourceRoot, resourcePlan, directoryPath, database, generation,
        maximumActiveTransactions, lockWaitTimeoutNanos, false, result);
  }

  public static StatusCode openWithDurableWalQuorum(
      Path directoryPath,
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      EmbeddedDatabaseOpenResult result) {
    return open(
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS,
        false,
        followerDirectoryPaths,
        requiredDurableNodes,
        result);
  }

  public static StatusCode openWithDurableWalQuorum(
      Path directoryPath,
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      EmbeddedDatabaseOpenResult result) {
    return open(
        directoryPath, database, generation, maximumActiveTransactions,
        lockWaitTimeoutNanos, false, followerDirectoryPaths, requiredDurableNodes, result);
  }

  public synchronized StatusCode createSession(
      int maximumRowBytes, EmbeddedSessionOpenResult result) {
    if (maximumRowBytes <= 0 || maximumRowBytes > MAXIMUM_ROW_BYTES || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (checkpoint.isFenced()) {
      return StatusCode.FENCED;
    }
    return EmbeddedSessionFactory.create(
        transactions, table, maximumRowBytes, groupCommit, vacuum,
        resourceGovernor, sessions, result);
  }

  /** Revalidates a retained database-owned session before it is borrowed again. */
  public StatusCode admitSession(IndexedTransactionSession session) {
    if (closed) return StatusCode.CLOSED;
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

  public long lockWaitsGranted() { return transactions.lockWaitsGranted(); }

  public long lockWaitsTimedOut() { return transactions.lockWaitsTimedOut(); }

  public long lockWaitsDeadlocked() { return transactions.lockWaitsDeadlocked(); }

  public long lockWaitsCancelled() { return transactions.lockWaitsCancelled(); }

  public boolean lockEscalationSupported() { return transactions.lockEscalationSupported(); }

  public long lockEscalationCount() { return transactions.lockEscalationCount(); }

  public int activeTransactionCount() {
    return transactions.activeTransactionCount();
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
    if (retainedRuntimeCapacityBytes != 0) {
      return retainedRuntimeCapacityBytes == bytes ? StatusCode.OK : StatusCode.CONFLICT;
    }
    StatusCode status = resourceGovernor.growRetainedDatabaseAccountedBytes(bytes);
    if (status.isOk()) retainedRuntimeCapacityBytes = bytes;
    return status;
  }

  public boolean resourceGoverned() { return resourceGovernor != null; }

  public long resourceWriteEntryCapacity() {
    return resourceGovernor == null ? 0 : resourceGovernor.plan().writeEntryCapacity();
  }

  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (transactions.activeTransactionCount() != 0) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = sessions.closeAll();
    if (status.isOk()) status = groupCommit.close();
    if (status.isOk()) status = table.flush();
    if (status.isOk()) {
      status = table.close();
    }
    if (status.isOk()) {
      status = wal.close();
    }
    for (LocalWal followerWal : followerWals) {
      StatusCode followerStatus = followerWal.close();
      if (status.isOk()) {
        status = followerStatus;
      }
    }
    for (NioDurableDirectory followerDirectory : followerDirectories) {
      StatusCode followerStatus = followerDirectory.close();
      if (status.isOk()) {
        status = followerStatus;
      }
    }
    if (status.isOk()) {
      status = directory.close();
    }
    if (status.isOk() && resourceGovernor != null) {
      status = resourceGovernor.close();
    }
    if (status.isOk()) {
      closed = true;
    }
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
      first = firstFailure(first, resourceGovernor.abandonAfterOpenFailure());
    }
    closed = true;
    return first;
  }

  private static StatusCode firstFailure(StatusCode first, StatusCode next) {
    return first.isOk() ? next : first;
  }

  private static StatusCode open(
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      boolean create,
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      EmbeddedDatabaseOpenResult result) {
    return EmbeddedDatabaseOpener.open(
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        lockWaitTimeoutNanos,
        create,
        followerDirectoryPaths,
        requiredDurableNodes,
        result);
  }

  private static StatusCode open(
      RuntimeResourceRoot resourceRoot,
      DatabaseResourcePlan resourcePlan,
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      boolean create,
      EmbeddedDatabaseOpenResult result) {
    return EmbeddedDatabaseOpener.open(
        resourceRoot,
        resourcePlan,
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        lockWaitTimeoutNanos,
        create,
        null,
        1,
        result);
  }
}
