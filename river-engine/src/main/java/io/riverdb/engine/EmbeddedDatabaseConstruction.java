package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointControlStore;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.engine.runtime.DatabaseProviderLease;
import io.riverdb.engine.table.IndexedGroupCommitCoordinator;
import io.riverdb.engine.table.IndexedSessionContext;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedTableStore;
import io.riverdb.engine.table.IndexedVacuum;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.tx.LockDeadlockDiagnosticsConfig;
import io.riverdb.tx.LockMemoryEnvelope;
import io.riverdb.tx.TransactionManager;
import io.riverdb.wal.local.LocalWal;

/** Translates allocation failure while publishing an acquired embedded database. */
final class EmbeddedDatabaseConstruction {
  private EmbeddedDatabaseConstruction() {}

  static StatusCode construct(
      NioDurableDirectory directory,
      NioDurableDirectory[] followerDirectories,
      LocalWal wal,
      LocalWal[] followerWals,
      IndexedTableStore store,
      IndexedTable table,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      LockDeadlockDiagnosticsConfig lockDiagnostics,
      CheckpointControlStore checkpointControl,
      CheckpointState checkpointState,
      long checkpointId,
      DatabaseProviderLease providerLease,
      EmbeddedDatabaseOpenResult result) {
    DatabaseResourceGovernor resourceGovernor =
        providerLease == null ? null : providerLease.governor();
    if (resourceGovernor == null) return StatusCode.NOT_OWNER;
    IndexedGroupCommitCoordinator groupCommit = null;
    try {
      TransactionManager transactions = new TransactionManager(
          wal.databaseIncarnation().high(),
          wal.databaseIncarnation().low(),
          table.nextTransactionId(),
          maximumActiveTransactions,
          new LockMemoryEnvelope(resourceGovernor.plan().lockProviderBytes()),
          lockWaitTimeoutNanos,
          lockDiagnostics);
      IndexedVacuum vacuum = new IndexedVacuum(transactions, table);
      groupCommit = new IndexedGroupCommitCoordinator(transactions, table);
      IndexedSessionContext.Result sessionContext = new IndexedSessionContext.Result();
      StatusCode status = IndexedSessionContext.bind(
          transactions, table, groupCommit, vacuum, sessionContext);
      if (!status.isOk()) {
        groupCommit.close();
        closeAfterFailure(
            directory, followerDirectories, wal, followerWals,
            store, table, checkpointState, resourceGovernor);
        return status;
      }
      result.set(new EmbeddedDatabase(
          directory, followerDirectories, wal, followerWals, store, table,
          transactions, sessionContext.context(),
          checkpointControl, checkpointId, providerLease));
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      if (groupCommit != null) groupCommit.close();
      closeAfterFailure(
          directory, followerDirectories, wal, followerWals,
          store, table, checkpointState, resourceGovernor);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static void closeAfterFailure(
      NioDurableDirectory directory,
      NioDurableDirectory[] followerDirectories,
      LocalWal wal,
      LocalWal[] followerWals,
      IndexedTableStore store,
      IndexedTable table,
      CheckpointState checkpointState,
      DatabaseResourceGovernor resourceGovernor) {
    if (table != null) table.close();
    else if (store != null) store.close();
    checkpointState.close();
    closeFollowers(followerWals, followerDirectories);
    if (wal != null) wal.close();
    if (directory != null) directory.close();
    if (resourceGovernor != null) resourceGovernor.abandonAfterOpenFailure();
  }

  private static void closeFollowers(
      LocalWal[] followerWals, NioDurableDirectory[] followerDirectories) {
    if (followerWals != null) {
      for (LocalWal follower : followerWals) if (follower != null) follower.close();
    }
    if (followerDirectories != null) {
      for (NioDurableDirectory follower : followerDirectories) {
        if (follower != null) follower.close();
      }
    }
  }
}
