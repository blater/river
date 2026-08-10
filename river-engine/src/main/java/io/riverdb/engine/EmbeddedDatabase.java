package io.riverdb.engine;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.page.IndexedPageStore;
import io.riverdb.engine.page.IndexedPageStoreOpenResult;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedTableOpenResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedVacuum;
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

  private final NioDurableDirectory directory;
  private final LocalWal wal;
  private final IndexedTable table;
  private final TransactionManager transactions;
  private final IndexedVacuum vacuum;
  private boolean closed;

  private EmbeddedDatabase(
      NioDurableDirectory openedDirectory,
      LocalWal openedWal,
      IndexedTable openedTable,
      int maximumActiveTransactions) {
    directory = openedDirectory;
    wal = openedWal;
    table = openedTable;
    transactions = new TransactionManager(
        openedWal.databaseIncarnation().high(),
        openedWal.databaseIncarnation().low(),
        openedTable.nextTransactionId(),
        maximumActiveTransactions);
    vacuum = new IndexedVacuum(transactions, table);
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
    result.set(new IndexedTransactionSession(transactions, table, maximumRowBytes));
    return StatusCode.OK;
  }

  public StatusCode vacuum(TransactionOutcome result) {
    if (closed) {
      return StatusCode.CLOSED;
    }
    return vacuum.run(result);
  }

  public int activeTransactionCount() {
    return transactions.activeTransactionCount();
  }

  public long currentCommitSequence() {
    return table.currentCommitSequence();
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
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    status = create
        ? LocalWal.create(directory, database, generation, walResult)
        : LocalWal.openExisting(directory, database, generation, walResult);
    if (!status.isOk()) {
      directory.close();
      return status;
    }
    LocalWal wal = walResult.wal();
    IndexedPageStoreOpenResult storeResult = new IndexedPageStoreOpenResult();
    status = create
        ? IndexedPageStore.create(directory, wal, database, generation, storeResult)
        : IndexedPageStore.openExisting(directory, wal, database, generation, storeResult);
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
    result.set(new EmbeddedDatabase(
        directory,
        wal,
        tableResult.table(),
        maximumActiveTransactions));
    return StatusCode.OK;
  }
}
