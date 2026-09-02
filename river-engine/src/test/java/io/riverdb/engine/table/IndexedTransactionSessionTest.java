package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
  void readCommittedLocksAndUpdatesTheCurrentSuccessorAfterAWait(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession seed = session(manager, table);
    IndexedTransactionSession claimant = session(manager, table);
    IndexedTransactionSession blocker = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, seed.insert(0, 81, row(8101)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedRowCandidate candidate = new IndexedRowCandidate();
    IndexedLockedRow current = new IndexedLockedRow();
    assertEquals(StatusCode.OK, claimant.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, claimant.beginStatement());
    assertEquals(StatusCode.OK, claimant.fetchCandidateByKey(0, 81, candidate));
    assertEquals(8101, value(candidate.row()));
    assertEquals(StatusCode.OK, blocker.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, blocker.update(0, 81, row(8102)));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> locked = executor.submit(() -> claimant.lockCurrent(candidate, current));
      awaitOneLockWait(manager);
      assertEquals(StatusCode.OK, blocker.commit(outcome));
      assertEquals(StatusCode.OK, locked.get());
    } finally {
      executor.shutdownNow();
    }
    assertNotEquals(candidate.versionRowId(), current.currentVersionRowId());
    assertEquals(8102, value(current.row()));
    assertEquals(StatusCode.OK, claimant.updateLocked(current, row(8103)));
    assertEquals(StatusCode.OK, claimant.completeStatement());
    assertEquals(StatusCode.OK, claimant.commit(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(0, 81, fetched));
    assertEquals(8103, value(fetched));
    assertEquals(0, manager.waitingLockCount());
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void repeatableReadLockCurrentReturnsANewerCurrentSuccessor(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession seed = session(manager, table);
    IndexedTransactionSession claimant = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, seed.insert(0, 82, row(8201)));
    assertEquals(StatusCode.OK, seed.commit(outcome));
    assertEquals(StatusCode.OK, claimant.begin(IsolationLevel.REPEATABLE_READ));
    IndexedRowCandidate candidate = new IndexedRowCandidate();
    assertEquals(StatusCode.OK, claimant.fetchCandidateByKey(0, 82, candidate));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, writer.update(0, 82, row(8202)));
    assertEquals(StatusCode.OK, writer.commit(outcome));

    IndexedLockedRow current = new IndexedLockedRow();
    assertEquals(StatusCode.OK, claimant.lockCurrent(candidate, current));
    assertEquals(8202, value(current.row()));
    assertEquals(StatusCode.OK, claimant.releaseLocked(current));
    assertEquals(StatusCode.OK, claimant.abort(outcome));
    close(table, wal, directory);
  }

  @Test
  void lockCurrentRejectsDeleteReinsertAsAReplacementRow(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession seed = session(manager, table);
    IndexedTransactionSession claimant = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, seed.insert(0, 83, row(8301)));
    assertEquals(StatusCode.OK, seed.commit(outcome));
    assertEquals(StatusCode.OK, claimant.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, claimant.beginStatement());
    IndexedRowCandidate candidate = new IndexedRowCandidate();
    assertEquals(StatusCode.OK, claimant.fetchCandidateByKey(0, 83, candidate));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, writer.delete(0, 83));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, writer.insert(0, 83, row(8302)));
    assertEquals(StatusCode.OK, writer.commit(outcome));

    assertEquals(StatusCode.CONFLICT,
        claimant.lockCurrent(candidate, new IndexedLockedRow()));
    assertEquals(StatusCode.OK, claimant.completeStatement());
    assertEquals(StatusCode.OK, claimant.abort(outcome));
    close(table, wal, directory);
  }

  @Test
  void ownPendingCandidateStagesWithoutRepeatingCurrentDiscovery(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 2);
    IndexedTransactionSession session = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, session.insert(0, 84, row(8401)));
    IndexedRowCandidate candidate = new IndexedRowCandidate();
    IndexedLockedRow current = new IndexedLockedRow();
    assertEquals(StatusCode.OK, session.fetchCandidateByKey(0, 84, candidate));
    assertEquals(StatusCode.OK, session.lockCurrent(candidate, current));
    assertEquals(StatusCode.OK, session.updateLocked(current, row(8402)));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        session.updateLocked(current, row(8403)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(0, 84, fetched));
    assertEquals(8402, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void scanResultsCarryCommittedAndPendingIdentityIntoLockCurrent(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 3);
    IndexedTransactionSession seed = session(manager, table);
    IndexedTransactionSession reader = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, seed.insert(0, 85, row(8501)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult scanned = new IndexedScanResult();
    IndexedLockedRow current = new IndexedLockedRow();
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, reader.beginScan(0, 85, 0, 86, cursor));
    assertEquals(StatusCode.OK, reader.nextScan(cursor, scanned));
    assertFalse(scanned.isPending());
    assertTrue(scanned.versionRowId() > 0);
    assertEquals(StatusCode.OK, reader.lockCurrent(scanned, current));
    assertEquals(8501, value(current.row()));
    assertEquals(StatusCode.OK, reader.releaseLocked(current));
    assertEquals(StatusCode.OK, reader.closeScan(cursor));
    assertEquals(StatusCode.OK, reader.abort(outcome));

    assertEquals(StatusCode.OK, cursor.reset());
    scanned.reset();
    current.reset();
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, reader.insert(0, 86, row(8601)));
    assertEquals(StatusCode.OK, reader.beginScan(0, 86, 0, 87, cursor));
    assertEquals(StatusCode.OK, reader.nextScan(cursor, scanned));
    assertTrue(scanned.isPending());
    assertEquals(0, scanned.versionRowId());
    assertEquals(StatusCode.OK, reader.lockCurrent(scanned, current));
    assertEquals(StatusCode.OK, reader.deleteLocked(current));
    assertEquals(StatusCode.OK, reader.closeScan(cursor));
    assertEquals(StatusCode.OK, reader.commit(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, table.fetchByKey(0, 86, fetched));
    close(table, wal, directory);
  }

  @Test
  void savepointRollbackTruncatesTupleLifecycleRequests(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession session = session(manager, table);
    TupleShape.Result shape = new TupleShape.Result();
    assertEquals(StatusCode.OK,
        TupleShape.create(new int[] {SqlTypeDescriptor.BIGINT}, shape));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    IndexedSavepoint statement = new IndexedSavepoint();
    assertEquals(StatusCode.OK, session.createSavepoint(statement));
    assertEquals(StatusCode.OK, session.preflightTupleIndexLifecycles(1));
    assertEquals(StatusCode.OK, session.stageTupleIndexBuilding(
        21, 31, 41, 41, shape.value()));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(statement));
    assertEquals(StatusCode.OK, session.releaseSavepoint(statement));
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT,
        table.fetchByKey(CatalogKeyspace.INDEX_ROOT_SPACE, 31, row));
    close(table, wal, directory);
  }

  @Test
  void callerOwnedRelationalGroupCommitsThroughSession(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession session = session(manager, table);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.reserveLogicalRowIds(
        19, 1, new IndexedLogicalRowIdReservation()));
    IndexedRelationalMutation mutation = relationalBaseMutation(1911);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.commitRelational(mutation, outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    HeapRowResult fetched = new HeapRowResult();
    long space = CatalogKeyspace.relationalBaseRowSpace(19);
    assertEquals(StatusCode.OK, table.fetchByKey(space, 1, fetched));
    assertEquals(1911, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void relationalCommitRetainsActiveRepeatableReadPageGenerations(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(
        directory, wal, new IndexedPageCacheConfig(8, 4, 0)));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 16);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(manager, table);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert(77, 23, row(2301)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedTransactionSession reader = session(manager, table);
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, reader.fetchByKey(77, 23, fetched));
    assertEquals(2301, value(fetched));
    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult scanned = new IndexedScanResult();
    assertEquals(StatusCode.OK, reader.beginScan(77, 23, 77, 24, cursor));

    StatusCode pressure = StatusCode.OK;
    long latest = 2301;
    long rowsBeforeFailure = 0;
    long sequenceBeforeFailure = 0;
    IndexedTransactionSession failedWriter = null;
    for (int attempt = 0; attempt < 16 && pressure.isOk(); attempt++) {
      latest++;
      IndexedTransactionSession writer = session(manager, table);
      assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
      rowsBeforeFailure = table.rowCount();
      sequenceBeforeFailure = table.currentCommitSequence();
      pressure = writer.commitRelational(
          relationalScalarUpdate(table, 77, 23, latest), outcome);
      if (!pressure.isOk()) failedWriter = writer;
    }
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, pressure, "value=" + latest);
    assertTrue(failedWriter != null);
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(false, failedWriter.transactionLifecycleActive());
    assertEquals(rowsBeforeFailure, table.rowCount());
    assertEquals(sequenceBeforeFailure, table.currentCommitSequence());
    assertEquals(1, manager.activeTransactionCount());
    assertEquals(StatusCode.OK, reader.nextScan(cursor, scanned));
    assertEquals(2301, value(scanned.row()));
    assertEquals(StatusCode.OK, reader.closeScan(cursor));
    assertEquals(StatusCode.OK, reader.abort(outcome));
    assertEquals(0, manager.activeTransactionCount());

    IndexedTransactionSession resumed = session(manager, table);
    assertEquals(StatusCode.OK, resumed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, resumed.commitRelational(
        relationalScalarUpdate(table, 77, 23, latest + 1), outcome));
    assertEquals(StatusCode.OK, table.fetchByKey(77, 23, fetched));
    assertEquals(latest + 1, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void relationalGroupAtomicallyCommitsGenericScalarAndBaseRows(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession session = session(manager, table);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.reserveLogicalRowIds(
        19, 1, new IndexedLogicalRowIdReservation()));
    IndexedRelationalMutation mutation = new IndexedRelationalMutation(2, 0, 0);
    assertEquals(StatusCode.OK, mutation.reserve(2, 0, 0, 2 * Long.BYTES));
    assertEquals(StatusCode.OK, mutation.appendLogicalRowFloor(19, 2));
    assertEquals(StatusCode.OK, mutation.appendSuboperation(
        0, IndexedRelationalMutation.SCALAR_SUBOPERATION, 0, 1,
        0, 0, 3, 3, 4, 4, 0, 0, 0, 1,
        IndexedRelationalMutation.REGISTRY_ABSENT,
        IndexedRelationalMutation.REGISTRY_ABSENT, 0, 0));
    assertEquals(StatusCode.OK, mutation.appendSuboperation(
        19, -1, 1, 1, 0, 0, 3, 3, 4, 4, 0, 0, 1, 2,
        IndexedRelationalMutation.REGISTRY_ABSENT,
        IndexedRelationalMutation.REGISTRY_ABSENT, 0, 0));
    ByteBuffer scalar = row(3117);
    assertEquals(StatusCode.OK, mutation.appendScalar(
        0, IndexedRelationalMutation.SCALAR_INSERT, 77, 23, 0,
        scalar, 0, scalar.remaining()));
    ByteBuffer base = row(1917);
    assertEquals(StatusCode.OK, mutation.appendBase(
        1, 19, IndexedRelationalMutation.BASE_INSERT, 1, 0,
        base, 0, base.remaining()));
    assertEquals(StatusCode.OK, mutation.seal());
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.commitRelational(mutation, outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());

    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(77, 23, fetched));
    assertEquals(3117, value(fetched));
    long baseSpace = CatalogKeyspace.relationalBaseRowSpace(19);
    assertEquals(StatusCode.OK, table.fetchByKey(baseSpace, 1, fetched));
    assertEquals(1917, value(fetched));
    close(table, wal, directory);
  }

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
    assertEquals(StatusCode.OK, writer.insert( 0,71, row(7101)));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, writer.fetchByKey( 0,71, fetched));
    assertEquals(7101, value(fetched));
    assertEquals(StatusCode.CONFLICT, repeatable.fetchByKey( 0,71, fetched));

    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    assertEquals(StatusCode.CONFLICT, repeatable.fetchByKey( 0,71, fetched));
    assertEquals(StatusCode.OK, readCommitted.fetchByKey( 0,71, fetched));
    assertEquals(7101, value(fetched));
    assertEquals(StatusCode.OK, repeatable.abort(outcome));
    assertEquals(StatusCode.OK, readCommitted.abort(outcome));
    close(table, wal, directory);
  }

  @Test
  void readCommittedPinsOneSnapshotAcrossAStatement(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 8);
    IndexedTransactionSession reader = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    HeapRowResult fetched = new HeapRowResult();

    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, reader.beginStatement());
    assertEquals(StatusCode.CONFLICT, reader.fetchByKey( 0,72, fetched));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert( 0,72, row(7201)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.CONFLICT, reader.fetchByKey( 0,72, fetched));
    assertEquals(StatusCode.CONFLICT, reader.commit(outcome));
    assertEquals(StatusCode.OK, reader.completeStatement());

    assertEquals(StatusCode.OK, reader.beginStatement());
    assertEquals(StatusCode.OK, reader.fetchByKey( 0,72, fetched));
    assertEquals(7201, value(fetched));
    assertEquals(StatusCode.OK, reader.completeStatement());
    assertEquals(StatusCode.OK, reader.commit(outcome));
    close(table, wal, directory);
  }

  @Test
  void concurrentUniqueConflictAbortsOnlyLosingTransaction(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession first = session(manager, table);
    IndexedTransactionSession second = session(manager, table);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert( 0,9, row(91)));
    TransactionOutcome outcome = new TransactionOutcome();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> losingInsert = executor.submit(() -> second.insert(0, 9, row(92)));
      awaitOneLockWait(manager);
      assertEquals(StatusCode.OK, first.commit(outcome));
      assertEquals(StatusCode.CONFLICT, losingInsert.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, second.abort(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,9, fetched));
    assertEquals(91, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void tupleKeyLockCancellationDoesNotSerializeUnrelatedKeys(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession first = session(manager, table);
    IndexedTransactionSession second = session(manager, table);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    long keyId = 19;
    ByteBuffer firstKey = genericFixedTuple(101);
    ByteBuffer secondKey = genericFixedTuple(202);
    int firstOffset = IndexedTupleLockKey.userOffset(firstKey, 0, firstKey.remaining());
    int firstLength = IndexedTupleLockKey.userLength(firstKey, 0, firstKey.remaining());
    int secondOffset = IndexedTupleLockKey.userOffset(secondKey, 0, secondKey.remaining());
    int secondLength = IndexedTupleLockKey.userLength(secondKey, 0, secondKey.remaining());
    assertEquals(StatusCode.OK, first.protectKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId));
    assertEquals(StatusCode.OK, second.protectKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId));
    assertEquals(StatusCode.OK, first.tryAcquireTupleKey(
        keyId, firstKey, firstOffset, firstLength,
        io.riverdb.tx.api.lock.LockMode.EXCLUSIVE));
    assertEquals(StatusCode.RETRY, second.tryAcquireTupleKey(
        keyId, firstKey, firstOffset, firstLength,
        io.riverdb.tx.api.lock.LockMode.EXCLUSIVE));
    assertEquals(StatusCode.OK, second.cancelLockWait());
    assertEquals(StatusCode.OK, second.tryAcquireTupleKey(
        keyId, secondKey, secondOffset, secondLength,
        io.riverdb.tx.api.lock.LockMode.EXCLUSIVE));
    long nonuniqueKeyId = 23;
    assertEquals(StatusCode.OK,
        first.protectKey(CatalogKeyspace.INDEX_ROOT_SPACE, nonuniqueKeyId));
    assertEquals(StatusCode.RETRY,
        second.tryAcquireExclusiveKey(CatalogKeyspace.INDEX_ROOT_SPACE, nonuniqueKeyId));
    assertEquals(StatusCode.OK, second.cancelLockWait());
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, first.abort(outcome));
    assertEquals(StatusCode.OK, second.abort(outcome));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void deadlockAbortsDeterministicVictimAndSurvivorCommits(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession first = session(manager, table);
    IndexedTransactionSession second = session(manager, table);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert( 0,1, row(101)));
    assertEquals(StatusCode.OK, second.insert( 0,2, row(202)));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<StatusCode> survivor;
    try {
      survivor = executor.submit(() -> first.insert(0, 2, row(102)));
      awaitOneLockWait(manager);
      assertEquals(StatusCode.DEADLOCK, second.insert(0, 1, row(201)));
      assertEquals(StatusCode.OK, survivor.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, manager.deadlockVictimSelections());

    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.CONFLICT, second.commit(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(StatusCode.OK, first.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,1, fetched));
    assertEquals(101, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,2, fetched));
    assertEquals(102, value(fetched));
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    assertEquals(0, manager.waitingLockCount());
    close(table, wal, directory);
  }

  @Test
  void serializableMissingKeyReadBlocksInsertUntilReadOnlyCommit(@TempDir Path root)
      throws Exception {
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
    assertEquals(StatusCode.CONFLICT, reader.fetchByKey( 0,88, fetched));
    TransactionOutcome outcome = new TransactionOutcome();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> insert = executor.submit(() -> writer.insert(0, 88, row(880)));
      awaitOneLockWait(manager);
      assertEquals(StatusCode.OK, reader.commit(outcome));
      assertEquals(StatusCode.OK, insert.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,88, fetched));
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
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, session.fetchByKey( 0,99, fetched));
    assertEquals(StatusCode.OK, session.insert( 0,99, row(990)));
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,99, fetched));
    assertEquals(990, value(fetched));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void serializableScanLocksPhantomsThroughPublication(@TempDir Path root)
      throws Exception {
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
    assertEquals(StatusCode.OK, reader.beginScan( 0,0, 0, 100, cursor));
    assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, scanned));
    assertEquals(StatusCode.OK, reader.closeScan(cursor));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> insert = executor.submit(() -> writer.insert(0, 50, row(500)));
      awaitOneLockWait(manager);
      assertEquals(StatusCode.OK, reader.commit(outcome));
      assertEquals(StatusCode.OK, insert.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(0, manager.activeTransactionCount());

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, reader.beginScan( 0,0, 0, 100, cursor));
    assertEquals(StatusCode.OK, reader.nextScan(cursor, scanned));
    assertEquals(50, scanned.key());
    assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, scanned));
    assertEquals(StatusCode.OK, reader.closeScan(cursor));
    assertEquals(StatusCode.OK, reader.insert( 0,60, row(600)));
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,60, fetched));
    assertEquals(600, value(fetched));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void serializableScanCapturesCurrentFrontierAfterQueuedRangeGrant(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession writer = session(manager, table);
    IndexedTransactionSession reader = session(manager, table);
    IndexedTransactionSession outside = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult scanned = new IndexedScanResult();

    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert(0, 50, row(500)));
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.SERIALIZABLE));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> beginScan = executor.submit(
          () -> reader.beginScan(0, 0, 0, 100, cursor));
      awaitOneLockWait(manager);
      assertEquals(StatusCode.OK, writer.commit(outcome));
      assertEquals(StatusCode.OK, beginScan.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, reader.nextScan(cursor, scanned));
    assertEquals(50, scanned.key());
    assertEquals(500, value(scanned.row()));
    assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, scanned));

    assertEquals(StatusCode.OK, outside.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, outside.insert(0, 150, row(1500)));
    assertEquals(StatusCode.OK, outside.commit(outcome));
    assertEquals(StatusCode.OK, reader.closeScan(cursor));
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void nestedScansRetainIndependentCursorOwnership(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession session = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert( 0,10, row(100)));
    assertEquals(StatusCode.OK, session.insert( 0,20, row(200)));
    assertEquals(StatusCode.OK, session.commit(outcome));

    IndexedScanCursor outer = new IndexedScanCursor();
    IndexedScanCursor inner = new IndexedScanCursor();
    IndexedScanResult result = new IndexedScanResult();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, session.beginScan( 0,0, 0, 100, outer));
    assertEquals(StatusCode.OK, session.nextScan(outer, result));
    assertEquals(10, result.key());
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert( 0,30, row(300)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, session.beginScan( 0,15, 0, 100, inner));
    assertEquals(StatusCode.OK, session.nextScan(inner, result));
    assertEquals(20, result.key());
    assertEquals(StatusCode.CONFLICT, session.nextScan(inner, result));
    assertEquals(StatusCode.OK, session.closeScan(inner));
    assertEquals(StatusCode.OK, session.nextScan(outer, result));
    assertEquals(20, result.key());
    assertEquals(StatusCode.CONFLICT, session.nextScan(outer, result));
    assertEquals(StatusCode.OK, session.closeScan(outer));
    assertEquals(StatusCode.OK, session.commit(outcome));
    close(table, wal, directory);
  }

  @Test
  void abortDiscardsPendingInsertAndReleasesKey(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession first = session(manager, table);
    IndexedTransactionSession second = session(manager, table);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert( 0,17, row(171)));
    TransactionOutcome outcome = new TransactionOutcome();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> insert = executor.submit(() -> second.insert(0, 17, row(172)));
      awaitOneLockWait(manager);
      assertEquals(StatusCode.OK, first.abort(outcome));
      assertEquals(StatusCode.OK, insert.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, second.commit(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,17, fetched));
    assertEquals(172, value(fetched));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void repeatedKeyMutationsReadLatestAndCommitOneEffectiveVersion(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession session = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert( 0,20, row(200)));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert( 0,10, row(100)));
    assertEquals(StatusCode.OK, session.update( 0,10, row(101)));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, session.fetchByKey( 0,10, fetched));
    assertEquals(101, value(fetched));
    IndexedSavepoint savepoint = new IndexedSavepoint();
    assertEquals(StatusCode.OK, session.createSavepoint(savepoint));
    assertEquals(StatusCode.OK, session.update( 0,10, row(102)));
    assertEquals(StatusCode.OK, session.delete( 0,10));
    assertEquals(StatusCode.CONFLICT, session.fetchByKey( 0,10, fetched));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(savepoint));
    assertEquals(StatusCode.OK, session.fetchByKey( 0,10, fetched));
    assertEquals(101, value(fetched));
    assertEquals(StatusCode.OK, session.releaseSavepoint(savepoint));

    assertEquals(StatusCode.OK, session.update( 0,20, row(201)));
    assertEquals(StatusCode.OK, session.update( 0,20, row(202)));
    assertEquals(StatusCode.OK, session.delete( 0,20));
    assertEquals(StatusCode.OK, session.insert( 0,20, row(203)));
    assertEquals(StatusCode.OK, session.insert( 0,30, row(300)));
    assertEquals(StatusCode.OK, session.delete( 0,30));

    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult scanned = new IndexedScanResult();
    assertEquals(StatusCode.OK, session.beginScan( 0,0, 0, 40, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanned));
    assertEquals(10, scanned.key());
    assertEquals(101, value(scanned.row()));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanned));
    assertEquals(20, scanned.key());
    assertEquals(203, value(scanned.row()));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, scanned));
    assertEquals(StatusCode.OK, session.closeScan(cursor));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, table.fetchByKey( 0,10, fetched));
    assertEquals(101, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,20, fetched));
    assertEquals(203, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,30, fetched));
    close(table, wal, directory);
  }

  @Test
  void savepointRollsBackPendingRowsButRetainsLocks(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession session = session(manager, table);
    IndexedTransactionSession contender = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert( 0,10, row(100)));
    IndexedSavepoint savepoint = new IndexedSavepoint();
    assertEquals(StatusCode.OK, session.createSavepoint(savepoint));
    assertEquals(StatusCode.OK, session.insert( 0,20, row(200)));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(savepoint));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, session.fetchByKey( 0,20, fetched));
    assertEquals(StatusCode.OK, contender.begin(IsolationLevel.REPEATABLE_READ));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> insert = executor.submit(() -> contender.insert(0, 20, row(201)));
      awaitOneLockWait(manager);
      assertEquals(StatusCode.OK, session.releaseSavepoint(savepoint));
      assertEquals(StatusCode.OK, session.commit(outcome));
      assertEquals(StatusCode.OK, insert.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, contender.commit(outcome));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,10, fetched));
    assertEquals(100, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,20, fetched));
    assertEquals(201, value(fetched));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void tupleKeySavepointRollbackRetainsExactProtection(
      @TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession child = session(manager, table);
    IndexedTransactionSession parent = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    ByteBuffer parentKey = genericFixedTuple(9753);
    long keyId = 31;

    assertEquals(StatusCode.OK, child.begin(IsolationLevel.REPEATABLE_READ));
    IndexedSavepoint savepoint = new IndexedSavepoint();
    assertEquals(StatusCode.OK, child.createSavepoint(savepoint));
    assertEquals(
        StatusCode.OK,
        child.protectTupleKey(keyId, parentKey, 0, parentKey.remaining()));
    assertEquals(StatusCode.OK, child.rollbackToSavepoint(savepoint));
    assertEquals(StatusCode.OK, parent.begin(IsolationLevel.REPEATABLE_READ));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> protection = executor.submit(
          () -> parent.protectTupleKeyForWrite(
              keyId, parentKey, 0, parentKey.remaining()));
      awaitOneLockWait(manager);
      assertEquals(StatusCode.OK, child.releaseSavepoint(savepoint));
      assertEquals(StatusCode.OK, child.commit(outcome));
      assertEquals(StatusCode.OK, protection.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, parent.abort(outcome));
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
    assertEquals(StatusCode.OK, session.insert( 0,10, row(100)));
    IndexedSavepoint outer = new IndexedSavepoint();
    IndexedSavepoint inner = new IndexedSavepoint();
    assertEquals(StatusCode.OK, session.createSavepoint(outer));
    assertEquals(StatusCode.OK, session.insert( 0,20, row(200)));
    assertEquals(StatusCode.OK, session.createSavepoint(inner));
    assertEquals(StatusCode.OK, session.insert( 0,30, row(300)));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(outer));
    assertEquals(false, inner.isActive());
    assertEquals(StatusCode.NOT_OWNER, session.releaseSavepoint(inner));
    assertEquals(StatusCode.OK, session.releaseSavepoint(outer));
    assertEquals(StatusCode.OK, session.commit(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,10, fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,20, fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,30, fetched));
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
    assertEquals(StatusCode.OK, writer.insert( 0,31, row(311)));
    assertEquals(StatusCode.OK, writer.insert( 0,32, row(322)));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, writer.fetchByKey( 0,31, fetched));
    assertEquals(311, value(fetched));
    assertEquals(StatusCode.OK, writer.fetchByKey( 0,32, fetched));
    assertEquals(322, value(fetched));
    assertEquals(StatusCode.CONFLICT, reader.fetchByKey( 0,31, fetched));
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.commit(outcome));
    long committedAt = outcome.commitSequence();
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(committedAt - 1, 0, 31, fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(committedAt - 1, 0, 32, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt, 0, 31, fetched));
    assertEquals(311, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt, 0, 32, fetched));
    assertEquals(322, value(fetched));
    assertEquals(StatusCode.OK, reader.abort(outcome));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void commitsOneBatchAcrossHeapPagesAndRecoversIt(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession writer = new IndexedTransactionSession(manager, table, 256);
    ByteBuffer row = ByteBuffer.allocateDirect(256);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    for (int key = 0; key < 64; key++) {
      row.clear();
      row.putLong(0, key * 10L);
      row.position(0);
      row.limit(row.capacity());
      assertEquals(StatusCode.OK, writer.insert( 0,key, row));
    }
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(64, table.rowCount());
    assertEquals(true, table.pageCount() >= 4);
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,63, fetched));
    assertEquals(630, value(fetched));

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    assertEquals(64, table.rowCount());
    assertEquals(StatusCode.OK, table.fetchByKey( 0,0, fetched));
    assertEquals(0, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,63, fetched));
    assertEquals(630, value(fetched));
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
    assertEquals(StatusCode.OK, seed.insert( 0,40, row(400)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert( 0,41, row(410)));
    assertEquals(StatusCode.CONFLICT, writer.insert( 0,40, row(401)));
    assertEquals(StatusCode.OK, writer.abort(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,41, fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,40, fetched));
    assertEquals(400, value(fetched));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void multiWriteCommitCanPublishLeafSplit(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession seed = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    for (int key = 0; key < 255; key++) {
      assertEquals(StatusCode.OK, seed.insert(0, key, row(key)));
    }
    assertEquals(StatusCode.OK, seed.commit(outcome));
    int oldRoot = table.rootPageId();
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert( 0,1000, row(10000)));
    assertEquals(StatusCode.OK, writer.insert( 0,1001, row(10010)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,1000, fetched));
    assertEquals(10000, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,1001, fetched));
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
    assertEquals(StatusCode.OK, seed.insert( 0,60, row(600)));
    assertEquals(StatusCode.OK, seed.insert( 0,61, row(610)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedTransactionSession oldReader = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, oldReader.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update( 0,60, row(601)));
    assertEquals(StatusCode.OK, writer.delete( 0,61));
    assertEquals(StatusCode.OK, writer.insert( 0,62, row(620)));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, writer.fetchByKey( 0,60, fetched));
    assertEquals(601, value(fetched));
    assertEquals(StatusCode.CONFLICT, writer.fetchByKey( 0,61, fetched));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    long mutatedAt = outcome.commitSequence();

    assertEquals(StatusCode.OK, oldReader.fetchByKey( 0,60, fetched));
    assertEquals(600, value(fetched));
    assertEquals(StatusCode.OK, oldReader.fetchByKey( 0,61, fetched));
    assertEquals(610, value(fetched));
    assertEquals(StatusCode.CONFLICT, oldReader.fetchByKey( 0,62, fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,60, fetched));
    assertEquals(601, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,61, fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,62, fetched));
    assertEquals(620, value(fetched));
    assertEquals(StatusCode.OK, oldReader.abort(outcome));

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    assertEquals(StatusCode.OK, table.fetchByKeyAt(mutatedAt - 1, 0, 60, fetched));
    assertEquals(600, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(mutatedAt - 1, 0, 61, fetched));
    assertEquals(610, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(mutatedAt, 0, 60, fetched));
    assertEquals(601, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(mutatedAt, 0, 61, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(mutatedAt, 0, 62, fetched));
    assertEquals(620, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void mixedMutationCommitSplitsFullLeafAndRecoversVersions(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    IndexedTransactionSession seed = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    for (int index = 0; index < BTreePage.MAX_ENTRIES; index++) {
      assertEquals(StatusCode.OK, seed.insert(0, index, row(index * 10L)));
    }
    assertEquals(StatusCode.OK, seed.commit(outcome));
    int leafRoot = table.rootPageId();
    IndexedTransactionSession oldReader = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, oldReader.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update( 0,0, row(9_000)));
    assertEquals(StatusCode.OK, writer.delete( 0,1));
    assertEquals(StatusCode.OK, writer.insert( 0,1_000, row(10_000)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    long committedAt = outcome.commitSequence();
    assertNotEquals(leafRoot, table.rootPageId());

    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, oldReader.fetchByKey( 0,0, fetched));
    assertEquals(0, value(fetched));
    assertEquals(StatusCode.OK, oldReader.fetchByKey( 0,1, fetched));
    assertEquals(10, value(fetched));
    assertEquals(StatusCode.CONFLICT, oldReader.fetchByKey( 0,1_000, fetched));
    assertEquals(StatusCode.OK, oldReader.abort(outcome));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,0, fetched));
    assertEquals(9_000, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,1, fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,1_000, fetched));
    assertEquals(10_000, value(fetched));

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt - 1, 0, 0, fetched));
    assertEquals(0, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt - 1, 0, 1, fetched));
    assertEquals(10, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(committedAt - 1, 0, 1_000, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt, 0, 0, fetched));
    assertEquals(9_000, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(committedAt, 0, 1, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt, 0, 1_000, fetched));
    assertEquals(10_000, value(fetched));
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
    assertEquals(StatusCode.OK, seed.insert( 0,75, row(750)));
    assertEquals(StatusCode.OK, seed.commit(outcome));
    IndexedTransactionSession stale = session(manager, table);
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, stale.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update( 0,75, row(751)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.CONFLICT, stale.update( 0,75, row(752)));
    assertEquals(StatusCode.OK, stale.abort(outcome));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,75, fetched));
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
    assertEquals(StatusCode.OK, seed.insert( 0,85, row(850)));
    assertEquals(StatusCode.OK, seed.commit(outcome));
    long insertedAt = outcome.commitSequence();

    IndexedTransactionSession beforeDelete = session(manager, table);
    assertEquals(StatusCode.OK, beforeDelete.begin(IsolationLevel.REPEATABLE_READ));
    IndexedTransactionSession deleter = session(manager, table);
    assertEquals(StatusCode.OK, deleter.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, deleter.delete( 0,85));
    assertEquals(StatusCode.OK, deleter.commit(outcome));
    long deletedAt = outcome.commitSequence();

    IndexedTransactionSession afterDelete = session(manager, table);
    assertEquals(StatusCode.OK, afterDelete.begin(IsolationLevel.REPEATABLE_READ));
    IndexedTransactionSession reinserter = session(manager, table);
    assertEquals(StatusCode.OK, reinserter.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, reinserter.insert( 0,85, row(851)));
    assertEquals(StatusCode.OK, reinserter.commit(outcome));
    long reinsertedAt = outcome.commitSequence();

    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, beforeDelete.fetchByKey( 0,85, fetched));
    assertEquals(850, value(fetched));
    assertEquals(StatusCode.CONFLICT, afterDelete.fetchByKey( 0,85, fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,85, fetched));
    assertEquals(851, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(insertedAt, 0, 85, fetched));
    assertEquals(850, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(deletedAt, 0, 85, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(reinsertedAt, 0, 85, fetched));
    assertEquals(851, value(fetched));
    assertEquals(StatusCode.OK, beforeDelete.abort(outcome));
    assertEquals(StatusCode.OK, afterDelete.abort(outcome));
    assertEquals(0, manager.activeLockCount());
    close(table, wal, directory);
  }

  @Test
  void deleteReinsertFormsRecoverFromWalBeforePageFlush(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 8);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(manager, table);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert(0, 85, row(850)));
    assertEquals(StatusCode.OK, seed.insert(0, 86, row(860)));
    assertEquals(StatusCode.OK, seed.commit(outcome));
    long insertedAt = outcome.commitSequence();

    IndexedTransactionSession sameTransaction = session(manager, table);
    assertEquals(StatusCode.OK, sameTransaction.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, sameTransaction.delete(0, 85));
    assertEquals(StatusCode.OK, sameTransaction.insert(0, 85, row(851)));
    assertEquals(StatusCode.OK, sameTransaction.commit(outcome));
    long replacedAt = outcome.commitSequence();

    IndexedTransactionSession deleter = session(manager, table);
    assertEquals(StatusCode.OK, deleter.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, deleter.delete(0, 86));
    assertEquals(StatusCode.OK, deleter.commit(outcome));
    long deletedAt = outcome.commitSequence();
    IndexedTransactionSession reinserter = session(manager, table);
    assertEquals(StatusCode.OK, reinserter.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, reinserter.insert(0, 86, row(861)));
    assertEquals(StatusCode.OK, reinserter.commit(outcome));
    long reinsertedAt = outcome.commitSequence();

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    table = openTable(storeResult.store());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKeyAt(insertedAt, 0, 85, fetched));
    assertEquals(850, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(replacedAt, 0, 85, fetched));
    assertEquals(851, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(insertedAt, 0, 86, fetched));
    assertEquals(860, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(deletedAt, 0, 86, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(reinsertedAt, 0, 86, fetched));
    assertEquals(861, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void groupedResurrectionRecoversFromWalBeforePageFlush(@TempDir Path root)
      throws Exception {
    NioIoCounters counters = new NioIoCounters();
    NioDurableDirectory directory = openDirectory(root, counters);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 8);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(manager, table);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert(0, 87, row(870)));
    assertEquals(StatusCode.OK, seed.commit(outcome));
    IndexedTransactionSession deleter = session(manager, table);
    assertEquals(StatusCode.OK, deleter.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, deleter.delete(0, 87));
    assertEquals(StatusCode.OK, deleter.commit(outcome));

    IndexedTable committingTable = table;
    IndexedGroupCommitCoordinator coordinator =
        new IndexedGroupCommitCoordinator(manager, table, 500_000_000);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    long forcesBefore = counters.forceCalls();
    try {
      Future<StatusCode> resurrection = executor.submit(
          () -> commitInsertValue(
              manager, committingTable, coordinator, 87, 8_700, ready, start));
      Future<StatusCode> companion = executor.submit(
          () -> commitDistinct(manager, committingTable, coordinator, 88, ready, start));
      ready.await();
      start.countDown();
      assertEquals(StatusCode.OK, resurrection.get());
      assertEquals(StatusCode.OK, companion.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(
        1, counters.forceCalls() - forcesBefore,
        "cohorts=" + coordinator.cohortCount()
            + " shared=" + coordinator.sharedForceTransactions()
            + " direct=" + coordinator.directFallbackTransactions()
            + " max=" + coordinator.maximumCohortSize());
    assertEquals(2, coordinator.sharedForceTransactions());
    assertEquals(StatusCode.OK, coordinator.close());

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    table = openTable(storeResult.store());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(0, 87, fetched));
    assertEquals(8_700, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey(0, 88, fetched));
    assertEquals(880, value(fetched));
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
    assertEquals(StatusCode.OK, seed.insert( 0,201, row(2010)));
    assertEquals(StatusCode.OK, seed.insert( 0,202, row(2020)));
    assertEquals(StatusCode.OK, seed.insert( 0,203, row(2030)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update( 0,201, row(2011)));
    assertEquals(StatusCode.OK, writer.delete( 0,202));
    assertEquals(StatusCode.OK, writer.delete( 0,203));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    IndexedTransactionSession reinserter = session(manager, table);
    assertEquals(StatusCode.OK, reinserter.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, reinserter.insert( 0,202, row(2021)));
    assertEquals(StatusCode.OK, reinserter.commit(outcome));
    assertEquals(7, table.rowCount());
    assertEquals(4, table.obsoleteVersionCount());

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
    assertEquals(0, table.obsoleteVersionCount());
    assertEquals(
        2L * io.riverdb.format.page.PageCodec.PAGE_BYTES,
        table.stagedCopyBytes() - stagedBefore);
    assertEquals(17, table.walCopyBytes() - walCopiedBefore);
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,201, fetched));
    assertEquals(2011, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,202, fetched));
    assertEquals(2021, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,203, fetched));
    assertEquals(StatusCode.CONFLICT, vacuum.run(outcome));
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertEquals(3, table.rowCount());

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    assertEquals(3, table.rowCount());
    assertEquals(0, table.obsoleteVersionCount());
    assertEquals(StatusCode.OK, table.fetchByKey( 0,201, fetched));
    assertEquals(2011, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,202, fetched));
    assertEquals(2021, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,203, fetched));
    close(table, wal, directory);
  }

  @Test
  void vacuumRetriesCleanlyAfterActiveSnapshotEnds(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession session = session(manager, table);

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(0, 41, row(410)));
    assertEquals(StatusCode.OK, session.insert(0, 43, row(430)));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.update(0, 41, row(411)));
    assertEquals(StatusCode.OK, session.delete(0, 43));
    assertEquals(StatusCode.OK, session.commit(outcome));

    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, table.vacuumPreflight());
    assertEquals(StatusCode.RETRY, vacuum.run(outcome));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, table.vacuumPreflight());
    assertEquals(StatusCode.OK, vacuum.run(outcome));
    assertEquals(2, vacuum.result().rowsReclaimed());

    close(table, wal, directory);
  }

  @Test
  void vacuumCompactsMoreThanOneWalPayloadAndRecovers(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    IndexedTransactionSession writer = new IndexedTransactionSession(manager, table, 4096);
    TransactionOutcome outcome = new TransactionOutcome();
    ByteBuffer largeRow = ByteBuffer.allocateDirect(4096);
    for (int batch = 0; batch < 6; batch++) {
      assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
      for (int index = 0; index < 50; index++) {
        long key = 1000L + batch * 50L + index;
        largeRow.putLong(0, key);
        largeRow.position(0);
        largeRow.limit(largeRow.capacity());
        assertEquals(StatusCode.OK, writer.insert( 0,key, largeRow));
      }
      assertEquals(StatusCode.OK, writer.commit(outcome));
    }
    for (int batch = 0; batch < 6; batch++) {
      assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
      for (int index = 0; index < 50; index++) {
        long key = 1000L + batch * 50L + index;
        largeRow.putLong(0, key + 10_000);
        largeRow.position(0);
        largeRow.limit(largeRow.capacity());
        assertEquals(StatusCode.OK, writer.update( 0,key, largeRow));
      }
      assertEquals(StatusCode.OK, writer.commit(outcome));
    }
    assertEquals(600, table.rowCount());
    assertEquals(300, table.obsoleteVersionCount());
    assertEquals(
        true,
        IndexedTableStore.VACUUM_COMMIT_PAYLOAD_BYTES
            + 300L * (4096 + 24) > WalRecordCodec.MAX_PAYLOAD_BYTES);

    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    assertEquals(StatusCode.OK, vacuum.run(outcome));
    assertEquals(600, vacuum.result().rowsBefore());
    assertEquals(300, vacuum.result().rowsAfter());
    assertEquals(300, table.rowCount());
    assertEquals(0, table.obsoleteVersionCount());

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    assertEquals(300, table.rowCount());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,1000, fetched));
    assertEquals(11_000, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,1299, fetched));
    assertEquals(11_299, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void recoveryDiscardsVacuumChunksWithoutCommitMarker(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    IndexedTransactionSession writer = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert( 0,501, row(5010)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update( 0,501, row(5011)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    assertEquals(StatusCode.OK, vacuum.run(outcome));
    long incompleteEnd = wal.durableEnd()
        - WalRecordCodec.encodedBytes(IndexedTableStore.VACUUM_COMMIT_PAYLOAD_BYTES);
    try (FileChannel channel = FileChannel.open(
        root.resolve(LocalWal.FILE_NAME), StandardOpenOption.WRITE)) {
      channel.truncate(incompleteEnd);
      channel.force(true);
    }

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    assertEquals(2, table.rowCount());
    assertEquals(1, table.obsoleteVersionCount());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,501, fetched));
    assertEquals(5011, value(fetched));
    manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    writer = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.insert(0, 502, row(5020)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    close(table, wal, directory);

    directory = openDirectory(root);
    wal = openWal(directory);
    storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    assertEquals(StatusCode.OK, table.fetchByKey(0, 501, fetched));
    assertEquals(5011, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey(0, 502, fetched));
    assertEquals(5020, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void automaticVacuumWaitsForCapacityPressureWhileExplicitVacuumRecovers(
      @TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(manager, table);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert( 0,301, row(3010)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedTransactionSession snapshot = session(manager, table);
    assertEquals(StatusCode.OK, snapshot.begin(IsolationLevel.REPEATABLE_READ));
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedTransactionSession writer = new IndexedTransactionSession(
        manager,
        table,
        128,
        null,
        vacuum);
    for (long value = 3011; value <= 3013; value++) {
      assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
      assertEquals(StatusCode.OK, writer.update( 0,301, row(value)));
      assertEquals(StatusCode.OK, writer.commit(outcome));
    }
    assertEquals(4, table.rowCount());
    assertEquals(3, table.obsoleteVersionCount());
    assertEquals(0, vacuum.automaticDeferrals());
    assertEquals(0, vacuum.automaticRuns());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, snapshot.fetchByKey( 0,301, fetched));
    assertEquals(3010, value(fetched));
    assertEquals(StatusCode.OK, snapshot.abort(outcome));

    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(4, table.rowCount());
    assertEquals(3, table.obsoleteVersionCount());
    assertEquals(0, vacuum.automaticRuns());
    assertEquals(StatusCode.OK, writer.abort(outcome));
    assertEquals(StatusCode.OK, vacuum.run(outcome));
    assertEquals(1, table.rowCount());
    assertEquals(0, table.obsoleteVersionCount());
    assertEquals(0, vacuum.automaticRuns());
    assertEquals(3, vacuum.result().rowsReclaimed());
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update( 0,301, row(3014)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(2, table.rowCount());
    assertEquals(1, table.obsoleteVersionCount());

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    assertEquals(2, table.rowCount());
    assertEquals(1, table.obsoleteVersionCount());
    assertEquals(StatusCode.OK, table.fetchByKey( 0,301, fetched));
    assertEquals(3014, value(fetched));
    close(table, wal, directory);
  }

  @Test
  void versionPressureCoalescesAdmissionRejectionUntilSnapshotDrains(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    TransactionOutcome outcome = new TransactionOutcome();
    IndexedTransactionSession seed = session(manager, table);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert( 0,401, row(4010)));
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedTransactionSession snapshot = session(manager, table);
    assertEquals(StatusCode.OK, snapshot.begin(IsolationLevel.REPEATABLE_READ));
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedTransactionSession writer = new IndexedTransactionSession(
        manager,
        table,
        128,
        null,
        vacuum,
        Integer.MAX_VALUE);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update( 0,401, row(4011)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.RETRY, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(1, vacuum.automaticDeferrals());
    assertEquals(1, vacuum.automaticPressureRejections());
    assertEquals(StatusCode.RETRY, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(1, vacuum.automaticDeferrals());
    assertEquals(1, vacuum.automaticPressureRejections());
    assertEquals(2, table.rowCount());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, snapshot.fetchByKey( 0,401, fetched));
    assertEquals(4010, value(fetched));

    assertEquals(StatusCode.OK, snapshot.abort(outcome));
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(2, table.rowCount());
    assertEquals(0, vacuum.automaticRuns());
    assertEquals(StatusCode.OK, writer.update( 0,401, row(4012)));
    assertEquals(StatusCode.OK, writer.commit(outcome));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,401, fetched));
    assertEquals(4012, value(fetched));
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
      assertEquals(StatusCode.OK, table.fetchByKey( 0,key, fetched));
      assertEquals(key * 10L, value(fetched));
    }
    close(table, wal, directory);
  }

  @Test
  void forcesConcurrentInsertCommitsOnceAndRecoversEveryRecord(@TempDir Path root)
      throws Exception {
    NioIoCounters counters = new NioIoCounters();
    NioDurableDirectory directory = openDirectory(root, counters);
    LocalWal wal = openWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    IndexedTable table = createTable(store);
    IndexedTable committingTable = table;
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 8);
    IndexedGroupCommitCoordinator coordinator =
        new IndexedGroupCommitCoordinator(manager, table, 500_000_000);
    CountDownLatch ready = new CountDownLatch(4);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(4);
    long forcesBefore = counters.forceCalls();
    long walCopiesBefore = store.walCopyBytes();
    long compiledCopiesBefore = store.relationalCompilationCopyBytes();
    try {
      Future<StatusCode> first = executor.submit(
          () -> commitDistinct(manager, committingTable, coordinator, 301, ready, start));
      Future<StatusCode> second = executor.submit(
          () -> commitDistinct(manager, committingTable, coordinator, 302, ready, start));
      Future<StatusCode> third = executor.submit(
          () -> commitDistinct(manager, committingTable, coordinator, 303, ready, start));
      Future<StatusCode> fourth = executor.submit(
          () -> commitDistinct(manager, committingTable, coordinator, 304, ready, start));
      ready.await();
      start.countDown();
      assertEquals(StatusCode.OK, first.get());
      assertEquals(StatusCode.OK, second.get());
      assertEquals(StatusCode.OK, third.get());
      assertEquals(StatusCode.OK, fourth.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(
        1, counters.forceCalls() - forcesBefore,
        "cohorts=" + coordinator.cohortCount()
            + " shared=" + coordinator.sharedForceTransactions()
            + " direct=" + coordinator.directFallbackTransactions()
            + " max=" + coordinator.maximumCohortSize());
    assertEquals(4L * Long.BYTES, store.walCopyBytes() - walCopiesBefore);
    assertEquals(
        4L * Long.BYTES,
        store.relationalCompilationCopyBytes() - compiledCopiesBefore);
    assertEquals(0, wal.copiedPayloadBytes());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(5, table.currentCommitSequence());
    assertEquals(4, coordinator.sharedForceTransactions());
    assertEquals(0, coordinator.directFallbackTransactions());
    assertEquals(StatusCode.OK, coordinator.close());

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    HeapRowResult fetched = new HeapRowResult();
    for (int key = 301; key <= 304; key++) {
      assertEquals(StatusCode.OK, table.fetchByKey( 0,key, fetched));
      assertEquals(key * 10L, value(fetched));
    }
    close(table, wal, directory);
  }

  @Test
  void preparedGroupRemainsInvisibleUntilAtomicFrontierInstall(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedTransactionSession first = session(manager, table);
    IndexedTransactionSession second = session(manager, table);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert(0, 351, row(3_510)));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.insert(0, 352, row(3_520)));

    IndexedTransactionSession[] sessions = {first, second};
    io.riverdb.tx.Transaction[] transactions = {
        first.groupTransaction(), second.groupTransaction()
    };
    TransactionOutcome[] outcomes = {new TransactionOutcome(), new TransactionOutcome()};
    long[] sequences = new long[2];
    long previousFrontier = table.currentCommitSequence();
    assertEquals(
        StatusCode.OK,
        table.preflightHybridCommitGroup(
            sessions, sessions.length, manager.oldestVisibleCommitSequence()));
    assertEquals(StatusCode.OK, manager.beginCommitGroup(transactions, transactions.length));
    assertEquals(
        StatusCode.OK, table.appendHybridCommitGroup(sessions, sequences, sessions.length));
    assertEquals(StatusCode.OK, table.forceHybridCommitGroup());
    assertEquals(StatusCode.OK, table.prepareForcedGroupPublication());
    assertEquals(previousFrontier, table.currentCommitSequence());

    IndexedTransactionSession oldSnapshot = session(manager, table);
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, oldSnapshot.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.CONFLICT, oldSnapshot.fetchByKey(0, 351, new HeapRowResult()));
    assertEquals(
        StatusCode.OK,
        manager.publishCommitGroup(
            transactions, outcomes, sequences, transactions.length, table));
    assertEquals(StatusCode.OK, first.completeCoordinatedCommit(StatusCode.OK));
    assertEquals(StatusCode.OK, second.completeCoordinatedCommit(StatusCode.OK));
    assertEquals(sequences[1], table.currentCommitSequence());
    assertEquals(StatusCode.OK, table.fetchByKeyAt(sequences[0], 0, 351, fetched));
    assertEquals(3_510, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(sequences[0], 0, 352, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(sequences[1], 0, 352, fetched));
    assertEquals(3_520, value(fetched));
    assertEquals(StatusCode.CONFLICT, oldSnapshot.fetchByKey(0, 351, new HeapRowResult()));
    assertEquals(StatusCode.OK, oldSnapshot.abort(new TransactionOutcome()));

    IndexedTransactionSession newSnapshot = session(manager, table);
    assertEquals(StatusCode.OK, newSnapshot.begin(IsolationLevel.REPEATABLE_READ));
    fetched.reset();
    assertEquals(StatusCode.OK, newSnapshot.fetchByKey(0, 351, fetched));
    assertEquals(3_510, value(fetched));
    assertEquals(StatusCode.OK, newSnapshot.fetchByKey(0, 352, fetched));
    assertEquals(3_520, value(fetched));
    assertEquals(StatusCode.OK, newSnapshot.abort(new TransactionOutcome()));
    close(table, wal, directory);
  }

  @Test
  void forcesConcurrentUpdatesAndDeletesOnceAndRecoversEveryDecision(@TempDir Path root)
      throws Exception {
    NioIoCounters counters = new NioIoCounters();
    NioDurableDirectory directory = openDirectory(root, counters);
    LocalWal wal = openWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    IndexedTable table = createTable(store);
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 8);
    IndexedTransactionSession seed = session(manager, table);
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    for (int key = 401; key <= 404; key++) {
      assertEquals(StatusCode.OK, seed.insert( 0,key, row(key * 10L)));
    }
    assertEquals(StatusCode.OK, seed.commit(outcome));

    IndexedTable committingTable = table;
    IndexedGroupCommitCoordinator coordinator =
        new IndexedGroupCommitCoordinator(manager, table, 500_000_000);
    CountDownLatch ready = new CountDownLatch(4);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(4);
    long forcesBefore = counters.forceCalls();
    long walCopiesBefore = store.walCopyBytes();
    long compiledCopiesBefore = store.relationalCompilationCopyBytes();
    try {
      Future<StatusCode> first = executor.submit(
          () -> commitMutation(
              manager, committingTable, coordinator, 401, false, ready, start));
      Future<StatusCode> second = executor.submit(
          () -> commitMutation(
              manager, committingTable, coordinator, 402, false, ready, start));
      Future<StatusCode> third = executor.submit(
          () -> commitMutation(
              manager, committingTable, coordinator, 403, true, ready, start));
      Future<StatusCode> fourth = executor.submit(
          () -> commitMutation(
              manager, committingTable, coordinator, 404, true, ready, start));
      ready.await();
      start.countDown();
      assertEquals(StatusCode.OK, first.get());
      assertEquals(StatusCode.OK, second.get());
      assertEquals(StatusCode.OK, third.get());
      assertEquals(StatusCode.OK, fourth.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, counters.forceCalls() - forcesBefore);
    assertEquals(2L * Long.BYTES, store.walCopyBytes() - walCopiesBefore);
    assertEquals(
        2L * Long.BYTES,
        store.relationalCompilationCopyBytes() - compiledCopiesBefore);
    assertEquals(0, wal.copiedPayloadBytes());
    assertEquals(6, table.currentCommitSequence());
    assertMutationResults(table);
    assertEquals(4, coordinator.sharedForceTransactions());
    assertEquals(0, coordinator.directFallbackTransactions());
    assertEquals(StatusCode.OK, coordinator.close());

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    assertMutationResults(table);
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
    assertEquals(StatusCode.OK, writer.insert( 0,501, row(5010)));
    assertEquals(StatusCode.OK, writer.insert( 0,502, row(5020)));
    long committedTransactionId = writer.transaction().transactionId();
    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, writer.commit(outcome));
    long committedAt = outcome.commitSequence();
    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    table = tableResult.table();
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(committedAt - 1, 0, 501, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt, 0, 501, fetched));
    assertEquals(5010, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKeyAt(committedAt - 1, 0, 502, fetched));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(committedAt, 0, 502, fetched));
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
        assertEquals(StatusCode.OK, seed.insert( 0,key, row(key * 10L)));
      }
      assertEquals(StatusCode.OK, seed.commit(outcome));
    }
    IndexedTransactionSession snapshot = session(manager, table);
    assertEquals(StatusCode.OK, snapshot.begin(IsolationLevel.REPEATABLE_READ));
    IndexedTransactionSession writer = session(manager, table);
    assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.update( 0,10, row(101)));
    assertEquals(StatusCode.OK, writer.delete( 0,20));
    assertEquals(StatusCode.OK, writer.insert( 0,1000, row(10_000)));
    assertEquals(StatusCode.OK, writer.commit(outcome));

    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult scanned = new IndexedScanResult();
    assertEquals(StatusCode.OK, snapshot.beginScan( 0,0, 0, Long.MAX_VALUE, cursor));
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
    assertEquals(StatusCode.OK, current.beginScan( 0,0, 0, Long.MAX_VALUE, cursor));
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
      status = session.insert( 0,key, row(key * 10L));
    }
    ready.countDown();
    start.await();
    return status.isOk() ? session.commit(new TransactionOutcome()) : status;
  }

  private static StatusCode commitMutation(
      TransactionManager manager,
      IndexedTable table,
      IndexedGroupCommitCoordinator coordinator,
      int key,
      boolean delete,
      CountDownLatch ready,
      CountDownLatch start) throws InterruptedException {
    IndexedTransactionSession session =
        new IndexedTransactionSession(manager, table, 128, coordinator);
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) {
      status = delete ? session.delete( 0,key) : session.update( 0,key, row(key * 100L));
    }
    ready.countDown();
    start.await();
    return status.isOk() ? session.commit(new TransactionOutcome()) : status;
  }

  private static void assertMutationResults(IndexedTable table) {
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,401, fetched));
    assertEquals(40_100, value(fetched));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,402, fetched));
    assertEquals(40_200, value(fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,403, fetched));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,404, fetched));
  }

  private static StatusCode commitDistinct(
      TransactionManager manager,
      IndexedTable table,
      IndexedGroupCommitCoordinator coordinator,
      int key,
      CountDownLatch ready,
      CountDownLatch start) throws InterruptedException {
    return commitInsertValue(
        manager, table, coordinator, key, key * 10L, ready, start);
  }

  private static StatusCode commitInsertValue(
      TransactionManager manager,
      IndexedTable table,
      IndexedGroupCommitCoordinator coordinator,
      int key,
      long value,
      CountDownLatch ready,
      CountDownLatch start) throws InterruptedException {
    IndexedTransactionSession session =
        new IndexedTransactionSession(manager, table, 128, coordinator);
    StatusCode status = session.begin(IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) {
      status = session.insert(0, key, row(value));
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

  private static ByteBuffer genericFixedTuple(long value) {
    ByteBuffer tuple = ByteBuffer.allocate(24);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    assertEquals(StatusCode.OK, builder.beginTuple(tuple, 0, 1));
    assertEquals(StatusCode.OK, builder.addFixed(SqlTypeDescriptor.BIGINT, value));
    assertEquals(StatusCode.OK, builder.finishTuple());
    tuple.position(0);
    tuple.limit(builder.keyBytes());
    return tuple;
  }

  private static IndexedRelationalMutation relationalBaseMutation(long value) {
    IndexedRelationalMutation mutation = new IndexedRelationalMutation(1, 0, 0);
    assertEquals(StatusCode.OK, mutation.reserve(1, 0, 0, Long.BYTES));
    assertEquals(StatusCode.OK, mutation.appendLogicalRowFloor(19, 2));
    assertEquals(StatusCode.OK, mutation.appendSuboperation(
        19, -1, 0, 1, 0, 0, 3, 3, 4, 4,
        0, 0, 0, 1,
        IndexedRelationalSuboperations.REGISTRY_ABSENT,
        IndexedRelationalSuboperations.REGISTRY_ABSENT, 0, 0));
    ByteBuffer row = row(value);
    assertEquals(StatusCode.OK, mutation.appendBase(
        0, 19, IndexedRelationalMutation.BASE_INSERT, 1, 0,
        row, 0, Long.BYTES));
    assertEquals(StatusCode.OK, mutation.seal());
    return mutation;
  }

  private static IndexedRelationalMutation relationalScalarUpdate(
      IndexedTable table, long space, long key, long value) {
    long expectedHeapVersion = table.rowCount();
    IndexedRelationalMutation mutation = new IndexedRelationalMutation(1, 0, 0);
    assertEquals(StatusCode.OK, mutation.reserve(1, 0, 0, Long.BYTES));
    assertEquals(StatusCode.OK, mutation.appendSuboperation(
        0, IndexedRelationalMutation.SCALAR_SUBOPERATION, 0, 1,
        0, 0, 3, 3, 4, 4, 0, 0,
        expectedHeapVersion, expectedHeapVersion + 1,
        IndexedRelationalMutation.REGISTRY_ABSENT,
        IndexedRelationalMutation.REGISTRY_ABSENT, 0, 0));
    ByteBuffer row = row(value);
    assertEquals(StatusCode.OK, mutation.appendScalar(
        0, IndexedRelationalMutation.SCALAR_UPDATE, space, key, expectedHeapVersion,
        row, 0, row.remaining()));
    assertEquals(StatusCode.OK, mutation.seal());
    return mutation;
  }

  private static long value(HeapRowResult result) {
    ByteBuffer row = ByteBuffer.allocate(result.length());
    assertEquals(StatusCode.OK, result.copyTo(row));
    return row.getLong(0);
  }

  private static IndexedTransactionSession session(
      TransactionManager manager,
      IndexedTable table) {
    return new IndexedTransactionSession(manager, table, 128);
  }

  private static void awaitOneLockWait(TransactionManager manager) {
    long deadline = System.nanoTime() + 1_000_000_000L;
    while (manager.waitingLockCount() == 0 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(1, manager.waitingLockCount());
  }

  private static NioDurableDirectory openDirectory(Path root) {
    return openDirectory(root, new NioIoCounters());
  }

  private static NioDurableDirectory openDirectory(
      Path root,
      NioIoCounters counters) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            counters,
            8,
            result));
    return result.directory();
  }

  private static LocalWal openWal(NioDurableDirectory directory) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  private static IndexedTableStore createStore(
      NioDurableDirectory directory,
      LocalWal wal) {
    IndexedTableStoreOpenResult result = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.create(directory, wal, DATABASE, GENERATION, result));
    return result.store();
  }

  private static IndexedTableStore createStore(
      NioDurableDirectory directory,
      LocalWal wal,
      IndexedPageCacheConfig pageCacheConfig) {
    IndexedTableStoreOpenResult result = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStoreFactory.create(
            directory, wal, DATABASE, GENERATION, pageCacheConfig, result));
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
      IndexedTable table,
      LocalWal wal,
      NioDurableDirectory directory) {
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }
}
