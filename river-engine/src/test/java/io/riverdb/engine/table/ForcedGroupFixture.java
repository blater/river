package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.pageCachePlan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.engine.runtime.DatabasePageCacheTestPlan;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ForcedGroupFixture {
  final FaultFixture faults = new FaultFixture();
  final LocalWal wal;
  final IndexedTableStore store;
  final IndexedTable table;
  final TransactionManager manager;
  final IndexedTransactionSession first;
  final IndexedTransactionSession second;
  final HeldForce heldForce;
  final IndexedGroupCommitCoordinator coordinator;
  final TransactionOutcome firstOutcome = new TransactionOutcome();
  final TransactionOutcome secondOutcome = new TransactionOutcome();
  final long firstSequence;
  final long secondSequence;
  final long rowsBefore;
  private final IndexedTransactionSession[] borrowed = new IndexedTransactionSession[4];
  private ExecutorService commits;
  private Future<StatusCode> firstCommit;
  private Future<StatusCode> secondCommit;
  private int borrowedCount;
  private boolean closed;

  ForcedGroupFixture() {
    this(pageCachePlan());
  }

  ForcedGroupFixture(boolean delete) {
    this(pageCachePlan(), delete);
  }

  ForcedGroupFixture(DatabasePageCachePlan cachePlan) {
    this(cachePlan, false);
  }

  ForcedGroupFixture(DatabasePageCachePlan cachePlan, boolean delete) {
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(
        faults.directory, IndexedGroupCommitFaultTest.DATABASE,
        IndexedGroupCommitFaultTest.GENERATION, walResult));
    wal = walResult.wal();
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK, IndexedTableStore.create(
        faults.directory, wal, IndexedGroupCommitFaultTest.DATABASE,
        IndexedGroupCommitFaultTest.GENERATION,
        DatabasePageCacheTestPlan.providerLease(cachePlan, 4), storeResult));
    store = storeResult.store();
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(store, tableResult));
    table = tableResult.table();
    manager = new TransactionManager(
        IndexedGroupCommitFaultTest.DATABASE.high(), IndexedGroupCommitFaultTest.DATABASE.low(),
        table.nextTransactionId(), 4);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext directContext = IndexedGroupCommitFaultTest.context(
        manager, table, null, vacuum);
    if (delete) seedDelete(directContext);
    heldForce = new HeldForce(walFile(wal));
    replaceWalFile(wal, heldForce);
    coordinator = new IndexedGroupCommitCoordinator(
        manager, table, TimeUnit.MILLISECONDS.toNanos(500));
    IndexedSessionContext groupedContext = IndexedGroupCommitFaultTest.context(
        manager, table, coordinator, vacuum);
    first = IndexedGroupCommitFaultTest.session(groupedContext, Long.BYTES);
    second = IndexedGroupCommitFaultTest.session(groupedContext, Long.BYTES);
    assertEquals(StatusCode.OK, first.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, first.insert(0, 41, IndexedGroupCommitFaultTest.row(410)));
    if (delete) assertEquals(StatusCode.OK, first.delete(0, 999));
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, second.insert(0, 42, IndexedGroupCommitFaultTest.row(420)));
    rowsBefore = table.rowCount();
    firstSequence = wal.nextCommitSequence();
    secondSequence = firstSequence + 1;
    assertTrue(manager.activeLockCount() > 0);
  }

  private void seedDelete(IndexedSessionContext context) {
    IndexedTransactionSession seed = IndexedGroupCommitFaultTest.session(context, Long.BYTES);
    assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, seed.insert(0, 43, IndexedGroupCommitFaultTest.row(430)));
    assertEquals(StatusCode.OK, seed.insert(0, 999, IndexedGroupCommitFaultTest.row(9990)));
    assertEquals(StatusCode.OK, seed.commit(new TransactionOutcome()));
    assertEquals(StatusCode.OK, seed.close());
  }

  void publish() throws Exception {
    commits = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    firstCommit = commits.submit(
        () -> IndexedGroupCommitFaultTest.coordinatedCommit(first, firstOutcome, ready, start));
    secondCommit = commits.submit(
        () -> IndexedGroupCommitFaultTest.coordinatedCommit(second, secondOutcome, ready, start));
    assertTrue(ready.await(5, TimeUnit.SECONDS));
    start.countDown();
    IndexedGroupCommitFaultTest.awaitQueueEnqueues(coordinator, 2);
    heldForce.awaitEntered();
  }

  void complete(StatusCode expected) throws Exception {
    heldForce.release();
    assertEquals(expected, firstCommit.get(5, TimeUnit.SECONDS));
    assertEquals(expected, secondCommit.get(5, TimeUnit.SECONDS));
    commits.shutdownNow();
    StatusCode closedStatus = coordinator.close();
    assertTrue(closedStatus.isOk() || closedStatus == StatusCode.CLOSED);
    assertFalse(first.transactionLifecycleActive());
    assertFalse(second.transactionLifecycleActive());
  }

  void failHeldForce() { heldForce.fail(); }

  IndexedTransactionSession newSession() {
    IndexedTransactionSession result = IndexedGroupCommitFaultTest.session(
        IndexedGroupCommitFaultTest.context(
            manager, table, null, new IndexedVacuum(manager, table)), Long.BYTES);
    borrowed[borrowedCount++] = result;
    return result;
  }

  void close() {
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
    if (flush.isOk()) table.close();
    StatusCode walStatus = wal.close();
    if (!flush.isOk() || !(walStatus.isOk() || walStatus == StatusCode.CLOSED)) {
      faults.directory.crash();
    }
  }

  void assertTerminalFailure() {
    assertEquals(TransactionState.INDETERMINATE, first.transaction().state());
    assertEquals(TransactionState.INDETERMINATE, second.transaction().state());
    assertEquals(TransactionState.INDETERMINATE, firstOutcome.state());
    assertEquals(TransactionState.INDETERMINATE, secondOutcome.state());
    assertEquals(0, manager.activeTransactionCount());
    assertEquals(0, manager.activeLockCount());
    assertEquals(0, manager.waitingLockCount());
    assertEquals(StatusCode.FENCED, store.admission());
    assertEquals(StatusCode.FENCED, table.flush());
    assertEquals(StatusCode.FENCED, wal.reserve(
        1, new io.riverdb.wal.local.LocalWalReservation()));
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
  }

  static DurableFile walFile(LocalWal wal) {
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

  static void replaceWalFile(LocalWal wal, DurableFile file) {
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

  private static void closeSession(IndexedTransactionSession session) {
    if (session.transactionLifecycleActive()) session.abort(new TransactionOutcome());
    session.close();
  }

  static final class HeldForce implements DurableFile {
    private final DurableFile delegate;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean fail;
    private boolean first = true;

    HeldForce(DurableFile file) { delegate = file; }

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

    void awaitEntered() throws InterruptedException {
      assertTrue(entered.await(5, TimeUnit.SECONDS), "group force did not start");
    }

    void release() { release.countDown(); }
    void fail() { fail = true; }

    @Override public StatusCode force(ForceMode mode) {
      return force(() -> delegate.force(mode));
    }

    @Override public StatusCode force(long startInclusive, long endExclusive, ForceMode mode) {
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

  @FunctionalInterface
  private interface ForceOperation {
    StatusCode force();
  }
}
