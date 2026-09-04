package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.runtime.DatabaseProviderLease;
import io.riverdb.engine.runtime.DatabaseStoreLease;
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
import io.riverdb.wal.local.LocalWalReservation;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTableStoreLifecycleTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(829, 839);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void providerLeaseOwnsExactlyOnePublishedStore(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    DatabaseProviderLease providers = databaseProviderLease(4);
    IndexedTableStoreOpenResult first = new IndexedTableStoreOpenResult();
    IndexedTableStoreOpenResult duplicate = new IndexedTableStoreOpenResult();

    assertEquals(StatusCode.OK,
        IndexedTableStore.create(
            directory, wal, DATABASE, GENERATION, providers, first));
    assertEquals(StatusCode.NOT_OWNER,
        providers.releaseStore(new DatabaseStoreLease()));
    assertEquals(StatusCode.CONFLICT,
        IndexedTableStore.openExisting(
            directory, wal, DATABASE, GENERATION, providers, duplicate));
    assertNull(duplicate.store());

    IndexedTable table = createTable(first.store());
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.NOT_OWNER,
        providers.claimStore(
            DATABASE.high() + 1, DATABASE.low(), GENERATION.value(),
            new DatabaseStoreLease()));
    assertEquals(StatusCode.OK,
        IndexedTableStore.openExisting(
            directory, wal, DATABASE, GENERATION, providers, duplicate));
    assertEquals(StatusCode.OK, duplicate.store().close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void walReservationConflictCancelsStagedTransaction(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    IndexedTable table = createTable(store);
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext.Result contextResult = new IndexedSessionContext.Result();
    assertEquals(
        StatusCode.OK,
        IndexedSessionContext.bind(manager, table, null, vacuum, contextResult));
    IndexedTransactionSessionOpenResult sessionResult =
        new IndexedTransactionSessionOpenResult();
    assertEquals(
        StatusCode.OK,
        contextResult.context().openSession(Long.BYTES, sessionResult));
    IndexedTransactionSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    for (int key = 0; key < 256; key++) {
      insert(session, outcome, key, key);
    }
    int pagesBeforeSplit = table.pageCount();
    LocalWalReservation blocker = new LocalWalReservation();
    assertEquals(StatusCode.OK, wal.reserve(1, blocker));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(0, 256, row(2_560)));
    assertEquals(StatusCode.CONFLICT, session.commit(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(StatusCode.CONFLICT, store.cancelOperation());
    assertEquals(StatusCode.CONFLICT, table.fetchByKey(0, 256, new HeapRowResult()));
    assertEquals(pagesBeforeSplit, table.pageCount());

    assertEquals(StatusCode.OK, wal.cancel(blocker));
    insert(session, outcome, 256, 2_560);
    assertEquals(StatusCode.OK, table.fetchByKey(0, 256, new HeapRowResult()));
    assertTrue(table.pageCount() > pagesBeforeSplit);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static ByteBuffer row(long value) {
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    return row;
  }

  private static void insert(
      IndexedTransactionSession session,
      TransactionOutcome outcome,
      long key,
      long value) {
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(0, key, row(value)));
    assertEquals(StatusCode.OK, session.commit(outcome));
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, result));
    return result.directory();
  }

  private static LocalWal openWal(NioDurableDirectory directory) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  private static IndexedTableStore createStore(
      NioDurableDirectory directory, LocalWal wal) {
    IndexedTableStoreOpenResult result = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.create(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(4), result));
    return result.store();
  }

  private static IndexedTable createTable(IndexedTableStore store) {
    IndexedTableOpenResult result = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(store, result));
    return result.table();
  }
}
