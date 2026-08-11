package io.riverdb.engine;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointControlStore;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.engine.checkpoint.EmbeddedCheckpoint;
import io.riverdb.engine.control.DatabaseControlResult;
import io.riverdb.engine.control.DatabaseControlStore;
import io.riverdb.engine.page.IndexedPageStore;
import io.riverdb.engine.page.IndexedPageStoreOpenResult;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedTableOpenResult;
import io.riverdb.engine.table.IndexedGroupCommitCoordinator;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedVacuum;
import io.riverdb.format.control.ControlFile;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.file.Path;

/** Minimal embedded lifecycle over the first durable indexed transaction kernel. */
public final class EmbeddedDatabase {
  private static final int MAXIMUM_ROW_BYTES = 4096;
  private static final int MAXIMUM_ACTIVE_TRANSACTIONS = 1024;

  private final NioDurableDirectory directory;
  private final LocalWal wal;
  private final IndexedTable table;
  private final TransactionManager transactions;
  private final IndexedGroupCommitCoordinator groupCommit;
  private final IndexedVacuum vacuum;
  private final EmbeddedCheckpoint checkpoint;
  private boolean closed;

  private EmbeddedDatabase(
      NioDurableDirectory openedDirectory,
      LocalWal openedWal,
      IndexedPageStore openedStore,
      IndexedTable openedTable,
      int maximumActiveTransactions,
      CheckpointControlStore checkpointControl,
      long checkpointId) {
    directory = openedDirectory;
    wal = openedWal;
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
    return checkpoint.run(result);
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
      EmbeddedDatabaseOpenResult result) {
    if (directoryPath == null
        || database == null
        || !database.isValid()
        || generation == null
        || !generation.isValid()
        || maximumActiveTransactions <= 0
        || maximumActiveTransactions > MAXIMUM_ACTIVE_TRANSACTIONS
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    StatusCode status = NioDurableDirectory.openExisting(
        directoryPath,
        new FatalStateFence(),
        new NioIoCounters(),
        16,
        directoryResult);
    if (!status.isOk()) {
      return status;
    }
    NioDurableDirectory directory = directoryResult.directory();
    DatabaseControlResult databaseControl = new DatabaseControlResult();
    StatusCode controlStatus = DatabaseControlStore.open(directory, databaseControl);
    CheckpointControlStore checkpointControl = new CheckpointControlStore();
    CheckpointState checkpointState = new CheckpointState();
    boolean checkpointAvailable = false;
    status = checkpointControl.read(directory, checkpointState);
    if (create) {
      if (controlStatus.isOk()) {
        directory.close();
        return StatusCode.CONFLICT;
      }
      if (controlStatus != StatusCode.CONFLICT) {
        directory.close();
        return controlStatus;
      }
      if (status.isOk()) {
        directory.close();
        return StatusCode.CONFLICT;
      }
      if (status != StatusCode.CONFLICT) {
        directory.close();
        return status;
      }
      status = StatusCode.OK;
    } else {
      if (!controlStatus.isOk()) {
        directory.close();
        return controlStatus;
      }
      ControlFile control = databaseControl.controlFile();
      if (!database.equals(control.databaseIncarnation())
          || !generation.equals(control.walGeneration())) {
        directory.close();
        return StatusCode.FENCED;
      }
      if (status.isOk()) {
        if (!database.equals(checkpointState.database())) {
          directory.close();
          return StatusCode.FENCED;
        }
        checkpointAvailable = true;
      } else if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
      } else {
        directory.close();
        return status;
      }
    }
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    if (create) {
      status = LocalWal.create(directory, database, generation, walResult);
    } else if (checkpointAvailable) {
      status = LocalWal.openExistingNamed(
          directory,
          LocalWal.generationFileName(checkpointState.walGeneration()),
          database,
          checkpointState.walGeneration(),
          walResult);
    } else {
      status = LocalWal.openExisting(directory, database, generation, walResult);
    }
    if (!status.isOk()) {
      directory.close();
      return checkpointAvailable && status == StatusCode.CONFLICT
          ? StatusCode.CORRUPTION : status;
    }
    LocalWal wal = walResult.wal();
    if (checkpointAvailable) {
      status = wal.adoptCheckpointState(
          checkpointState.commitSequence(), checkpointState.maximumTransactionId());
      if (!status.isOk()) {
        wal.close();
        directory.close();
        return status;
      }
    }
    IndexedPageStoreOpenResult storeResult = new IndexedPageStoreOpenResult();
    if (create) {
      status = IndexedPageStore.create(directory, wal, database, generation, storeResult);
    } else if (checkpointAvailable) {
      status = IndexedPageStore.openCheckpoint(
          directory, wal, database, checkpointState, storeResult);
    } else {
      status = IndexedPageStore.openExisting(
          directory, wal, database, generation, storeResult);
    }
    if (!status.isOk()) {
      wal.close();
      directory.close();
      return status;
    }
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    status = create
        ? IndexedTable.create(storeResult.store(), tableResult)
        : IndexedTable.open(storeResult.store(), tableResult);
    if (!status.isOk()) {
      storeResult.store().close();
      wal.close();
      directory.close();
      return status;
    }
    if (create) {
      status = DatabaseControlStore.create(
          directory, new ControlFile(database, generation), databaseControl);
      if (!status.isOk()) {
        tableResult.table().close();
        wal.close();
        directory.close();
        return status;
      }
    }
    result.set(new EmbeddedDatabase(
        directory,
        wal,
        storeResult.store(),
        tableResult.table(),
        maximumActiveTransactions,
        checkpointControl,
        checkpointAvailable ? checkpointState.checkpointId() : 0));
    return StatusCode.OK;
  }
}
