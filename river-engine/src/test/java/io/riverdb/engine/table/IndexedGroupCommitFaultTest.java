package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.testsupport.fault.CrashPointController;
import io.riverdb.engine.testsupport.fault.DirectoryFaultPoints;
import io.riverdb.engine.testsupport.fault.DirectoryOperation;
import io.riverdb.engine.testsupport.fault.FaultAction;
import io.riverdb.engine.testsupport.fault.FaultBoundary;
import io.riverdb.engine.testsupport.fault.FaultOperation;
import io.riverdb.engine.testsupport.fault.FaultPointRegistry;
import io.riverdb.engine.testsupport.fault.FaultPointSlot;
import io.riverdb.engine.testsupport.fault.FaultingDurableDirectory;
import io.riverdb.storage.heap.HeapInsertResult;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class IndexedGroupCommitFaultTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(919, 929);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

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
            fixture.directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    IndexedTable table = tableResult.table();
    long publishedBefore = table.currentCommitSequence();
    assertEquals(StatusCode.OK, fixture.arm(operation, faultOperation, action));

    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
    IndexedGroupCommitCoordinator coordinator =
        new IndexedGroupCommitCoordinator(manager, table, 50_000_000);
    IndexedTransactionSession first =
        new IndexedTransactionSession(manager, table, Long.BYTES, coordinator);
    IndexedTransactionSession second =
        new IndexedTransactionSession(manager, table, Long.BYTES, coordinator);
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
    assertEquals(
        StatusCode.FENCED,
        table.insert(99, 0, 99, row(990), new HeapInsertResult()));
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
