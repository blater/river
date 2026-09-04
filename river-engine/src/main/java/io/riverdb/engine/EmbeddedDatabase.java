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
    return open(
        resourceRoot, resourcePlan, directoryPath, database, generation,
        maximumActiveTransactions, lockWaitTimeoutNanos, true, lockDiagnostics, result);
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
    return open(
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
    return open(
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
    appendDiagnosticSummary(target, snapshot);
    appendDiagnosticSignatures(target, snapshot);
    appendDiagnosticEvents(target, snapshot);
    appendDiagnosticExemplars(target, snapshot);
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

  private static void appendDiagnosticSummary(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    target.append("server_deadlock_diagnostics_enabled=").append(snapshot.enabled()).append('\n')
        .append("server_deadlock_diagnostics_budget_bytes=")
        .append(snapshot.maximumRetainedBytes()).append('\n')
        .append("server_deadlock_diagnostics_retained_payload_bytes=")
        .append(snapshot.retainedPayloadBytes()).append('\n')
        .append("server_deadlock_diagnostics_maximum_epochs=")
        .append(snapshot.maximumEpochs()).append('\n')
        .append("server_deadlock_diagnostics_signatures_per_epoch=")
        .append(snapshot.signaturesPerEpoch()).append('\n')
        .append("server_deadlock_diagnostics_events_per_epoch=")
        .append(snapshot.victimEventsPerEpoch()).append('\n')
        .append("server_deadlock_diagnostics_exemplars_per_signature=")
        .append(snapshot.exemplarsPerSignature()).append('\n')
        .append("server_deadlock_diagnostics_maximum_cycle_edges=")
        .append(snapshot.maximumCycleEdges()).append('\n')
        .append("server_deadlock_fingerprint_version=")
        .append(snapshot.fingerprintVersion()).append('\n')
        .append("server_deadlock_diagnostics_valid=")
        .append(snapshot.validForDiagnosticGate()).append('\n')
        .append("server_deadlock_victim_selections=")
        .append(snapshot.totalVictimSelections()).append('\n')
        .append("server_deadlock_victim_outcomes=")
        .append(snapshot.victimTransactionOutcomes()).append('\n')
        .append("server_deadlock_queued_requests_cancelled=")
        .append(snapshot.queuedRequestsCancelled()).append('\n')
        .append("server_deadlock_holdings_released=")
        .append(snapshot.holdingsReleased()).append('\n')
        .append("server_deadlock_self_validation_failures=")
        .append(snapshot.selfValidationFailures()).append('\n')
        .append("server_deadlock_fingerprint_overflows=")
        .append(snapshot.fingerprintOverflows()).append('\n')
        .append("server_deadlock_fingerprint_collisions=")
        .append(snapshot.fingerprintCollisions()).append('\n')
        .append("server_deadlock_epoch_overflows=")
        .append(snapshot.epochOverflows()).append('\n')
        .append("server_deadlock_event_overflows=")
        .append(snapshot.victimEventOverflows()).append('\n')
        .append("server_deadlock_exemplar_overflows=")
        .append(snapshot.exemplarOverflows()).append('\n')
        .append("server_deadlock_cycle_edge_overflows=")
        .append(snapshot.cycleEdgeOverflows()).append('\n')
        .append("server_deadlock_sequence_overflows=")
        .append(snapshot.eventSequenceOverflows()).append('\n');
  }

  private static void appendDiagnosticSignatures(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    for (int index = 0; index < snapshot.signatureCount(); index++) {
      target.append("deadlock_signature index=").append(index)
          .append(" epoch=").append(snapshot.signatureEpochAt(index))
          .append(" fingerprint=")
          .append(Long.toUnsignedString(snapshot.fingerprintAt(index), 16))
          .append(" collision_guard=")
          .append(Long.toUnsignedString(snapshot.collisionGuardAt(index), 16))
          .append(" victims=").append(snapshot.signatureVictimSelectionsAt(index))
          .append(" outcomes=").append(snapshot.signatureVictimOutcomesAt(index))
          .append(" queued_cancelled=")
          .append(snapshot.signatureQueuedRequestsCancelledAt(index))
          .append(" holdings_released=")
          .append(snapshot.signatureHoldingsReleasedAt(index))
          .append(" first_sequence=")
          .append(snapshot.signatureFirstEventSequenceAt(index))
          .append(" last_sequence=")
          .append(snapshot.signatureLastEventSequenceAt(index))
          .append(" exemplars=").append(snapshot.signatureExemplarCountAt(index))
          .append('\n');
    }
  }

  private static void appendDiagnosticEvents(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    for (int index = 0; index < snapshot.victimEventCount(); index++) {
      target.append("deadlock_event index=").append(index)
          .append(" epoch=").append(snapshot.eventEpochAt(index))
          .append(" sequence=").append(snapshot.eventSequenceAt(index))
          .append(" outcome_sequence=").append(snapshot.eventOutcomeSequenceAt(index))
          .append(" victim_sequence=")
          .append(snapshot.eventVictimSelectionSequenceAt(index))
          .append(" fingerprint=")
          .append(Long.toUnsignedString(snapshot.eventFingerprintAt(index), 16))
          .append(" transaction_id=").append(snapshot.eventTransactionIdAt(index))
          .append(" generation=").append(snapshot.eventTransactionGenerationAt(index))
          .append(" start_order=").append(snapshot.eventTransactionStartOrderAt(index))
          .append(" attempt_tag=").append(snapshot.eventDiagnosticTagAt(index))
          .append(" step_tag=").append(snapshot.eventDiagnosticStepTagAt(index))
          .append(" outcome=").append(snapshot.eventOutcomeStatusAt(index))
          .append(" queued_cancelled=")
          .append(snapshot.eventQueuedRequestsCancelledAt(index))
          .append(" holdings_released=").append(snapshot.eventHoldingsReleasedAt(index))
          .append(" cleanup_valid=").append(snapshot.eventCleanupValidAt(index))
          .append('\n');
    }
  }

  private static void appendDiagnosticExemplars(
      StringBuilder target, LockDeadlockDiagnosticsSnapshot snapshot) {
    for (int exemplar = 0; exemplar < snapshot.exemplarCount(); exemplar++) {
      int edges = snapshot.exemplarEdgeCountAt(exemplar);
      target.append("deadlock_exemplar index=").append(exemplar)
          .append(" signature_index=").append(snapshot.exemplarSignatureIndexAt(exemplar))
          .append(" event_index=").append(snapshot.exemplarEventIndexAt(exemplar))
          .append(" edges=").append(edges).append('\n');
      for (int offset = 0; offset < edges; offset++) {
        int edge = snapshot.exemplarEdgeIndex(exemplar, offset);
        target.append("deadlock_edge exemplar=").append(exemplar)
            .append(" offset=").append(offset)
            .append(" kind=").append(snapshot.edgeKindAt(edge))
            .append(" precondition=").append(snapshot.edgePreconditionAt(edge))
            .append(" grant_predicate=").append(snapshot.edgeGrantPredicateResultAt(edge))
            .append(" waiter_attempt_tag=")
            .append(snapshot.edgeWaiterDiagnosticTagAt(edge))
            .append(" waiter_step_tag=")
            .append(snapshot.edgeWaiterDiagnosticStepTagAt(edge))
            .append(" blocker_attempt_tag=")
            .append(snapshot.edgeBlockerDiagnosticTagAt(edge))
            .append(" blocker_step_tag=")
            .append(snapshot.edgeBlockerDiagnosticStepTagAt(edge))
            .append(" scope=").append(snapshot.edgeResourceScopeAt(edge))
            .append(" requested_mode=").append(snapshot.edgeRequestedModeAt(edge))
            .append(" held_mode=").append(snapshot.edgeHeldModeAt(edge))
            .append(" blocker_requested_mode=")
            .append(snapshot.edgeBlockerRequestedModeAt(edge))
            .append(" waiter_queue=").append(snapshot.edgeWaiterQueueKindAt(edge))
            .append(" waiter_order=").append(snapshot.edgeWaiterQueueOrderAt(edge))
            .append(" blocker_queue=").append(snapshot.edgeBlockerQueueKindAt(edge))
            .append(" blocker_order=").append(snapshot.edgeBlockerQueueOrderAt(edge))
            .append(" resource_namespace=").append(snapshot.edgeResourceNamespaceAt(edge))
            .append(" resource_lower=").append(snapshot.edgeResourceLowerKeyAt(edge))
            .append(" resource_upper_namespace=")
            .append(snapshot.edgeResourceUpperNamespaceAt(edge))
            .append(" resource_upper=").append(snapshot.edgeResourceUpperKeyAt(edge))
            .append('\n');
      }
    }
  }

  public int activeTransactionCount() {
    return transactions.activeTransactionCount();
  }

  public int retainedSnapshotCount() { return transactions.retainedSnapshotCount(); }

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
    StatusCode status = closeStatus(performanceCapture.cancelIfActive());
    if (status.isOk()) status = sessions.closeAll();
    if (status.isOk()) status = closeStatus(groupCommit.close());
    if (status.isOk()) status = closeStatus(table.flush());
    if (status.isOk()) {
      status = closeStatus(table.close());
    }
    if (status.isOk()) {
      status = closeStatus(wal.close());
    }
    for (LocalWal followerWal : followerWals) {
      StatusCode followerStatus = closeStatus(followerWal.close());
      if (status.isOk()) {
        status = followerStatus;
      }
    }
    for (NioDurableDirectory followerDirectory : followerDirectories) {
      StatusCode followerStatus = closeStatus(followerDirectory.close());
      if (status.isOk()) {
        status = followerStatus;
      }
    }
    if (status.isOk()) {
      status = closeStatus(directory.close());
    }
    if (status.isOk() && resourceGovernor != null && runtimeCapacityLease.active()) {
      status = resourceGovernor.releaseRetainedDatabaseAccountedBytes(
          runtimeCapacityLease);
    }
    if (status.isOk() && resourceGovernor != null && providerLease.active()) {
      status = resourceGovernor.releaseDatabaseProviders(providerLease);
    }
    if (status.isOk() && resourceGovernor != null) {
      status = closeStatus(resourceGovernor.close());
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
    return open(
        resourceRoot, resourcePlan, directoryPath, database, generation,
        maximumActiveTransactions, lockWaitTimeoutNanos, create,
        EmbeddedLockDiagnosticsConfig.disabled(), result);
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
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      EmbeddedDatabaseOpenResult result) {
    return open(
        resourceRoot, resourcePlan, directoryPath, database, generation,
        maximumActiveTransactions, lockWaitTimeoutNanos, create, null, 1,
        lockDiagnostics, result);
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
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
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
        followerDirectoryPaths,
        requiredDurableNodes,
        lockDiagnostics,
        result);
  }
}
