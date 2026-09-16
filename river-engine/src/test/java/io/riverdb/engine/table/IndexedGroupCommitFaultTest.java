package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static io.riverdb.engine.TestDatabaseResources.pageCachePlan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.engine.runtime.DatabasePageCacheTestPlan;
import io.riverdb.engine.testsupport.fault.CrashPointController;
import io.riverdb.engine.testsupport.fault.DirectoryFaultPoints;
import io.riverdb.engine.testsupport.fault.DirectoryOperation;
import io.riverdb.engine.testsupport.fault.FaultAction;
import io.riverdb.engine.testsupport.fault.FaultBoundary;
import io.riverdb.engine.testsupport.fault.FaultOperation;
import io.riverdb.engine.testsupport.fault.FaultPointRegistry;
import io.riverdb.engine.testsupport.fault.FaultPointSlot;
import io.riverdb.engine.testsupport.fault.FaultingDurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class IndexedGroupCommitFaultTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(919, 929);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  private ForcedGroupFixture openForcedFixture;
  private FaultingDurableDirectory openDirectory;
  private LocalWal openWal;
  private IndexedTable openTable;
  private IndexedGroupCommitCoordinator openCoordinator;

  @AfterEach
  void closeForcedFixture() {
    if (openForcedFixture != null) openForcedFixture.close();
    if (openCoordinator != null) openCoordinator.close();
    StatusCode flush = openTable == null ? StatusCode.CLOSED : openTable.flush();
    if (flush.isOk()) openTable.close();
    StatusCode walClose = openWal == null ? StatusCode.CLOSED : openWal.close();
    if (openDirectory != null
        && (!flush.isOk() && flush != StatusCode.CLOSED
            || !walClose.isOk() && walClose != StatusCode.CLOSED)) {
      openDirectory.crash();
    }
  }

  private ForcedGroupFixture forcedFixture() {
    openForcedFixture = new ForcedGroupFixture();
    return openForcedFixture;
  }

  private ForcedGroupFixture forcedFixture(boolean delete) {
    openForcedFixture = new ForcedGroupFixture(delete);
    return openForcedFixture;
  }

  private ForcedGroupFixture forcedFixture(DatabasePageCachePlan cachePlan) {
    openForcedFixture = new ForcedGroupFixture(cachePlan);
    return openForcedFixture;
  }

  @Test
  void publishedGroupHandsOffLocksButRetainsAdmissionAndWithholdsReaderCompletion()
      throws Exception {
    ForcedGroupFixture fixture = forcedFixture();
    long durableEnd = fixture.wal.durableEnd();
    fixture.publish();
    assertEquals(durableEnd, fixture.wal.durableEnd());
    assertEquals(0, fixture.manager.activeLockCount());
    assertEquals(2, fixture.manager.activeTransactionCount());
    assertEquals(0, fixture.manager.retainedSnapshotCount());
    assertEquals(TransactionState.COMMITTING, fixture.first.transaction().state());
    assertFalse(fixture.firstOutcome.isAvailable());

    IndexedTransactionSession successor = fixture.newSession();
    assertEquals(StatusCode.OK, successor.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(0, successor.transaction().snapshot().activeTransactionCount());
    assertEquals(StatusCode.OK, successor.beginStatement());
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK, successor.fetchByKey(0, 41, row));
    assertEquals(410, value(row));
    assertEquals(StatusCode.OK, successor.update(0, 41, row(411)));
    assertEquals(StatusCode.OK, successor.completeStatement(false));

    IndexedTransactionSession reader = fixture.newSession();
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    IndexedTransactionSession excess = fixture.newSession();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, excess.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(4, fixture.manager.activeTransactionCount());
    assertEquals(2, fixture.manager.retainedSnapshotCount());
    assertEquals(StatusCode.OK, reader.beginStatement());
    assertEquals(StatusCode.OK, reader.fetchByKey(0, 41, new HeapRowResult()));
    assertEquals(StatusCode.OK, reader.completeStatement(false));
    TransactionOutcome readOutcome = new TransactionOutcome();
    CountDownLatch started = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> readCommit = executor.submit(() -> {
        started.countDown();
        return reader.commit(readOutcome);
      });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> readCommit.get(100, TimeUnit.MILLISECONDS));
      assertFalse(readOutcome.isAvailable());
      fixture.complete(StatusCode.OK);
      assertEquals(StatusCode.OK, readCommit.get(5, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
    assertEquals(TransactionState.COMMITTED, readOutcome.state());
    assertEquals(StatusCode.OK, successor.commit(new TransactionOutcome()));
    assertEquals(StatusCode.OK, successor.close());
    assertEquals(StatusCode.OK, reader.close());
    assertEquals(StatusCode.OK, excess.close());
    assertEquals(0, fixture.manager.activeTransactionCount());
    assertEquals(0, fixture.manager.retainedSnapshotCount());
    assertEquals(0, fixture.manager.activeLockCount());
  }

  @ParameterizedTest
  @ValueSource(longs = {41, 999})
  void failedForceWakesDependentReaderWithoutAcknowledgingRowsOrAbsence(long key)
      throws Exception {
    ForcedGroupFixture fixture = forcedFixture(true);
    fixture.publish();
    IndexedTransactionSession reader = fixture.newSession();
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    CountDownLatch started = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> read = executor.submit(() -> {
        started.countDown();
        return reader.fetchByKey(0, key, new HeapRowResult());
      });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> read.get(100, TimeUnit.MILLISECONDS));
      assertEquals(StatusCode.OK, fixture.faults.arm(
          DirectoryOperation.FILE_FORCE, FaultOperation.DIRECTORY_FILE_FORCE,
          FaultAction.FORCE_FAILURE));
      fixture.complete(StatusCode.IO_FAILURE);
      assertEquals(StatusCode.FENCED, read.get(5, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, reader.abort(new TransactionOutcome()));
    fixture.assertTerminalFailure();
    assertEquals(StatusCode.OK, reader.close());
  }

  @Test
  void oldSnapshotProceedsButCurrentRowObservationWaitsForItsNewerDependency() throws Exception {
    ForcedGroupFixture fixture = forcedFixture();
    IndexedTransactionSession reader = fixture.newSession();
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    fixture.publish();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> oldRead = executor.submit(
          () -> reader.fetchByKey(0, 41, new HeapRowResult()));
      assertEquals(StatusCode.CONFLICT, oldRead.get(5, TimeUnit.SECONDS));
      HeapRowResult current = new HeapRowResult();
      CountDownLatch started = new CountDownLatch(1);
      Future<StatusCode> currentRead = executor.submit(() -> {
        started.countDown();
        return reader.lockCurrentKeyCurrent(0, 41, current);
      });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> currentRead.get(100, TimeUnit.MILLISECONDS));
      fixture.complete(StatusCode.OK);
      assertEquals(StatusCode.OK, currentRead.get(5, TimeUnit.SECONDS));
      assertEquals(410, value(current));
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, reader.releaseCurrentKey());
    assertEquals(StatusCode.OK, reader.abort(new TransactionOutcome()));
    assertEquals(StatusCode.OK, reader.close());
  }

  @Test
  void unrelatedRowsAbsenceAndReadOnlyCommitCompleteWhileForceIsPending() throws Exception {
    ForcedGroupFixture fixture = forcedFixture(true);
    fixture.publish();
    IndexedTransactionSession reader = fixture.newSession();
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    // First observe pending data, then abort and reuse the session: dependencies must reset.
    assertEquals(StatusCode.OK, reader.beginStatement());
    assertEquals(StatusCode.OK, reader.fetchByKey(0, 41, new HeapRowResult()));
    assertEquals(StatusCode.OK, reader.completeStatement(false));
    assertEquals(StatusCode.OK, reader.abort(new TransactionOutcome()));
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertEquals(StatusCode.OK, executor.submit(() -> {
        HeapRowResult row = new HeapRowResult();
        assertEquals(StatusCode.OK, reader.fetchByKey(0, 43, row));
        assertEquals(430, value(row));
        assertEquals(StatusCode.CONFLICT, reader.fetchByKey(0, 888, row));
        IndexedScanCursor cursor = new IndexedScanCursor();
        assertEquals(StatusCode.OK, reader.beginScan(0, 800, 0, 900, cursor));
        assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, new IndexedScanResult()));
        assertEquals(StatusCode.OK, reader.closeScan(cursor));
        assertEquals(StatusCode.OK, reader.commit(new TransactionOutcome()));
        return reader.awaitDurability();
      }).get(5, TimeUnit.SECONDS));
    } finally {
      fixture.complete(StatusCode.OK);
    }
    assertEquals(StatusCode.OK, reader.close());
    assertEquals(StatusCode.OK, fixture.first.close());
    assertEquals(StatusCode.OK, fixture.second.close());
  }

  @ParameterizedTest
  @ValueSource(strings = {"scan", "current", "insert", "duplicate", "update", "savepoint"})
  void negativeAndRolledBackObservationsRemainDependentOnFailedForce(String observation)
      throws Exception {
    ForcedGroupFixture fixture = forcedFixture(true);
    fixture.publish();
    IndexedTransactionSession reader = fixture.newSession();
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    IndexedSavepoint savepoint = new IndexedSavepoint();
    assertEquals(StatusCode.OK, reader.createSavepoint(savepoint));
    assertEquals(StatusCode.OK, reader.beginStatement());
    switch (observation) {
      case "scan" -> {
        IndexedScanCursor cursor = new IndexedScanCursor();
        assertEquals(StatusCode.OK, reader.beginScan(0, 999, 0, 1000, cursor));
        assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, new IndexedScanResult()));
        assertEquals(StatusCode.OK, reader.closeScan(cursor));
      }
      case "current" -> assertEquals(StatusCode.CONFLICT,
          reader.lockCurrentKeyCurrent(0, 999, new HeapRowResult()));
      case "insert" -> assertEquals(StatusCode.OK, reader.insert(0, 999, row(1)));
      case "duplicate" -> assertEquals(StatusCode.CONFLICT, reader.insert(0, 41, row(1)));
      case "update" -> assertEquals(StatusCode.CONFLICT, reader.update(0, 999, row(1)));
      case "savepoint" -> assertEquals(StatusCode.OK,
          reader.fetchByKey(0, 41, new HeapRowResult()));
      default -> throw new AssertionError(observation);
    }
    assertEquals(StatusCode.OK, reader.completeStatement(false));
    assertEquals(StatusCode.OK, reader.rollbackToSavepoint(savepoint));
    assertEquals(StatusCode.OK, reader.releaseSavepoint(savepoint));
    TransactionOutcome outcome = new TransactionOutcome();
    CountDownLatch started = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var delivered = executor.submit(() -> {
        started.countDown();
        return reader.commit(outcome);
      });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> delivered.get(100, TimeUnit.MILLISECONDS));
      assertEquals(StatusCode.OK, fixture.faults.arm(
          DirectoryOperation.FILE_FORCE, FaultOperation.DIRECTORY_FILE_FORCE,
          FaultAction.FORCE_FAILURE));
      fixture.complete(StatusCode.IO_FAILURE);
      assertEquals(StatusCode.FENCED, delivered.get(5, TimeUnit.SECONDS));
    }
    assertEquals(TransactionState.ABORTED, reader.transaction().state());
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertFalse(reader.transactionLifecycleActive());
    fixture.assertTerminalFailure();
    assertEquals(StatusCode.OK, reader.close());
  }

  @Test
  void cancelledReadOnlyCommitRetainsItsDependencyAndCanRetryAfterForce() throws Exception {
    ForcedGroupFixture fixture = forcedFixture();
    fixture.publish();
    IndexedTransactionSession reader = fixture.newSession();
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, reader.beginStatement());
    assertEquals(StatusCode.OK, reader.fetchByKey(0, 41, new HeapRowResult()));
    assertEquals(StatusCode.OK, reader.completeStatement(false));
    TransactionOutcome outcome = new TransactionOutcome();
    Thread.currentThread().interrupt();
    try {
      assertEquals(StatusCode.CANCELLED, reader.commit(outcome));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(TransactionState.ACTIVE, reader.transaction().state());
      assertTrue(reader.transactionLifecycleActive());
      assertFalse(outcome.isAvailable());
    } finally {
      Thread.interrupted();
      fixture.complete(StatusCode.OK);
    }
    assertEquals(StatusCode.OK, reader.commit(outcome));
    assertEquals(TransactionState.COMMITTED, outcome.state());
    assertEquals(StatusCode.OK, reader.close());
    assertEquals(StatusCode.OK, fixture.first.close());
    assertEquals(StatusCode.OK, fixture.second.close());
    assertEquals(0, fixture.manager.activeTransactionCount());
    assertEquals(0, fixture.manager.activeLockCount());
  }

  @Test
  void rowResultsRetainTheirOwnBytesAcrossOtherReadsAndValidateSpaces() throws Exception {
    ForcedGroupFixture fixture = forcedFixture();
    fixture.publish();
    fixture.complete(StatusCode.OK);
    IndexedTransactionSession reader = fixture.newSession();
    assertEquals(StatusCode.OK, reader.begin(IsolationLevel.REPEATABLE_READ));
    HeapRowResult first = new HeapRowResult();
    HeapRowResult second = new HeapRowResult();
    assertEquals(StatusCode.OK, reader.fetchByKey(0, 41, first));
    assertEquals(StatusCode.OK, reader.fetchByKey(0, 42, second));
    assertEquals(410, value(first));
    assertEquals(420, value(second));
    assertEquals(StatusCode.OK, reader.fetchCandidateByKey(
        0, 42, io.riverdb.tx.api.lock.LockMode.SHARED, new IndexedRowCandidate()));
    assertEquals(410, value(first));
    assertEquals(420, value(second));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        reader.fetchByKey(-1, 41, new HeapRowResult()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        reader.fetchByKey(Long.MIN_VALUE, 41, new HeapRowResult()));
    assertEquals(StatusCode.OK, reader.commit(new TransactionOutcome()));
    assertEquals(StatusCode.OK, reader.close());
    assertEquals(StatusCode.OK, fixture.first.close());
    assertEquals(StatusCode.OK, fixture.second.close());
  }

  @Test
  void crashAfterVisiblePublicationBeforeForceDiscardsTheUnacknowledgedGroup()
      throws Exception {
    ForcedGroupFixture fixture = forcedFixture();
    long durableTail = fixture.wal.durableEnd();
    fixture.publish();
    assertEquals(fixture.secondSequence, fixture.table.currentCommitSequence());
    assertFalse(fixture.firstOutcome.isAvailable());
    // Crash the file model directly: orderly close would hide an early-write violation.
    assertEquals(StatusCode.OK, fixture.faults.directory.crash());
    fixture.failHeldForce();
    fixture.complete(StatusCode.IO_FAILURE);
    fixture.assertTerminalFailure();
    assertEquals(StatusCode.OK, fixture.faults.directory.restart());
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.openExisting(
        fixture.faults.directory, DATABASE, GENERATION, walResult));
    assertEquals(durableTail, walResult.wal().tailEnd());
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK, IndexedTableStore.openExisting(
        fixture.faults.directory, walResult.wal(), DATABASE, GENERATION,
        databaseProviderLease(4), storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    assertEquals(fixture.firstSequence - 1, tableResult.table().currentCommitSequence());
    assertEquals(fixture.rowsBefore, tableResult.table().rowCount());
    assertEquals(StatusCode.CONFLICT, tableResult.table().fetchByKey(0, 41, new HeapRowResult()));
    assertEquals(StatusCode.CONFLICT, tableResult.table().fetchByKey(0, 42, new HeapRowResult()));
    assertEquals(StatusCode.OK, tableResult.table().close());
    assertEquals(StatusCode.OK, walResult.wal().close());
  }

  @Test
  void retainedPrefixPressureParksDeferredCommitUntilForceCompletion()
      throws Exception {
    DatabasePageCachePlan constrained = DatabasePageCacheTestPlan.geometry(5, 8, 16);
    FaultFixture faults = new FaultFixture();
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(faults.directory, DATABASE, GENERATION, walResult));
    LocalWal wal = walResult.wal();
    openDirectory = faults.directory;
    openWal = wal;
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK, IndexedTableStore.create(
        faults.directory, wal, DATABASE, GENERATION,
        DatabasePageCacheTestPlan.providerLease(constrained, 4), storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    IndexedTable table = tableResult.table();
    openTable = table;
    ForcedGroupFixture.HeldForce heldForce =
        new ForcedGroupFixture.HeldForce(ForcedGroupFixture.walFile(wal));
    ForcedGroupFixture.replaceWalFile(wal, heldForce);
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedGroupCommitCoordinator coordinator =
        new IndexedGroupCommitCoordinator(manager, table, 0);
    openCoordinator = coordinator;
    IndexedSessionContext context = context(manager, table, coordinator, vacuum);
    IndexedTransactionSession first = session(context, Long.BYTES);
    IndexedTransactionSession second = session(context, Long.BYTES);
    TransactionOutcome firstOutcome = new TransactionOutcome();
    TransactionOutcome secondOutcome = new TransactionOutcome();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
      assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
      assertEquals(StatusCode.OK, first.insert(0, 41, row(410)));
      assertEquals(StatusCode.OK, second.insert(0, 42, row(420)));
      long firstSequence = table.currentCommitSequence() + 1;
      Future<StatusCode> firstCommit = executor.submit(() -> first.commit(firstOutcome));
      awaitQueueEnqueues(coordinator, 1);
      heldForce.awaitEntered();
      assertEquals(firstSequence, table.currentCommitSequence());

      Future<StatusCode> secondCommit = executor.submit(() -> second.commit(secondOutcome));
      awaitQueueEnqueues(coordinator, 2);
      awaitPressurePark(coordinator, table, second, firstSequence);
      IndexedGroupCommitTelemetry parked = new IndexedGroupCommitTelemetry();
      assertEquals(StatusCode.OK, coordinator.copyTelemetry(parked));
      assertFalse(secondOutcome.isAvailable());
      assertEquals(TransactionState.PREPARED, second.transaction().state());
      assertEquals(firstSequence, table.currentCommitSequence());
      assertThrows(TimeoutException.class, () -> secondCommit.get(100, TimeUnit.MILLISECONDS));

      IndexedGroupCommitTelemetry stillParked = new IndexedGroupCommitTelemetry();
      assertEquals(StatusCode.OK, coordinator.copyTelemetry(stillParked));
      assertEquals(parked.attemptedGroupCohorts(), stillParked.attemptedGroupCohorts());
      assertEquals(
          parked.stageCount(IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_PREFLIGHT),
          stillParked.stageCount(
              IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_PREFLIGHT));

      heldForce.release();
      assertEquals(StatusCode.OK, firstCommit.get(5, TimeUnit.SECONDS));
      assertEquals(StatusCode.RETRY, secondCommit.get(5, TimeUnit.SECONDS));
      assertEquals(TransactionState.COMMITTED, firstOutcome.state());
      assertEquals(TransactionState.ABORTED, secondOutcome.state());
      assertEquals(firstSequence, firstOutcome.commitSequence());
      IndexedGroupCommitTelemetry completed = new IndexedGroupCommitTelemetry();
      assertEquals(StatusCode.OK, coordinator.copyTelemetry(completed));
      assertEquals(parked.attemptedGroupCohorts() + 1, completed.attemptedGroupCohorts());
      assertEquals(
          parked.stageCount(IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_PREFLIGHT) + 1,
          completed.stageCount(IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_PREFLIGHT));
      assertTrue(completed.reconciles());
      assertEquals(1, table.rowCount());
      assertEquals(0, manager.activeTransactionCount());
      assertEquals(0, manager.activeLockCount());
      assertEquals(0, manager.waitingLockCount());
    } finally {
      heldForce.release();
      executor.shutdownNow();
      StatusCode closed = coordinator.close();
      assertTrue(closed.isOk() || closed == StatusCode.CLOSED);
      assertEquals(StatusCode.OK, first.close());
      assertEquals(StatusCode.OK, second.close());
      assertEquals(StatusCode.OK, table.flush());
      assertEquals(StatusCode.OK, table.close());
      assertEquals(StatusCode.OK, wal.close());
    }
  }

  @Test
  void cancelledSuccessorPreflightDoesNotOrphanEarlierForceTarget() throws Exception {
    DatabasePageCachePlan constrained = DatabasePageCacheTestPlan.geometry(5, 8, 16);
    ForcedGroupFixture fixture = forcedFixture(constrained);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StatusCode> firstCommit =
          executor.submit(() -> fixture.first.commit(fixture.firstOutcome));
      awaitQueueEnqueues(fixture.coordinator, 1);
      fixture.heldForce.awaitEntered();
      assertTrue(fixture.table.forceActive());
      assertFalse(fixture.firstOutcome.isAvailable());

      long sealedTail = fixture.wal.tailEnd();
      assertEquals(StatusCode.OK, fixture.second.prepareLogicalCommit());
      assertEquals(
          StatusCode.OK, fixture.second.prepareCoordinatedCommit(fixture.secondOutcome));
      IndexedPreparedLogicalCommit[] successor = {fixture.second.preparedCommit()};
      IndexedPreparedCommitCohortDemand demand = new IndexedPreparedCommitCohortDemand();
      StatusCode preflight = fixture.table.preflightHybridCommitGroup(
          successor, 1, fixture.manager.oldestVisibleCommitSequence(), demand);
      assertTrue(
          preflight == StatusCode.RETRY || preflight == StatusCode.RESOURCE_EXHAUSTED,
          preflight.toString());
      assertEquals(0, demand.acceptedCount());
      assertTrue(demand.capacitySplit());
      assertEquals(sealedTail, fixture.wal.tailEnd());
      assertTrue(fixture.table.forceActive());
      assertFalse(firstCommit.isDone());
      assertEquals(StatusCode.OK, fixture.table.cancelCommitGroup());
      assertTrue(fixture.table.forceActive());
      io.riverdb.tx.Transaction[] successorTransaction = {fixture.second.groupTransaction()};
      TransactionOutcome[] successorOutcome = {fixture.secondOutcome};
      assertEquals(StatusCode.OK, fixture.manager.abortPreparedCommitGroup(
          successorTransaction, successorOutcome, 1, preflight));
      assertEquals(preflight, fixture.second.completeCoordinatedCommit(preflight));
      assertEquals(TransactionState.ABORTED, fixture.secondOutcome.state());

      fixture.heldForce.release();
      assertEquals(StatusCode.OK, firstCommit.get(5, TimeUnit.SECONDS));
      assertEquals(TransactionState.COMMITTED, fixture.firstOutcome.state());
      assertFalse(fixture.table.forceActive());
      assertEquals(0, fixture.manager.activeTransactionCount());
      assertEquals(0, fixture.manager.activeLockCount());
      assertEquals(0, fixture.manager.waitingLockCount());
      assertNoPagePins(fixture.store, fixture.table);
    } finally {
      fixture.heldForce.release();
      executor.shutdownNow();
    }
  }

  @Test
  void constrainedPhysicalAdmissionMatchesDirectAndSingleMemberGroup() throws Exception {
    SingleCommitResult direct = commitSingleUnderPressure(false, false);
    SingleCommitResult grouped = commitSingleUnderPressure(true, false);

    assertEquals(direct, grouped);
    assertEquals(StatusCode.OK, direct.status());
    assertEquals(TransactionState.COMMITTED, direct.state());
    assertEquals(StatusCode.OK, direct.visibility());
    assertEquals(710, direct.value());
    assertEquals(1, direct.rows());
    assertEquals(0, direct.activeTransactions());
    assertEquals(0, direct.activeLocks());
    assertEquals(0, direct.waitingLocks());

    SingleCommitResult rejectedDirect = commitSingleUnderPressure(false, true);
    SingleCommitResult rejectedGroup = commitSingleUnderPressure(true, true);

    assertEquals(rejectedDirect, rejectedGroup);
    assertEquals(StatusCode.RETRY, rejectedDirect.status());
    assertEquals(TransactionState.ABORTED, rejectedDirect.state());
    assertEquals(StatusCode.CONFLICT, rejectedDirect.visibility());
    assertEquals(0, rejectedDirect.rows());
    assertEquals(0, rejectedDirect.activeTransactions());
    assertEquals(0, rejectedDirect.activeLocks());
    assertEquals(0, rejectedDirect.waitingLocks());
  }

  private static void awaitQueueEnqueues(
      IndexedGroupCommitCoordinator coordinator, long expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    IndexedGroupCommitTelemetry telemetry = new IndexedGroupCommitTelemetry();
    while (System.nanoTime() < deadline) {
      assertEquals(StatusCode.OK, coordinator.copyTelemetry(telemetry));
      if (telemetry.queue().enqueues() >= expected) return;
      Thread.sleep(1);
    }
    assertEquals(expected, telemetry.queue().enqueues());
  }

  private static void awaitPressurePark(
      IndexedGroupCommitCoordinator coordinator,
      IndexedTable table,
      IndexedTransactionSession session,
      long expectedSequence) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (coordinator.writerIdle()
          && session.transaction().state() == TransactionState.PREPARED
          && table.currentCommitSequence() == expectedSequence) return;
      Thread.sleep(1);
    }
    assertTrue(
        coordinator.writerIdle()
            && session.transaction().state() == TransactionState.PREPARED
            && table.currentCommitSequence() == expectedSequence,
        "deferred commit did not reach the durability-pressure park");
  }

  private static void assertNoPagePins(IndexedTableStore store, IndexedTable table)
      throws Exception {
    IndexedPageSet pages = IndexedRelationalWalStorageFixtures.pageSet(store);
    Field field = IndexedPageSet.class.getDeclaredField("cache");
    field.setAccessible(true);
    IndexedPageFrameCache cache = (IndexedPageFrameCache) field.get(pages);
    synchronized (table) {
      for (IndexedPageFrame frame : cache.currentFrames) {
        if (frame != null) assertEquals(0, frame.pinCount);
      }
    }
  }

  private SingleCommitResult commitSingleUnderPressure(
      boolean grouped, boolean reject) throws Exception {
    DatabasePageCachePlan constrained = DatabasePageCacheTestPlan.geometry(
        reject ? 4 : 5, 8, 16);
    FaultFixture faults = new FaultFixture();
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(faults.directory, DATABASE, GENERATION, walResult));
    LocalWal wal = walResult.wal();
    openDirectory = faults.directory;
    openWal = wal;
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK, IndexedTableStore.create(
        faults.directory, wal, DATABASE, GENERATION,
        DatabasePageCacheTestPlan.providerLease(constrained, 4), storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    IndexedTable table = tableResult.table();
    openTable = table;
    IndexedPageSet pages = IndexedRelationalWalStorageFixtures.pageSet(storeResult.store());
    IndexedPageGenerationPin[] pins = new IndexedPageGenerationPin[3];
    if (reject) {
      int[] pageIds = {
          IndexedTableKernel.HEAP_PAGE_ID,
          IndexedTableKernel.ROOT_META_PAGE_ID,
          IndexedTableKernel.INITIAL_LEAF_PAGE_ID
      };
      for (int index = 0; index < pins.length; index++) {
        pins[index] = new IndexedPageGenerationPin();
        assertEquals(StatusCode.OK,
            pages.pinPageAt(pageIds[index], table.currentCommitSequence(), pins[index]));
      }
    }
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedGroupCommitCoordinator coordinator = grouped
        ? new IndexedGroupCommitCoordinator(manager, table) : null;
    openCoordinator = coordinator;
    IndexedTransactionSession session = session(context(manager, table, coordinator, vacuum), 8);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(0, 71, row(710)));
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.commit(outcome);
    HeapRowResult fetched = new HeapRowResult();
    StatusCode visibility = table.fetchByKey(0, 71, fetched);
    SingleCommitResult result = new SingleCommitResult(
        status,
        outcome.state(),
        visibility,
        visibility.isOk() ? value(fetched) : 0,
        table.rowCount(),
        manager.activeTransactionCount(),
        manager.activeLockCount(),
        manager.waitingLockCount());
    assertEquals(StatusCode.OK, session.close());
    if (coordinator != null) assertEquals(StatusCode.OK, coordinator.close());
    if (reject) {
      for (int index = pins.length - 1; index >= 0; index--) {
        assertEquals(StatusCode.OK, pages.unpinPage(pins[index]));
      }
    }
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    return result;
  }

  private record SingleCommitResult(
      StatusCode status,
      TransactionState state,
      StatusCode visibility,
      long value,
      long rows,
      long activeTransactions,
      long activeLocks,
      long waitingLocks) {}

  @Test
  void forceFailureAfterPressureSplitTerminalizesAcceptedPrefixAndDeferredSuffix()
      throws Exception {
    DatabasePageCachePlan constrained = DatabasePageCacheTestPlan.geometry(5, 8, 16);
    FaultFixture faults = new FaultFixture();
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(faults.directory, DATABASE, GENERATION, walResult));
    LocalWal wal = walResult.wal();
    openDirectory = faults.directory;
    openWal = wal;
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.create(
            faults.directory, wal, DATABASE, GENERATION,
            DatabasePageCacheTestPlan.providerLease(constrained, 4), storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    IndexedTable table = tableResult.table();
    openTable = table;
    long durableTail = wal.durableEnd();
    long publishedBefore = table.currentCommitSequence();
    long rowsBefore = table.rowCount();
    assertEquals(StatusCode.OK, faults.arm(
        DirectoryOperation.FILE_FORCE,
        FaultOperation.DIRECTORY_FILE_FORCE,
        FaultAction.FORCE_FAILURE));
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedGroupCommitCoordinator coordinator = new IndexedGroupCommitCoordinator(
        manager, table, TimeUnit.MILLISECONDS.toNanos(500));
    openCoordinator = coordinator;
    IndexedSessionContext context = context(manager, table, coordinator, vacuum);
    IndexedTransactionSession[] sessions = {
        session(context, Long.BYTES), session(context, Long.BYTES), session(context, Long.BYTES)
    };
    TransactionOutcome[] outcomes = {
        new TransactionOutcome(), new TransactionOutcome(), new TransactionOutcome()
    };
    List<Future<StatusCode>> commits = new ArrayList<>(3);
    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      for (int index = 0; index < sessions.length; index++) {
        assertEquals(StatusCode.OK, sessions[index].begin(IsolationLevel.REPEATABLE_READ));
        assertEquals(StatusCode.OK, sessions[index].insert(0, 61 + index, row(610 + index)));
      }
      for (int index = 0; index < sessions.length; index++) {
        int member = index;
        commits.add(executor.submit(() -> sessions[member].commit(outcomes[member])));
        awaitQueueEnqueues(coordinator, index + 1);
      }
      for (Future<StatusCode> commit : commits) {
        assertFalse(commit.get(5, TimeUnit.SECONDS).isOk());
      }
    } finally {
      executor.shutdownNow();
    }

    for (TransactionOutcome outcome : outcomes) {
      assertFalse(outcome.state() == TransactionState.COMMITTED);
    }
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    assertEquals(0, manager.waitingLockCount());
    IndexedGroupCommitTelemetry telemetry = new IndexedGroupCommitTelemetry();
    assertEquals(StatusCode.OK, coordinator.copyTelemetry(telemetry));
    assertTrue(telemetry.queue().capacityConstrainedSelections() > 0);
    assertEquals(publishedBefore + 1, table.currentCommitSequence());
    assertEquals(rowsBefore + 1, table.rowCount());
    assertEquals(StatusCode.FENCED, storeResult.store().admission());
    IndexedTransactionSession rejected = session(context, Long.BYTES);
    assertEquals(StatusCode.FENCED, rejected.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, rejected.close());
    StatusCode coordinatorStatus = coordinator.close();
    assertTrue(coordinatorStatus.isOk() || coordinatorStatus == StatusCode.CLOSED);
    for (IndexedTransactionSession session : sessions) {
      assertEquals(StatusCode.OK, session.close());
    }
    StatusCode failedWalClose = wal.close();
    assertTrue(failedWalClose.isOk() || failedWalClose == StatusCode.CLOSED);
    assertEquals(StatusCode.OK, faults.directory.crash());
    assertEquals(StatusCode.OK, faults.directory.restart());
    LocalWalOpenResult recoveredWalResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.openExisting(
        faults.directory, DATABASE, GENERATION, recoveredWalResult));
    assertEquals(durableTail, recoveredWalResult.wal().tailEnd());
    IndexedTableStoreOpenResult recoveredStoreResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK, IndexedTableStore.openExisting(
        faults.directory, recoveredWalResult.wal(), DATABASE, GENERATION,
        databaseProviderLease(4), recoveredStoreResult));
    IndexedTableOpenResult recoveredTableResult = new IndexedTableOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTable.open(recoveredStoreResult.store(), recoveredTableResult));
    assertEquals(publishedBefore, recoveredTableResult.table().currentCommitSequence());
    assertEquals(rowsBefore, recoveredTableResult.table().rowCount());
    for (int index = 0; index < sessions.length; index++) {
      assertEquals(StatusCode.CONFLICT,
          recoveredTableResult.table().fetchByKey(0, 61 + index, new HeapRowResult()));
    }
    assertEquals(StatusCode.OK, recoveredTableResult.table().close());
    assertEquals(StatusCode.OK, recoveredWalResult.wal().close());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("groupFaults")
  void groupedFacadeCommitFailureWithholdsAcknowledgmentAndFencesAdmission(
      String name,
      DirectoryOperation operation,
      FaultOperation faultOperation,
      FaultAction action) throws Exception {
    FaultFixture fixture = new FaultFixture();
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(fixture.directory, DATABASE, GENERATION, walResult));
    LocalWal wal = walResult.wal();
    openDirectory = fixture.directory;
    openWal = wal;
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.create(
            fixture.directory, wal, DATABASE, GENERATION,
            databaseProviderLease(4), storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    IndexedTable table = tableResult.table();
    openTable = table;
    long publishedBefore = table.currentCommitSequence();
    assertEquals(StatusCode.OK, fixture.arm(operation, faultOperation, action));

    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedGroupCommitCoordinator coordinator =
        new IndexedGroupCommitCoordinator(manager, table, 500_000_000);
    openCoordinator = coordinator;
    IndexedSessionContext context = context(manager, table, coordinator, vacuum);
    IndexedTransactionSession first = session(context, Long.BYTES);
    IndexedTransactionSession second = session(context, Long.BYTES);
    TransactionOutcome firstOutcome = new TransactionOutcome();
    TransactionOutcome secondOutcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert( 0,41, row(410)));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.insert( 0,42, row(420)));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<StatusCode> firstCommit = executor.submit(
          () -> coordinatedCommit(first, firstOutcome, ready, start));
      Future<StatusCode> secondCommit = executor.submit(
          () -> coordinatedCommit(second, secondOutcome, ready, start));
      ready.await();
      start.countDown();
      assertEquals(StatusCode.IO_FAILURE, firstCommit.get());
      assertEquals(StatusCode.IO_FAILURE, secondCommit.get());
    } finally {
      executor.shutdownNow();
    }

    if (operation == DirectoryOperation.FILE_FORCE) {
      assertTrue(table.currentCommitSequence() > publishedBefore);
    } else {
      assertEquals(publishedBefore, table.currentCommitSequence());
    }
    assertEquals(StatusCode.FENCED, table.awaitDurability(table.currentCommitSequence()));
    assertEquals(TransactionState.INDETERMINATE, first.transaction().state());
    assertEquals(TransactionState.INDETERMINATE, second.transaction().state());
    assertEquals(TransactionState.INDETERMINATE, firstOutcome.state());
    assertEquals(TransactionState.INDETERMINATE, secondOutcome.state());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    assertEquals(0, manager.waitingLockCount());
    assertEquals(StatusCode.FENCED, storeResult.store().admission());
    IndexedTransactionSession rejected = session(context, Long.BYTES);
    TransactionOutcome rejectedOutcome = new TransactionOutcome();
    assertEquals(StatusCode.FENCED, rejected.begin(IsolationLevel.REPEATABLE_READ));
    assertFalse(rejectedOutcome.isAvailable());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
    assertEquals(StatusCode.OK, rejected.close());
    StatusCode coordinatorClose = coordinator.close();
    assertTrue(coordinatorClose.isOk() || coordinatorClose == StatusCode.CLOSED);
    StatusCode failedWalClose = wal.close();
    assertTrue(failedWalClose.isOk() || failedWalClose == StatusCode.CLOSED);
    assertEquals(StatusCode.OK, fixture.directory.crash());
  }

  private static Stream<Arguments> groupFaults() {
    return Stream.of(
        Arguments.of(
            Named.of("file write", "write"),
            DirectoryOperation.FILE_WRITE,
            FaultOperation.DIRECTORY_FILE_WRITE,
            FaultAction.PARTIAL_WRITE),
        Arguments.of(
            Named.of("file force", "force"),
            DirectoryOperation.FILE_FORCE,
            FaultOperation.DIRECTORY_FILE_FORCE,
            FaultAction.FORCE_FAILURE));
  }

  private static StatusCode coordinatedCommit(
      IndexedTransactionSession session,
      TransactionOutcome outcome,
      CountDownLatch ready,
      CountDownLatch start) throws InterruptedException {
    ready.countDown();
    start.await();
    return session.commit(outcome);
  }

  private static ByteBuffer row(long value) {
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    return row;
  }

  private static long value(HeapRowResult result) {
    ByteBuffer target = ByteBuffer.allocate(result.length());
    assertEquals(StatusCode.OK, result.copyTo(target));
    return target.getLong(0);
  }

  private static IndexedSessionContext context(
      TransactionManager manager,
      IndexedTable table,
      IndexedGroupCommitCoordinator coordinator,
      IndexedVacuum vacuum) {
    IndexedSessionContext.Result result = new IndexedSessionContext.Result();
    assertEquals(
        StatusCode.OK,
        IndexedSessionContext.bind(manager, table, coordinator, vacuum, result));
    return result.context();
  }

  private static IndexedTransactionSession session(
      IndexedSessionContext context, int maximumRowBytes) {
    IndexedTransactionSessionOpenResult result =
        new IndexedTransactionSessionOpenResult();
    assertEquals(StatusCode.OK, context.openSession(maximumRowBytes, result));
    return result.session();
  }

  private static final class ForcedGroupFixture {
    private final FaultFixture faults = new FaultFixture();
    private final LocalWal wal;
    private final IndexedTableStore store;
    private final IndexedTable table;
    private final TransactionManager manager;
    private final IndexedTransactionSession first;
    private final IndexedTransactionSession second;
    private final IndexedTransactionSession[] borrowed = new IndexedTransactionSession[4];
    private final TransactionOutcome firstOutcome = new TransactionOutcome();
    private final TransactionOutcome secondOutcome = new TransactionOutcome();
    private final HeldForce heldForce;
    private final IndexedGroupCommitCoordinator coordinator;
    private final long firstSequence;
    private final long secondSequence;
    private final long rowsBefore;
    private ExecutorService commits;
    private Future<StatusCode> firstCommit;
    private Future<StatusCode> secondCommit;
    private int borrowedCount;
    private boolean closed;

    private ForcedGroupFixture() {
      this(pageCachePlan());
    }

    private ForcedGroupFixture(boolean delete) {
      this(pageCachePlan(), delete);
    }

    private ForcedGroupFixture(DatabasePageCachePlan cachePlan) {
      this(cachePlan, false);
    }

    private ForcedGroupFixture(DatabasePageCachePlan cachePlan, boolean delete) {
      LocalWalOpenResult walResult = new LocalWalOpenResult();
      assertEquals(
          StatusCode.OK,
          LocalWal.open(faults.directory, DATABASE, GENERATION, walResult));
      wal = walResult.wal();
      IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
      assertEquals(
          StatusCode.OK,
          IndexedTableStore.create(
              faults.directory, wal, DATABASE, GENERATION,
              DatabasePageCacheTestPlan.providerLease(cachePlan, 4), storeResult));
      store = storeResult.store();
      IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
      assertEquals(StatusCode.OK, IndexedTable.create(store, tableResult));
      table = tableResult.table();
      manager = new TransactionManager(
          DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
      IndexedVacuum vacuum = new IndexedVacuum(manager, table);
      IndexedSessionContext directContext = context(manager, table, null, vacuum);
      if (delete) {
        IndexedTransactionSession seed = session(directContext, Long.BYTES);
        assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
        assertEquals(StatusCode.OK, seed.insert(0, 43, row(430)));
        assertEquals(StatusCode.OK, seed.insert(0, 999, row(9990)));
        assertEquals(StatusCode.OK, seed.commit(new TransactionOutcome()));
        assertEquals(StatusCode.OK, seed.close());
      }
      heldForce = new HeldForce(walFile(wal));
      replaceWalFile(wal, heldForce);
      coordinator = new IndexedGroupCommitCoordinator(
          manager, table, TimeUnit.MILLISECONDS.toNanos(500));
      IndexedSessionContext groupedContext = context(manager, table, coordinator, vacuum);
      first = session(groupedContext, Long.BYTES);
      second = session(groupedContext, Long.BYTES);
      assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
      assertEquals(StatusCode.OK, first.insert(0, 41, row(410)));
      if (delete) assertEquals(StatusCode.OK, first.delete(0, 999));
      assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
      assertEquals(StatusCode.OK, second.insert(0, 42, row(420)));
      rowsBefore = table.rowCount();
      firstSequence = wal.nextCommitSequence();
      secondSequence = firstSequence + 1;
      assertTrue(manager.activeLockCount() > 0);
    }

    private void publish() throws Exception {
      commits = Executors.newFixedThreadPool(2);
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      firstCommit = commits.submit(
          () -> coordinatedCommit(first, firstOutcome, ready, start));
      secondCommit = commits.submit(
          () -> coordinatedCommit(second, secondOutcome, ready, start));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      awaitQueueEnqueues(coordinator, 2);
      heldForce.awaitEntered();
    }

    private void complete(StatusCode expected) throws Exception {
      heldForce.release();
      assertEquals(expected, firstCommit.get(5, TimeUnit.SECONDS));
      assertEquals(expected, secondCommit.get(5, TimeUnit.SECONDS));
      commits.shutdownNow();
      StatusCode closed = coordinator.close();
      assertTrue(closed.isOk() || closed == StatusCode.CLOSED);
      assertFalse(first.transactionLifecycleActive());
      assertFalse(second.transactionLifecycleActive());
    }

    private void failHeldForce() { heldForce.fail(); }

    private IndexedTransactionSession newSession() {
      IndexedTransactionSession result =
          session(context(manager, table, null, new IndexedVacuum(manager, table)), Long.BYTES);
      borrowed[borrowedCount++] = result;
      return result;
    }

    private void close() {
      if (closed) return;
      closed = true;
      heldForce.release();
      coordinator.close();
      if (commits != null) {
        commits.shutdownNow();
        try {
          commits.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      closeSession(first);
      closeSession(second);
      for (int index = 0; index < borrowedCount; index++) closeSession(borrowed[index]);
      StatusCode flush = table.flush();
      if (flush.isOk()) {
        table.close();
      }
      StatusCode walStatus = wal.close();
      if (!flush.isOk() || !(walStatus.isOk() || walStatus == StatusCode.CLOSED)) {
        faults.directory.crash();
      }
    }

    private static void closeSession(IndexedTransactionSession session) {
      if (session.transactionLifecycleActive()) {
        session.abort(new TransactionOutcome());
      }
      session.close();
    }

    private void assertTerminalFailure() {
      assertEquals(TransactionState.INDETERMINATE, first.transaction().state());
      assertEquals(TransactionState.INDETERMINATE, second.transaction().state());
      assertEquals(TransactionState.INDETERMINATE, firstOutcome.state());
      assertEquals(TransactionState.INDETERMINATE, secondOutcome.state());
      assertEquals(0, manager.activeTransactionCount());
      assertEquals(0, manager.activeLockCount());
      assertEquals(0, manager.waitingLockCount());
      assertEquals(StatusCode.FENCED, store.admission());
      assertEquals(StatusCode.FENCED, table.flush());
      assertEquals(StatusCode.FENCED, wal.reserve(1, new io.riverdb.wal.local.LocalWalReservation()));
      assertEquals(StatusCode.OK, first.close());
      assertEquals(StatusCode.OK, second.close());
    }

    private static DurableFile walFile(LocalWal wal) {
      try {
        Field state = LocalWal.class.getDeclaredField("appendState");
        state.setAccessible(true);
        Object append = state.get(wal);
        Field field = append.getClass().getDeclaredField("file");
        field.setAccessible(true);
        return (DurableFile) field.get(append);
      } catch (ReflectiveOperationException reflection) {
        throw new AssertionError(reflection);
      }
    }

    private static void replaceWalFile(LocalWal wal, DurableFile file) {
      try {
        Field state = LocalWal.class.getDeclaredField("appendState");
        state.setAccessible(true);
        Object append = state.get(wal);
        Field field = append.getClass().getDeclaredField("file");
        field.setAccessible(true);
        field.set(append, file);
      } catch (ReflectiveOperationException reflection) {
        throw new AssertionError(reflection);
      }
    }

    private static final class HeldForce implements DurableFile {
      private final DurableFile delegate;
      private final CountDownLatch entered = new CountDownLatch(1);
      private final CountDownLatch release = new CountDownLatch(1);
      private volatile boolean fail;
      private boolean first = true;

      private HeldForce(DurableFile file) { delegate = file; }

      private synchronized boolean hold() {
        boolean result = first;
        first = false;
        return result;
      }

      private StatusCode force(ForceOperation operation) {
        if (!hold()) return operation.force();
        entered.countDown();
        try {
          if (!release.await(15, TimeUnit.SECONDS)) return StatusCode.IO_FAILURE;
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return StatusCode.CANCELLED;
        }
        return fail ? StatusCode.IO_FAILURE : operation.force();
      }

      private void awaitEntered() throws InterruptedException {
        assertTrue(entered.await(5, TimeUnit.SECONDS), "group force did not start");
      }

      private void release() { release.countDown(); }
      private void fail() { fail = true; }

      @Override public StatusCode force(ForceMode mode) {
        return force(() -> delegate.force(mode));
      }
      @Override public StatusCode force(
          long startInclusive, long endExclusive, ForceMode mode) {
        return force(() -> delegate.force(startInclusive, endExclusive, mode));
      }
      @Override public StatusCode read(long offset, ByteBuffer target, IoResult result) {
        return delegate.read(offset, target, result);
      }
      @Override public StatusCode write(long offset, ByteBuffer source, IoResult result) {
        return delegate.write(offset, source, result);
      }
      @Override public StatusCode truncate(long bytes) { return delegate.truncate(bytes); }
      @Override public StatusCode size(FileSizeResult result) { return delegate.size(result); }
      @Override public StatusCode close() { return delegate.close(); }
    }
  }

  @FunctionalInterface
  private interface ForceOperation {
    StatusCode force();
  }

  private static final class FaultFixture {
    private final CrashPointController controller = new CrashPointController(1);
    private final DirectoryFaultPoints points = new DirectoryFaultPoints();
    private final FaultingDurableDirectory directory;

    private FaultFixture() {
      FaultPointRegistry registry = new FaultPointRegistry(
          DirectoryOperation.values().length * FaultBoundary.values().length);
      for (DirectoryOperation operation : DirectoryOperation.values()) {
        for (FaultBoundary boundary : FaultBoundary.values()) {
          FaultPointSlot slot = new FaultPointSlot();
          String pointName = "engine-group."
              + operation.name().toLowerCase(Locale.ROOT)
              + "." + boundary.name().toLowerCase(Locale.ROOT);
          assertEquals(StatusCode.OK, registry.register(pointName, slot));
          points.set(operation, boundary, slot.value());
        }
      }
      directory = new FaultingDurableDirectory(
          16, 16 * 1024 * 1024, 32, controller, points);
    }

    private StatusCode arm(
        DirectoryOperation operation,
        FaultOperation faultOperation,
        FaultAction action) {
      return controller.addRule(
          points.point(operation, FaultBoundary.BEFORE),
          faultOperation,
          FaultBoundary.BEFORE,
          1,
          1,
          action,
          1);
    }
  }

}
