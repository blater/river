package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.page.IndexedPageStore;
import io.riverdb.engine.page.IndexedPageStoreOpenResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.heap.HeapInsertResult;
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
    assertEquals(StatusCode.CONFLICT, second.insert(9, row(92)));
    assertEquals(StatusCode.OK, second.abort(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(9, fetched));
    assertEquals(91, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void serializableMissingKeyReadBlocksInsertUntilReadOnlyCommit(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession reader = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, reader.fetchByKey(88, fetched));
    assertEquals(StatusCode.RETRY, writer.insert(88, row(880)));
    assertEquals(1, manager.activeLockCount());
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    assertEquals(0, manager.activeLockCount());
    assertEquals(StatusCode.OK, writer.insert(88, row(880)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, table.fetchByKey(88, fetched));
    assertEquals(880, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void serializableReaderCanUpgradeMissingKeyToInsert(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession session = session(manager, table);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, session.fetchByKey(99, fetched));
    assertEquals(StatusCode.OK, session.insert(99, row(990)));
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.fetchByKey(99, fetched));
    assertEquals(990, value(fetched));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void serializableScanValidatesPhantomsAtPublication(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession reader = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult scanned = new IndexedScanResult();
    TransactionOutcome outcome = new TransactionOutcome();

    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, reader.beginScan(0, 100, cursor));
    assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, scanned));
    assertEquals(StatusCode.OK, reader.closeScan(cursor));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert(50, row(500)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.CONFLICT, reader.commit(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(0, manager.activeTransactionCount());

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, reader.beginScan(0, 100, cursor));
    assertEquals(StatusCode.OK, reader.nextScan(cursor, scanned));
    assertEquals(50, scanned.key());
    assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, scanned));
    assertEquals(StatusCode.OK, reader.closeScan(cursor));
    assertEquals(StatusCode.OK, reader.insert(60, row(600)));
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(60, fetched));
    assertEquals(600, value(fetched));
    assertEquals(0, manager.activeLockCount());
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
  void savepointRollsBackPendingRowsButRetainsLocks(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession session = session(manager, table);
    IndexedTransactionSession contender = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(10, row(100)));
    IndexedSavepoint savepoint = new IndexedSavepoint();
    assertEquals(StatusCode.OK, session.createSavepoint(savepoint));
    assertEquals(StatusCode.OK, session.insert(20, row(200)));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(savepoint));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, session.fetchByKey(20, fetched));
    assertEquals(StatusCode.OK, contender.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.RETRY, contender.insert(20, row(201)));
    assertEquals(StatusCode.OK, session.releaseSavepoint(savepoint));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, contender.insert(20, row(201)));
    assertEquals(StatusCode.OK, contender.commit(outcome));
    assertEquals(StatusCode.OK, table.fetchByKey(10, fetched));
    assertEquals(100, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey(20, fetched));
    assertEquals(201, value(fetched));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void outerSavepointInvalidatesNestedSavepoints(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession session = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(10, row(100)));
    IndexedSavepoint outer = new IndexedSavepoint();
    IndexedSavepoint inner = new IndexedSavepoint();
    assertEquals(StatusCode.OK, session.createSavepoint(outer));
    assertEquals(StatusCode.OK, session.insert(20, row(200)));
    assertEquals(StatusCode.OK, session.createSavepoint(inner));
    assertEquals(StatusCode.OK, session.insert(30, row(300)));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(outer));
    assertEquals(false, inner.isActive());
    assertEquals(StatusCode.NOT_OWNER, session.releaseSavepoint(inner));
    assertEquals(StatusCode.OK, session.releaseSavepoint(outer));
    assertEquals(StatusCode.OK, session.commit(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(10, fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey(20, fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey(30, fetched));
    close(table, wal, directory);
  }

  @Test
  void publishesMultipleRowsAtOneCommitSequence(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession writer = session(manager, table);
    IndexedTransactionSession reader = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert(31, row(311)));
    assertEquals(StatusCode.OK, writer.insert(32, row(322)));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, writer.fetchByKey(31, fetched));
    assertEquals(311, value(fetched));
    assertEquals(StatusCode.OK, writer.fetchByKey(32, fetched));
    assertEquals(322, value(fetched));
    assertEquals(StatusCode.CONFLICT, reader.fetchByKey(31, fetched));
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.commit(outcome));
    long committedAt = outcome.commitSequence();
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(committedAt - 1, 31, fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(committedAt - 1, 32, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt, 31, fetched));
    assertEquals(311, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt, 32, fetched));
    assertEquals(322, value(fetched));
    assertEquals(StatusCode.OK, reader.abort(outcome));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void duplicateInWriteSetRollsBackEveryStagedRow(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession seed = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert(40, row(400)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert(41, row(410)));
    assertEquals(StatusCode.CONFLICT, writer.insert(40, row(401)));
    assertEquals(StatusCode.OK, writer.abort(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, table.fetchByKey(41, fetched));
    assertEquals(StatusCode.OK, table.fetchByKey(40, fetched));
    assertEquals(400, value(fetched));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void multiWriteCommitCanPublishLeafSplit(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    HeapInsertResult inserted = new HeapInsertResult();
    for (int key = 0; key < 255; key++) {
      assertEquals(StatusCode.OK, table.insert(key + 2L, key, row(key), inserted));
    }
    int oldRoot = table.rootPageId();
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert(1000, row(10000)));
    assertEquals(StatusCode.OK, writer.insert(1001, row(10010)));
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.commit(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(1000, fetched));
    assertEquals(10000, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey(1001, fetched));
    assertEquals(10010, value(fetched));
    assertNotEquals(oldRoot, table.rootPageId());
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void mixedMutationsPreserveOldSnapshotAndRecoverBeforeFlush(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 6);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(manager, table);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert(60, row(600)));
    assertEquals(StatusCode.OK, seed.insert(61, row(610)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedTransactionSession oldReader = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, oldReader.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update(60, row(601)));
    assertEquals(StatusCode.OK, writer.delete(61));
    assertEquals(StatusCode.OK, writer.insert(62, row(620)));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, writer.fetchByKey(60, fetched));
    assertEquals(601, value(fetched));
    assertEquals(StatusCode.CONFLICT, writer.fetchByKey(61, fetched));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    long mutatedAt = outcome.commitSequence();

    assertEquals(StatusCode.OK, oldReader.fetchByKey(60, fetched));
    assertEquals(600, value(fetched));
    assertEquals(StatusCode.OK, oldReader.fetchByKey(61, fetched));
    assertEquals(610, value(fetched));
    assertEquals(StatusCode.CONFLICT, oldReader.fetchByKey(62, fetched));
    assertEquals(StatusCode.OK, table.fetchByKey(60, fetched));
    assertEquals(601, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey(61, fetched));
    assertEquals(StatusCode.OK, table.fetchByKey(62, fetched));
    assertEquals(620, value(fetched));
    assertEquals(StatusCode.OK, oldReader.abort(outcome));

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
    assertEquals(StatusCode.OK, table.fetchByKeyAt(mutatedAt - 1, 60, fetched));
    assertEquals(600, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(mutatedAt - 1, 61, fetched));
    assertEquals(610, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(mutatedAt, 60, fetched));
    assertEquals(601, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(mutatedAt, 61, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(mutatedAt, 62, fetched));
    assertEquals(620, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void staleRepeatableReadCannotOverwriteNewerVersion(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(manager, table);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert(75, row(750)));
    assertEquals(StatusCode.OK, seed.commit(outcome));
    IndexedTransactionSession stale = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, stale.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update(75, row(751)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.CONFLICT, stale.update(75, row(752)));
    assertEquals(StatusCode.OK, stale.abort(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(75, fetched));
    assertEquals(751, value(fetched));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void deleteThenReinsertPreservesEachSnapshotVersion(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 7);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(manager, table);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert(85, row(850)));
    assertEquals(StatusCode.OK, seed.commit(outcome));
    long insertedAt = outcome.commitSequence();

    IndexedTransactionSession beforeDelete = session(manager, table);
    assertEquals(StatusCode.OK, beforeDelete.begin(IsolationLevel.REPEATABLE_READ));
    IndexedTransactionSession deleter = session(manager, table);
    assertEquals(StatusCode.OK, deleter.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, deleter.delete(85));
    assertEquals(StatusCode.OK, deleter.commit(outcome));
    long deletedAt = outcome.commitSequence();

    IndexedTransactionSession afterDelete = session(manager, table);
    assertEquals(StatusCode.OK, afterDelete.begin(IsolationLevel.REPEATABLE_READ));
    IndexedTransactionSession reinserter = session(manager, table);
    assertEquals(StatusCode.OK, reinserter.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, reinserter.insert(85, row(851)));
    assertEquals(StatusCode.OK, reinserter.commit(outcome));
    long reinsertedAt = outcome.commitSequence();

    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, beforeDelete.fetchByKey(85, fetched));
    assertEquals(850, value(fetched));
    assertEquals(StatusCode.CONFLICT, afterDelete.fetchByKey(85, fetched));
    assertEquals(StatusCode.OK, table.fetchByKey(85, fetched));
    assertEquals(851, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(insertedAt, 85, fetched));
    assertEquals(850, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(deletedAt, 85, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(reinsertedAt, 85, fetched));
    assertEquals(851, value(fetched));
    assertEquals(StatusCode.OK, beforeDelete.abort(outcome));
    assertEquals(StatusCode.OK, afterDelete.abort(outcome));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void quiescentVacuumReclaimsVersionsAndRecoversBeforePageFlush(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(manager, table);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert(201, row(2010)));
    assertEquals(StatusCode.OK, seed.insert(202, row(2020)));
    assertEquals(StatusCode.OK, seed.insert(203, row(2030)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update(201, row(2011)));
    assertEquals(StatusCode.OK, writer.delete(202));
    assertEquals(StatusCode.OK, writer.delete(203));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    IndexedTransactionSession reinserter = session(manager, table);
    assertEquals(StatusCode.OK, reinserter.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, reinserter.insert(202, row(2021)));
    assertEquals(StatusCode.OK, reinserter.commit(outcome));
    assertEquals(7, table.rowCount());

    IndexedTransactionSession snapshot = session(manager, table);
    assertEquals(StatusCode.OK, snapshot.begin(IsolationLevel.REPEATABLE_READ));
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    assertEquals(StatusCode.RETRY, vacuum.run(outcome));
    assertEquals(7, table.rowCount());
    assertEquals(StatusCode.OK, snapshot.abort(outcome));

    long stagedBefore = table.stagedCopyBytes();
    long walCopiedBefore = table.walCopyBytes();
    assertEquals(StatusCode.OK, vacuum.run(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    assertEquals(7, vacuum.result().rowsBefore());
    assertEquals(3, vacuum.result().rowsAfter());
    assertEquals(4, vacuum.result().rowsReclaimed());
    assertEquals(3, table.rowCount());
    assertEquals(
        2L * io.riverdb.format.page.PageCodec.PAGE_BYTES,
        table.stagedCopyBytes() - stagedBefore);
    assertEquals(17, table.walCopyBytes() - walCopiedBefore);
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(201, fetched));
    assertEquals(2011, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey(202, fetched));
    assertEquals(2021, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey(203, fetched));
    assertEquals(StatusCode.CONFLICT, vacuum.run(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(3, table.rowCount());

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
    assertEquals(3, table.rowCount());
    assertEquals(StatusCode.OK, table.fetchByKey(201, fetched));
    assertEquals(2011, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey(202, fetched));
    assertEquals(2021, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey(203, fetched));
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
    assertEquals(StatusCode.OK, writer.insert(502, row(5020)));
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
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(committedAt - 1, 502, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt, 502, fetched));
    assertEquals(5020, value(fetched));
    TransactionManager restartedManager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession restarted = session(restartedManager, table);
    assertEquals(StatusCode.OK, restarted.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(committedTransactionId + 1, restarted.transaction().transactionId());
    assertEquals(StatusCode.OK, restarted.abort(outcome));
    close(table, wal, directory);
  }

  @Test
  void orderedScanCrossesLeavesAndRetainsSnapshotVersions(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 6);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(manager, table);
    for (int batch = 0; batch < 5; batch++) {
      assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
      int first = batch * 52;
      for (int key = first; key < first + 52; key++) {
        assertEquals(StatusCode.OK, seed.insert(key, row(key * 10L)));
      }
      assertEquals(StatusCode.OK, seed.commit(outcome));
    }
    IndexedTransactionSession snapshot = session(manager, table);
    assertEquals(StatusCode.OK, snapshot.begin(IsolationLevel.REPEATABLE_READ));
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update(10, row(101)));
    assertEquals(StatusCode.OK, writer.delete(20));
    assertEquals(StatusCode.OK, writer.insert(1000, row(10_000)));
    assertEquals(StatusCode.OK, writer.commit(outcome));

    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult scanned = new IndexedScanResult();
    assertEquals(StatusCode.OK, snapshot.beginScan(0, Long.MAX_VALUE, cursor));
    int count = 0;
    StatusCode scanStatus;
    while ((scanStatus = snapshot.nextScan(cursor, scanned)).isOk()) {
      assertEquals(count, scanned.key());
      assertEquals(count * 10L, value(scanned.row()));
      count++;
    }
    assertEquals(StatusCode.CONFLICT, scanStatus);
    assertEquals(260, count);
    assertEquals(StatusCode.OK, snapshot.closeScan(cursor));
    assertEquals(StatusCode.OK, snapshot.abort(outcome));

    IndexedTransactionSession current = session(manager, table);
    assertEquals(StatusCode.OK, current.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(StatusCode.OK, current.beginScan(0, Long.MAX_VALUE, cursor));
    count = 0;
    long previous = -1;
    while ((scanStatus = current.nextScan(cursor, scanned)).isOk()) {
      assertEquals(true, scanned.key() > previous);
      previous = scanned.key();
      if (scanned.key() == 10) {
        assertEquals(101, value(scanned.row()));
      }
      assertNotEquals(20, scanned.key());
      count++;
    }
    assertEquals(StatusCode.CONFLICT, scanStatus);
    assertEquals(260, count);
    assertEquals(1000, previous);
    assertEquals(StatusCode.OK, current.closeScan(cursor));
    assertEquals(StatusCode.OK, current.commit(outcome));
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
