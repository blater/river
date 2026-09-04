package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionGroupCompletionTimings;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedPreparedGroupPublicationTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(1_031, 1_033);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void preservesEachMemberCsnWhenBothChangeOneScalarLeaf(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = manager(table, 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext context = context(manager, table, vacuum);
    TransactionOutcome seedOutcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(context);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert(0, 10, row(100)));
    assertEquals(StatusCode.OK, seed.insert(0, 20, row(200)));
    assertEquals(StatusCode.OK, seed.commit(seedOutcome));
    int sharedLeafRoot = table.rootPageId();

    IndexedTransactionSession first = session(context);
    IndexedTransactionSession second = session(context);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.update(0, 10, row(101)));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.update(0, 20, row(202)));
    long[] sequences = publish(manager, table, first, second);

    assertEquals(sharedLeafRoot, table.rootPageId());
    assertValue(table, sequences[0], 10, 101);
    assertValue(table, sequences[0], 20, 200);
    assertValue(table, sequences[1], 10, 101);
    assertValue(table, sequences[1], 20, 202);
    assertEquals(sequences[1], table.currentCommitSequence());
    close(table, wal, directory);
  }

  @Test
  void splitAllocationsRetainMemberSnapshotsAndRecoverFromWal(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = manager(table, 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext context = context(manager, table, vacuum);
    int originalRoot = table.rootPageId();
    int originalNextPage = table.nextPageId();
    IndexedTransactionSession first = session(context);
    IndexedTransactionSession second = session(context);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    for (int key = 0; key < BTreePage.MAX_ENTRIES / 2; key++) {
      assertEquals(StatusCode.OK, first.insert(0, key, row(key + 1L)));
    }
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    for (int key = BTreePage.MAX_ENTRIES / 2; key <= BTreePage.MAX_ENTRIES; key++) {
      assertEquals(StatusCode.OK, second.insert(0, key, row(key + 1L)));
    }
    long[] sequences = publish(manager, table, first, second);
    assertNotEquals(originalRoot, table.rootPageId());
    assertTrue(table.nextPageId() > originalNextPage);
    assertMemberSnapshots(table, sequences);

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.open(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(4), storeResult));
    table = openTable(storeResult.store());
    assertMemberSnapshots(table, sequences);
    assertNotEquals(originalRoot, table.rootPageId());
    close(table, wal, directory);
  }

  @Test
  void cancellationBeforeForceKeepsFrontierInvisibleAndAllowsNextCommit(
      @TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = manager(table, 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext context = context(manager, table, vacuum);
    IndexedTransactionSession first = session(context);
    IndexedTransactionSession second = session(context);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert(0, 900, row(9_000)));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.insert(0, 901, row(9_010)));
    long frontier = table.currentCommitSequence();
    long rows = table.rowCount();
    assertEquals(StatusCode.OK, first.prepareLogicalCommit());
    assertEquals(StatusCode.OK, second.prepareLogicalCommit());
    Transaction[] transactions = {first.groupTransaction(), second.groupTransaction()};
    TransactionOutcome[] outcomes = {new TransactionOutcome(), new TransactionOutcome()};
    assertEquals(StatusCode.OK, manager.prepareCommit(transactions[0], outcomes[0]));
    assertEquals(StatusCode.OK, manager.prepareCommit(transactions[1], outcomes[1]));
    IndexedPreparedLogicalCommit[] prepared = {
        first.preparedCommit(), second.preparedCommit()
    };
    assertEquals(StatusCode.OK, table.reserveHybridCommitGroupCapacity(prepared.length));
    assertEquals(StatusCode.OK, table.preflightHybridCommitGroup(
        prepared, prepared.length, manager.oldestVisibleCommitSequence()));
    assertEquals(frontier, table.currentCommitSequence());
    assertMissing(table, frontier, 900);
    assertMissing(table, frontier, 901);

    assertEquals(StatusCode.OK, table.cancelCommitGroup());
    assertEquals(StatusCode.OK, manager.abortPreparedCommitGroup(
        transactions, outcomes, transactions.length, StatusCode.CANCELLED));
    assertEquals(StatusCode.CANCELLED, first.completeCoordinatedCommit(StatusCode.CANCELLED));
    assertEquals(StatusCode.CANCELLED, second.completeCoordinatedCommit(StatusCode.CANCELLED));
    assertEquals(frontier, table.currentCommitSequence());
    assertEquals(rows, table.rowCount());
    assertMissing(table, frontier, 900);
    assertMissing(table, frontier, 901);

    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert(0, 900, row(9_000)));
    assertEquals(StatusCode.OK, first.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    assertEquals(frontier + 1, outcome.commitSequence());
    assertValue(table, outcome.commitSequence(), 900, 9_000);
    assertMissing(table, outcome.commitSequence(), 901);
    close(table, wal, directory);
  }

  private static long[] publish(
      TransactionManager manager,
      IndexedTable table,
      IndexedTransactionSession first,
      IndexedTransactionSession second) {
    IndexedTransactionSession[] sessions = {first, second};
    assertEquals(StatusCode.OK, first.prepareLogicalCommit());
    assertEquals(StatusCode.OK, second.prepareLogicalCommit());
    IndexedPreparedLogicalCommit[] prepared = {
        first.preparedCommit(), second.preparedCommit()
    };
    assertEquals(StatusCode.OK, table.reserveHybridCommitGroupCapacity(prepared.length));
    Transaction[] transactions = {first.groupTransaction(), second.groupTransaction()};
    TransactionOutcome[] outcomes = {new TransactionOutcome(), new TransactionOutcome()};
    long[] sequences = new long[2];
    long[] committedRows = new long[2];
    long frontier = table.currentCommitSequence();
    assertEquals(StatusCode.OK,
        manager.prepareCommit(transactions[0], outcomes[0]));
    assertEquals(StatusCode.OK,
        manager.prepareCommit(transactions[1], outcomes[1]));
    assertEquals(StatusCode.OK, table.preflightHybridCommitGroup(
        prepared, prepared.length, manager.oldestVisibleCommitSequence()));
    assertEquals(StatusCode.OK, manager.beginCommitGroup(transactions, transactions.length));
    assertEquals(StatusCode.OK,
        table.appendHybridCommitGroup(
            prepared, sequences, committedRows, prepared.length));
    assertEquals(0, first.committedSequence());
    assertEquals(0, second.committedSequence());
    assertEquals(StatusCode.OK, table.forceHybridCommitGroup());
    assertEquals(StatusCode.OK, table.prepareForcedGroupPublication());
    assertEquals(frontier, table.currentCommitSequence());
    assertEquals(StatusCode.OK, manager.publishCommitGroup(
        transactions, outcomes, sequences, transactions.length, table,
        new TransactionGroupCompletionTimings()));
    assertEquals(StatusCode.OK, first.completeCoordinatedCommit(StatusCode.OK));
    assertEquals(StatusCode.OK, second.completeCoordinatedCommit(StatusCode.OK));
    assertEquals(TransactionState.COMMITTED, outcomes[0].state());
    assertEquals(TransactionState.COMMITTED, outcomes[1].state());
    assertEquals(sequences[0], outcomes[0].commitSequence());
    assertEquals(sequences[1], outcomes[1].commitSequence());
    return sequences;
  }

  private static void assertMemberSnapshots(IndexedTable table, long[] sequences) {
    int boundary = BTreePage.MAX_ENTRIES / 2;
    for (int key = 0; key <= BTreePage.MAX_ENTRIES; key++) {
      if (key < boundary) assertValue(table, sequences[0], key, key + 1L);
      else assertMissing(table, sequences[0], key);
      assertValue(table, sequences[1], key, key + 1L);
    }
  }

  private static void assertValue(
      IndexedTable table, long sequence, long key, long expected) {
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKeyAt(sequence, 0, key, fetched));
    assertEquals(expected, value(fetched));
  }

  private static void assertMissing(IndexedTable table, long sequence, long key) {
    assertEquals(StatusCode.CONFLICT,
        table.fetchByKeyAt(sequence, 0, key, new HeapRowResult()));
  }

  private static long value(HeapRowResult result) {
    ByteBuffer target = ByteBuffer.allocate(result.length());
    assertEquals(StatusCode.OK, result.copyTo(target));
    return target.getLong(0);
  }

  private static ByteBuffer row(long value) {
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    return row;
  }

  private static TransactionManager manager(IndexedTable table, int capacity) {
    return new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), capacity);
  }

  private static IndexedSessionContext context(
      TransactionManager manager, IndexedTable table, IndexedVacuum vacuum) {
    IndexedSessionContext.Result result = new IndexedSessionContext.Result();
    assertEquals(StatusCode.OK, IndexedSessionContext.bind(
        manager, table, null, vacuum, result));
    return result.context();
  }

  private static IndexedTransactionSession session(IndexedSessionContext context) {
    IndexedTransactionSessionOpenResult result = new IndexedTransactionSessionOpenResult();
    assertEquals(StatusCode.OK, context.openSession(Long.BYTES, result));
    return result.session();
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

  private static IndexedTable openTable(IndexedTableStore store) {
    IndexedTableOpenResult result = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(store, result));
    return result.table();
  }

  private static void close(
      IndexedTable table, LocalWal wal, NioDurableDirectory directory) {
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }
}
