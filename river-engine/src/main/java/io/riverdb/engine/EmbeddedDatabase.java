package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointControlStore;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.checkpoint.EmbeddedCheckpoint;
import io.riverdb.engine.table.IndexedTableStore;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedGroupCommitCoordinator;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedVacuum;
import io.riverdb.platform.file.nio.NioDurableDirectory;
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
  private boolean closed;

  EmbeddedDatabase(
      NioDurableDirectory openedDirectory,
      NioDurableDirectory[] openedFollowerDirectories,
      LocalWal openedWal,
      LocalWal[] openedFollowerWals,
      IndexedTableStore openedStore,
      IndexedTable openedTable,
      int maximumActiveTransactions,
      CheckpointControlStore checkpointControl,
      long checkpointId) {
    directory = openedDirectory;
    followerDirectories = openedFollowerDirectories;
    wal = openedWal;
    followerWals = openedFollowerWals;
    table = openedTable;
    transactions = new TransactionManager(
        openedWal.databaseIncarnation().high(),
        openedWal.databaseIncarnation().low(),
        openedTable.nextTransactionId(),
        maximumActiveTransactions);
    groupCommit = new IndexedGroupCommitCoordinator(transactions, openedTable);
    vacuum = new IndexedVacuum(transactions, table);
    checkpoint = new EmbeddedCheckpoint(
        transactions,
        openedDirectory,
        openedWal,
        openedStore,
        openedTable,
        checkpointControl,
        checkpointId);
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
        true,
        null,
        1,
        result);
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
        true,
        followerDirectoryPaths,
        requiredDurableNodes,
        result);
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
        false,
        null,
        1,
        result);
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
        false,
        followerDirectoryPaths,
        requiredDurableNodes,
        result);
  }

  public StatusCode createSession(int maximumRowBytes, EmbeddedSessionOpenResult result) {
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
    result.set(new IndexedTransactionSession(
        transactions, table, maximumRowBytes, groupCommit, vacuum));
    return StatusCode.OK;
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

  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (transactions.activeTransactionCount() != 0) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = table.flush();
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
    if (status.isOk()) {
      closed = true;
    }
    return status;
  }

  private static StatusCode open(
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      boolean create,
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      EmbeddedDatabaseOpenResult result) {
    return EmbeddedDatabaseOpener.open(
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        create,
        followerDirectoryPaths,
        requiredDurableNodes,
        result);
  }
}
