package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static io.riverdb.engine.TestDatabaseResources.pageCachePlan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class IndexedGroupCommitFaultTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(919, 929);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void preflightFailureAbortsPreparedMembersWithoutWalOrFallbackAndAllowsNextCommit() {
    DatabasePageCachePlan constrained = DatabasePageCacheTestPlan.geometry(5, 8, 16);
    ForcedGroupFixture fixture = new ForcedGroupFixture(constrained);
    long tail = fixture.wal.tailEnd();
    long journalSequence = fixture.wal.nextJournalSequence();
    long commitSequence = fixture.table.currentCommitSequence();
    long rows = fixture.table.rowCount();

    assertEquals(
        StatusCode.OK,
        fixture.table.preflightHybridCommitGroup(
            new IndexedPreparedLogicalCommit[] {fixture.first.preparedCommit()},
            1,
            fixture.manager.oldestVisibleCommitSequence()));
    assertEquals(StatusCode.OK, fixture.table.cancelCommitGroup());

    fixture.batch.process(2);
    fixture.complete(StatusCode.RESOURCE_EXHAUSTED);

    assertEquals(TransactionState.ABORTED, fixture.first.transaction().state());
    assertEquals(TransactionState.ABORTED, fixture.second.transaction().state());
    assertEquals(TransactionState.ABORTED, fixture.firstOutcome.state());
    assertEquals(TransactionState.ABORTED, fixture.secondOutcome.state());
    assertEquals(0, fixture.manager.activeTransactionCount());
    assertEquals(0, fixture.manager.activeLockCount());
    assertEquals(0, fixture.manager.waitingLockCount());
    assertEquals(StatusCode.OK, fixture.store.admission());
    assertEquals(tail, fixture.wal.tailEnd());
    assertEquals(journalSequence, fixture.wal.nextJournalSequence());
    assertEquals(commitSequence, fixture.table.currentCommitSequence());
    assertEquals(rows, fixture.table.rowCount());
    assertEquals(StatusCode.CONFLICT,
        fixture.table.fetchByKey(0, 41, new HeapRowResult()));
    assertEquals(StatusCode.CONFLICT,
        fixture.table.fetchByKey(0, 42, new HeapRowResult()));

    IndexedGroupCommitTelemetry telemetry = new IndexedGroupCommitTelemetry();
    assertEquals(StatusCode.OK, fixture.table.copyCommitTelemetry(telemetry));
    assertEquals(1, telemetry.groupFailureCohortCount(
        IndexedGroupFailureStage.PREFLIGHT));
    assertEquals(2, telemetry.groupFailureTransactionCount(
        IndexedGroupFailureStage.PREFLIGHT));
    assertEquals(0, telemetry.directCommitTransactions());

    TransactionOutcome next = new TransactionOutcome();
    assertEquals(StatusCode.OK, fixture.first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, fixture.first.insert(0, 41, row(411)));
    assertEquals(StatusCode.OK, fixture.first.commit(next));
    assertEquals(TransactionState.COMMITTED, next.state());
    assertEquals(rows + 1, fixture.table.rowCount());
    assertEquals(StatusCode.OK,
        fixture.table.fetchByKey(0, 41, new HeapRowResult()));

    assertEquals(StatusCode.OK, fixture.first.close());
    assertEquals(StatusCode.OK, fixture.second.close());
    assertEquals(StatusCode.OK, fixture.table.flush());
    assertEquals(StatusCode.OK, fixture.table.close());
    assertEquals(StatusCode.OK, fixture.wal.close());
  }

  @Test
  void unexpectedWriterFailureTerminalizesAcceptedWorkAndStopsCoordinator()
      throws Exception {
    FaultFixture faults = new FaultFixture();
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(faults.directory, DATABASE, GENERATION, walResult));
    LocalWal wal = walResult.wal();
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK,
        IndexedTableStore.create(
            faults.directory, wal, DATABASE, GENERATION,
            databaseProviderLease(4), storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    IndexedTable table = tableResult.table();
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedGroupCommitMetrics metrics = table.commitMetrics();
    ThrowingGroupCommitBatch batch = new ThrowingGroupCommitBatch(manager, table, metrics);
    IndexedGroupCommitCoordinator coordinator = new IndexedGroupCommitCoordinator(
        manager, table, TimeUnit.SECONDS.toNanos(1), batch);
    IndexedSessionContext context = context(manager, table, coordinator, vacuum);
    IndexedTransactionSession first = session(context, Long.BYTES);
    IndexedTransactionSession second = session(context, Long.BYTES);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert(0, 61, row(610)));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.insert(0, 62, row(620)));
    TransactionOutcome firstOutcome = new TransactionOutcome();
    TransactionOutcome secondOutcome = new TransactionOutcome();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<StatusCode> firstCommit = executor.submit(
          () -> coordinatedCommit(first, firstOutcome, ready, start));
      Future<StatusCode> secondCommit = executor.submit(
          () -> coordinatedCommit(second, secondOutcome, ready, start));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertEquals(StatusCode.INVARIANT_BROKEN,
          firstCommit.get(5, TimeUnit.SECONDS));
      assertEquals(StatusCode.INVARIANT_BROKEN,
          secondCommit.get(5, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    assertEquals(TransactionState.INDETERMINATE, firstOutcome.state());
    assertEquals(TransactionState.INDETERMINATE, secondOutcome.state());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    assertEquals(0, manager.waitingLockCount());
    assertEquals(StatusCode.FENCED, storeResult.store().admission());
    assertTrue(coordinator.stopped());
    assertEquals(StatusCode.CLOSED, coordinator.close());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
    assertEquals(StatusCode.OK, wal.close());
  }

  @Test
  void forcedGroupPreparationFailureTerminalizesFencesAndRecoversExactlyOnce() {
    ForcedGroupFixture fixture = new ForcedGroupFixture();
    assertTrue(fixture.batch.forceSharedGroup(2));
    assertEquals(StatusCode.OK, fixture.table.prepareForcedGroupPublication());
    long tail = fixture.wal.tailEnd();
    long nextJournalSequence = fixture.wal.nextJournalSequence();

    fixture.batch.publishForced(2);
    fixture.complete(StatusCode.INVALID_EXTERNAL_INPUT);
    fixture.assertTerminalFailure();
    fixture.assertRecoveredExactlyOnce(tail, nextJournalSequence);
  }

  @Test
  void forcedGroupInstallationFailureTerminalizesFencesAndRecoversExactlyOnce() {
    ForcedGroupFixture fixture = new ForcedGroupFixture();
    fixture.store.lastCommitSequence = Long.MAX_VALUE;

    fixture.batch.process(2);
    long tail = fixture.wal.tailEnd();
    long nextJournalSequence = fixture.wal.nextJournalSequence();
    fixture.complete(StatusCode.INVARIANT_BROKEN);
    fixture.assertTerminalFailure();
    fixture.assertRecoveredExactlyOnce(tail, nextJournalSequence);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("groupFaults")
  void groupedFacadeCommitFailureDoesNotPublishAndFencesAdmission(
      String name,
      DirectoryOperation operation,
      FaultOperation faultOperation,
      FaultAction action) throws Exception {
    FaultFixture fixture = new FaultFixture();
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(fixture.directory, DATABASE, GENERATION, walResult));
    LocalWal wal = walResult.wal();
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.create(
            fixture.directory, wal, DATABASE, GENERATION,
            databaseProviderLease(4), storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    IndexedTable table = tableResult.table();
    long publishedBefore = table.currentCommitSequence();
    assertEquals(StatusCode.OK, fixture.arm(operation, faultOperation, action));

    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedGroupCommitCoordinator coordinator =
        new IndexedGroupCommitCoordinator(manager, table, 500_000_000);
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

    assertEquals(publishedBefore, table.currentCommitSequence());
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,41, new HeapRowResult()));
    assertEquals(StatusCode.CONFLICT, table.fetchByKey( 0,42, new HeapRowResult()));
    assertEquals(TransactionState.INDETERMINATE, first.transaction().state());
    assertEquals(TransactionState.INDETERMINATE, second.transaction().state());
    assertEquals(TransactionState.INDETERMINATE, firstOutcome.state());
    assertEquals(TransactionState.INDETERMINATE, secondOutcome.state());
    assertEquals(0, manager.activeTransactionCount());
    IndexedTransactionSession rejected = session(context, Long.BYTES);
    TransactionOutcome rejectedOutcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, rejected.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, rejected.insert(0, 99, row(990)));
    assertEquals(StatusCode.FENCED, rejected.commit(rejectedOutcome));
    assertEquals(TransactionState.ABORTED, rejectedOutcome.state());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
    assertEquals(StatusCode.OK, rejected.close());
    assertEquals(StatusCode.OK, coordinator.close());
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
    private final TransactionOutcome firstOutcome = new TransactionOutcome();
    private final TransactionOutcome secondOutcome = new TransactionOutcome();
    private final IndexedGroupCommitRequest firstRequest;
    private final IndexedGroupCommitRequest secondRequest;
    private final IndexedGroupCommitBatch batch;
    private final long firstTicket;
    private final long secondTicket;
    private final long firstSequence;
    private final long secondSequence;
    private final long rowsBefore;

    private ForcedGroupFixture() {
      this(pageCachePlan());
    }

    private ForcedGroupFixture(DatabasePageCachePlan cachePlan) {
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
      IndexedSessionContext context = context(manager, table, null, vacuum);
      first = session(context, Long.BYTES);
      second = session(context, Long.BYTES);
      assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
      assertEquals(StatusCode.OK, first.insert(0, 41, row(410)));
      assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
      assertEquals(StatusCode.OK, second.insert(0, 42, row(420)));
      rowsBefore = table.rowCount();
      firstSequence = wal.nextCommitSequence();
      secondSequence = firstSequence + 1;

      firstRequest = new IndexedGroupCommitRequest(first);
      secondRequest = new IndexedGroupCommitRequest(second);
      IndexedGroupCommitMetrics metrics = table.commitMetrics();
      assertEquals(StatusCode.OK, first.prepareLogicalCommit());
      assertEquals(StatusCode.OK, second.prepareLogicalCommit());
      firstTicket = firstRequest.prepare(firstOutcome, 0, metrics);
      secondTicket = secondRequest.prepare(secondOutcome, 0, metrics);
      assertTrue(firstTicket > 0);
      assertTrue(secondTicket > 0);
      assertEquals(StatusCode.OK,
          manager.prepareCommit(first.groupTransaction(), firstRequest.outcome));
      assertEquals(StatusCode.OK,
          manager.prepareCommit(second.groupTransaction(), secondRequest.outcome));
      batch = new IndexedGroupCommitBatch(manager, table, metrics);
      assertEquals(StatusCode.OK, table.reserveHybridCommitGroupCapacity(batch.capacity()));
      batch.add(0, firstRequest);
      batch.add(1, secondRequest);
      assertTrue(manager.activeLockCount() > 0);
    }

    private void complete(StatusCode expected) {
      batch.complete(2);
      assertFalse(first.transactionLifecycleActive());
      assertFalse(second.transactionLifecycleActive());
      StatusCode firstStatus = firstRequest.await(firstTicket, firstOutcome);
      StatusCode secondStatus = secondRequest.await(secondTicket, secondOutcome);
      assertEquals(expected, firstStatus);
      assertEquals(expected, secondStatus);
      assertEquals(expected, first.completeCoordinatedCommit(firstStatus));
      assertEquals(expected, second.completeCoordinatedCommit(secondStatus));
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

    private void assertRecoveredExactlyOnce(long tail, long nextJournalSequence) {
      assertEquals(StatusCode.OK, faults.directory.crash());
      assertEquals(StatusCode.OK, faults.directory.restart());
      assertRecovered(tail, nextJournalSequence, false);
      assertEquals(StatusCode.OK, faults.directory.crash());
      assertEquals(StatusCode.OK, faults.directory.restart());
      assertRecovered(tail, nextJournalSequence, true);
    }

    private void assertRecovered(long tail, long nextJournalSequence, boolean close) {
      LocalWalOpenResult walResult = new LocalWalOpenResult();
      assertEquals(
          StatusCode.OK,
          LocalWal.openExisting(faults.directory, DATABASE, GENERATION, walResult));
      LocalWal recoveredWal = walResult.wal();
      assertEquals(tail, recoveredWal.tailEnd());
      assertEquals(nextJournalSequence, recoveredWal.nextJournalSequence());
      assertEquals(secondSequence + 1, recoveredWal.nextCommitSequence());
      IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
      assertEquals(
          StatusCode.OK,
          IndexedTableStore.openExisting(
              faults.directory, recoveredWal, DATABASE, GENERATION,
              databaseProviderLease(4), storeResult));
      IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
      assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
      IndexedTable recoveredTable = tableResult.table();
      assertEquals(secondSequence, recoveredTable.currentCommitSequence());
      assertEquals(rowsBefore + 2, recoveredTable.rowCount());
      assertValue(recoveredTable, firstSequence, 41, 410);
      assertMissing(recoveredTable, firstSequence, 42);
      assertValue(recoveredTable, secondSequence, 41, 410);
      assertValue(recoveredTable, secondSequence, 42, 420);
      if (close) {
        assertEquals(StatusCode.OK, recoveredTable.flush());
        assertEquals(StatusCode.OK, recoveredTable.close());
        assertEquals(StatusCode.OK, recoveredWal.close());
      }
    }

    private static void assertValue(
        IndexedTable table, long sequence, long key, long expected) {
      HeapRowResult fetched = new HeapRowResult();
      assertEquals(StatusCode.OK, table.fetchByKeyAt(sequence, 0, key, fetched));
      assertEquals(expected, value(fetched));
    }

    private static void assertMissing(IndexedTable table, long sequence, long key) {
      assertEquals(
          StatusCode.CONFLICT,
          table.fetchByKeyAt(sequence, 0, key, new HeapRowResult()));
    }
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

  private static final class ThrowingGroupCommitBatch extends IndexedGroupCommitBatch {
    ThrowingGroupCommitBatch(
        TransactionManager manager,
        IndexedTable table,
        IndexedGroupCommitMetrics metrics) {
      super(manager, table, metrics);
    }

    @Override
    void process(int count) {
      throw new IllegalStateException("injected writer failure");
    }
  }
}
