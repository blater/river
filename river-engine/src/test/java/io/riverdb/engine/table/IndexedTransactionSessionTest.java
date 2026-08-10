package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.page.IndexedPageStore;
import io.riverdb.engine.page.IndexedPageStoreOpenResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTransactionSessionTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(449, 457);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void repeatableReadHidesLaterCommitAndReadCommittedRefreshes(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 8);
    IndexedTransactionSession repeatable = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    IndexedTransactionSession readCommitted = session(manager, table);
    assertEquals(StatusCode.OK, repeatable.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, readCommitted.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert(71, row(7101)));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, writer.fetchByKey(71, fetched));
    assertEquals(7101, value(fetched));
    assertEquals(StatusCode.CONFLICT, repeatable.fetchByKey(71, fetched));

    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    assertEquals(StatusCode.CONFLICT, repeatable.fetchByKey(71, fetched));
    assertEquals(StatusCode.OK, readCommitted.fetchByKey(71, fetched));
    assertEquals(7101, value(fetched));
    assertEquals(StatusCode.OK, repeatable.abort(outcome));
    assertEquals(StatusCode.OK, readCommitted.abort(outcome));
    close(table, wal, directory);
  }

  @Test
  void concurrentUniqueConflictAbortsOnlyLosingTransaction(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession first = session(manager, table);
    IndexedTransactionSession second = session(manager, table);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert(9, row(91)));
    assertEquals(StatusCode.RETRY, second.insert(9, row(92)));
    assertEquals(1, manager.activeLockCount());
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, first.commit(outcome));
    assertEquals(0, manager.activeLockCount());
    assertEquals(StatusCode.OK, second.insert(9, row(92)));
    assertEquals(StatusCode.CONFLICT, second.commit(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(9, fetched));
    assertEquals(91, value(fetched));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session(manager, table).begin(IsolationLevel.SERIALIZABLE));
    close(table, wal, directory);
  }

  @Test
  void abortDiscardsPendingInsertAndReleasesKey(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession first = session(manager, table);
    IndexedTransactionSession second = session(manager, table);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert(17, row(171)));
    assertEquals(StatusCode.RETRY, second.insert(17, row(172)));
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, first.abort(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(StatusCode.OK, second.insert(17, row(172)));
    assertEquals(StatusCode.OK, second.commit(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(17, fetched));
    assertEquals(172, value(fetched));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void commitsDistinctTransactionsFromConcurrentSessions(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 8);
    CountDownLatch ready = new CountDownLatch(4);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
      Future<StatusCode> first = executor.submit(
          () -> commitDistinct(manager, table, 101, ready, start));
      Future<StatusCode> second = executor.submit(
          () -> commitDistinct(manager, table, 102, ready, start));
      Future<StatusCode> third = executor.submit(
          () -> commitDistinct(manager, table, 103, ready, start));
      Future<StatusCode> fourth = executor.submit(
          () -> commitDistinct(manager, table, 104, ready, start));
      ready.await();
      start.countDown();
      assertEquals(StatusCode.OK, first.get());
      assertEquals(StatusCode.OK, second.get());
      assertEquals(StatusCode.OK, third.get());
      assertEquals(StatusCode.OK, fourth.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(0, manager.activeTransactionCount());
    HeapRowResult fetched = new HeapRowResult();
    for (int key = 101; key <= 104; key++) {
      assertEquals(StatusCode.OK, table.fetchByKey(key, fetched));
      assertEquals(key * 10L, value(fetched));
    }
    close(table, wal, directory);
  }

  @Test
  void recoversCommittedVisibilityBeforePageFlush(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert(501, row(5010)));
    long committedTransactionId = writer.transaction().transactionId();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.commit(outcome));
    long committedAt = outcome.commitSequence();
    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedPageStoreOpenResult storeResult = new IndexedPageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedPageStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(committedAt - 1, 501, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt, 501, fetched));
    assertEquals(5010, value(fetched));
    TransactionManager restartedManager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession restarted = session(restartedManager, table);
    assertEquals(StatusCode.OK, restarted.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(committedTransactionId + 1, restarted.transaction().transactionId());
    assertEquals(StatusCode.OK, restarted.abort(outcome));
    close(table, wal, directory);
  }

  private static StatusCode commitDistinct(
      TransactionManager manager,
      IndexedTable table,
      int key,
      CountDownLatch ready,
      CountDownLatch start) throws InterruptedException {
    IndexedTransactionSession session = session(manager, table);
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) {
      status = session.insert(key, row(key * 10L));
    }
    ready.countDown();
    start.await();
    return status.isOk() ? session.commit(new TransactionOutcome()) : status;
  }

  private static ByteBuffer row(long value) {
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    return row;
  }

  private static long value(HeapRowResult result) {
    ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
    assertEquals(StatusCode.OK, result.copyTo(row));
    return row.getLong(0);
  }

  private static IndexedTransactionSession session(
      TransactionManager manager,
      IndexedTable table) {
    return new IndexedTransactionSession(manager, table, 128);
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            result));
    return result.directory();
  }

  private static LocalWal openWal(NioDurableDirectory directory) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  private static IndexedPageStore createStore(
      NioDurableDirectory directory,
      LocalWal wal) {
    IndexedPageStoreOpenResult result = new IndexedPageStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedPageStore.create(directory, wal, DATABASE, GENERATION, result));
    return result.store();
  }

  private static IndexedTable createTable(IndexedPageStore store) {
    IndexedTableOpenResult result = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(store, result));
    return result.table();
  }

  private static void close(
      IndexedTable table,
      LocalWal wal,
      NioDurableDirectory directory) {
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }
}
