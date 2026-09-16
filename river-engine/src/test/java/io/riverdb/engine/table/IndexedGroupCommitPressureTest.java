package io.riverdb.engine.table;

import static io.riverdb.engine.table.IndexedGroupCommitFaultTest.row;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.engine.runtime.DatabasePageCacheTestPlan;
import io.riverdb.engine.testsupport.fault.DirectoryOperation;
import io.riverdb.engine.testsupport.fault.FaultAction;
import io.riverdb.engine.testsupport.fault.FaultOperation;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

final class IndexedGroupCommitPressureTest {
  private final GroupCommitFaultCleanup cleanup = new GroupCommitFaultCleanup();

  @AfterEach
  void closeResources() {
    cleanup.close();
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
      assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> delivered.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
      assertEquals(StatusCode.OK, fixture.faults.arm(
          DirectoryOperation.FILE_FORCE, FaultOperation.DIRECTORY_FILE_FORCE,
          FaultAction.FORCE_FAILURE));
      fixture.complete(StatusCode.IO_FAILURE);
      assertEquals(StatusCode.FENCED, delivered.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertEquals(TransactionState.ABORTED, reader.transaction().state());
    assertEquals(TransactionState.ABORTED, outcome.state());
    assertFalse(reader.transactionLifecycleActive());
    fixture.assertTerminalFailure();
    assertEquals(StatusCode.OK, reader.close());
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

  private ForcedGroupFixture forcedFixture(boolean delete) {
    cleanup.forcedFixture = new ForcedGroupFixture(delete);
    return cleanup.forcedFixture;
  }

  private SingleCommitResult commitSingleUnderPressure(boolean grouped, boolean reject)
      throws Exception {
    DatabasePageCachePlan constrained = DatabasePageCacheTestPlan.geometry(
        reject ? 4 : 5, 8, 16);
    FaultFixture faults = new FaultFixture();
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(
        faults.directory, IndexedGroupCommitFaultTest.DATABASE,
        IndexedGroupCommitFaultTest.GENERATION, walResult));
    LocalWal wal = walResult.wal();
    cleanup.directory = faults.directory;
    cleanup.wal = wal;
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK, IndexedTableStore.create(
        faults.directory, wal, IndexedGroupCommitFaultTest.DATABASE,
        IndexedGroupCommitFaultTest.GENERATION,
        DatabasePageCacheTestPlan.providerLease(constrained, 4), storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    IndexedTable table = tableResult.table();
    cleanup.table = table;
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
        IndexedGroupCommitFaultTest.DATABASE.high(), IndexedGroupCommitFaultTest.DATABASE.low(),
        table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedGroupCommitCoordinator coordinator = grouped
        ? new IndexedGroupCommitCoordinator(manager, table) : null;
    cleanup.coordinator = coordinator;
    IndexedTransactionSession session = IndexedGroupCommitFaultTest.session(
        IndexedGroupCommitFaultTest.context(manager, table, coordinator, vacuum), 8);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.insert(0, 71, IndexedGroupCommitFaultTest.row(710)));
    TransactionOutcome outcome = new TransactionOutcome();
    StatusCode status = session.commit(outcome);
    HeapRowResult fetched = new HeapRowResult();
    StatusCode visibility = table.fetchByKey(0, 71, fetched);
    SingleCommitResult result = new SingleCommitResult(
        status,
        outcome.state(),
        visibility,
        visibility.isOk() ? IndexedGroupCommitFaultTest.value(fetched) : 0,
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
}
