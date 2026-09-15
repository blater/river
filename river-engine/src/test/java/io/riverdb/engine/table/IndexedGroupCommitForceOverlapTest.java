package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.context;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.openDirectory;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.openWal;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.pageSet;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedGroupCommitForceOverlapTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(919, 929);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int KEY = 41;

  @Test
  void publishesThreeSamePageGenerationsAndReleasesOnlyForcedPrefixes(
      @TempDir Path root) throws Exception {
    Fixture fixture = new Fixture(root, -1);
    ExecutorService executor = Executors.newFixedThreadPool(3);
    PendingCommit first = null;
    PendingCommit second = null;
    PendingCommit third = null;
    try {
      long baseSequence = fixture.commitSequence();
      IndexedTransactionSession firstSession = fixture.begin();
      IndexedTransactionSession secondSession = fixture.begin();
      IndexedTransactionSession thirdSession = fixture.begin();
      PreparedCommit firstPrepared = fixture.prepare(firstSession, KEY, 101);
      PreparedCommit secondPrepared = fixture.prepare(secondSession, KEY + 1, 102);
      PreparedCommit thirdPrepared = fixture.prepare(thirdSession, KEY + 2, 103);
      first = fixture.submit(executor, firstPrepared);
      fixture.selection.awaitEntered(0);
      second = fixture.submit(executor, secondPrepared);
      fixture.selection.release(0);
      fixture.force.awaitEntered(0);
      fixture.awaitPublished(baseSequence + 1, first);
      IndexedPageFrame firstFrame = fixture.currentLeaf();

      fixture.selection.awaitEntered(1);
      third = fixture.submit(executor, thirdPrepared);
      fixture.selection.release(1);
      fixture.awaitPublished(baseSequence + 2, second);
      IndexedPageFrame secondFrame = fixture.currentLeaf();
      fixture.selection.awaitEntered(2);
      fixture.selection.release(2);
      fixture.awaitPublished(baseSequence + 3, third);
      IndexedPageFrame thirdFrame = fixture.currentLeaf();

      assertNotSame(firstFrame, secondFrame);
      assertNotSame(secondFrame, thirdFrame);
      assertNotEquals(firstFrame.pageGeneration, secondFrame.pageGeneration);
      assertNotEquals(secondFrame.pageGeneration, thirdFrame.pageGeneration);
      fixture.assertGenerationChain(firstFrame, secondFrame, thirdFrame);
      fixture.assertPins(1, firstFrame, secondFrame, thirdFrame);
      assertPending(first, second, third);

      fixture.force.release(0);
      fixture.force.awaitEntered(1);
      awaitDone(first.future, "first covered commit did not complete");
      assertEquals(StatusCode.OK, first.future.get(5, TimeUnit.SECONDS));
      assertEquals(TransactionState.COMMITTED, first.outcome.state());
      assertFalse(second.future.isDone(), "second commit completed before its force target");
      assertFalse(third.future.isDone(), "third commit completed before its force target");
      fixture.assertPins(0, firstFrame);
      fixture.assertPins(1, secondFrame, thirdFrame);

      fixture.force.release(1);
      assertEquals(StatusCode.OK, second.future.get(5, TimeUnit.SECONDS));
      assertEquals(StatusCode.OK, third.future.get(5, TimeUnit.SECONDS));
      assertEquals(baseSequence + 1, first.outcome.commitSequence());
      assertEquals(baseSequence + 2, second.outcome.commitSequence());
      assertEquals(baseSequence + 3, third.outcome.commitSequence());
      assertEquals(TransactionState.COMMITTED, second.outcome.state());
      assertEquals(TransactionState.COMMITTED, third.outcome.state());
      fixture.assertPins(0, firstFrame, secondFrame, thirdFrame);
      IndexedGroupCommitTelemetry telemetry = new IndexedGroupCommitTelemetry();
      assertEquals(StatusCode.OK, fixture.coordinator.copyTelemetry(telemetry));
      assertEquals(2, telemetry.physicalCohortsWhileForceActive());
      fixture.success = true;
    } finally {
      fixture.force.releaseAll();
      fixture.selection.releaseAll();
      executor.shutdownNow();
      fixture.close(first, second, third);
    }
  }

  @Test
  void failedPrefixForceFencesAllWaitersAndReleasesEachOwnedGeneration(
      @TempDir Path root) throws Exception {
    Fixture fixture = new Fixture(root, 0);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    PendingCommit first = null;
    PendingCommit successor = null;
    try {
      long baseSequence = fixture.commitSequence();
      IndexedTransactionSession firstSession = fixture.begin();
      IndexedTransactionSession successorSession = fixture.begin();
      PreparedCommit firstPrepared = fixture.prepare(firstSession, KEY, 201);
      PreparedCommit successorPrepared = fixture.prepare(successorSession, KEY + 1, 202);
      first = fixture.submit(executor, firstPrepared);
      fixture.selection.awaitEntered(0);
      successor = fixture.submit(executor, successorPrepared);
      fixture.selection.release(0);
      fixture.force.awaitEntered(0);
      fixture.awaitPublished(baseSequence + 1, first);
      IndexedPageFrame firstFrame = fixture.currentLeaf();

      fixture.selection.awaitEntered(1);
      fixture.selection.release(1);
      fixture.awaitPublished(baseSequence + 2, successor);
      IndexedPageFrame successorFrame = fixture.currentLeaf();
      assertNotSame(firstFrame, successorFrame);
      fixture.assertGenerationChain(firstFrame, successorFrame);
      fixture.assertPins(1, firstFrame, successorFrame);

      fixture.force.release(0);
      assertFalse(first.future.get(5, TimeUnit.SECONDS).isOk());
      assertFalse(successor.future.get(5, TimeUnit.SECONDS).isOk());
      assertEquals(TransactionState.INDETERMINATE, first.outcome.state());
      assertEquals(TransactionState.INDETERMINATE, successor.outcome.state());
      assertEquals(StatusCode.FENCED, fixture.store.admission());
      fixture.assertPins(0, firstFrame, successorFrame);
    } finally {
      fixture.force.releaseAll();
      fixture.selection.releaseAll();
      executor.shutdownNow();
      fixture.close(first, successor);
    }
  }

  @Test
  void idleWriterWakesForEnqueueForceCompletionAndClose(@TempDir Path root)
      throws Exception {
    Fixture fixture = new Fixture(root, -1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    PendingCommit commit = null;
    try {
      await(fixture.coordinator::writerIdle, "commit writer did not enter its idle protocol");
      IndexedTransactionSession session = fixture.begin();
      commit = fixture.submit(executor, fixture.prepare(session, KEY, 301));
      fixture.selection.awaitEntered(0);
      fixture.selection.release(0);
      fixture.force.awaitEntered(0);
      fixture.force.release(0);
      assertEquals(StatusCode.OK, commit.future.get(5, TimeUnit.SECONDS));
      await(fixture.coordinator::writerIdle,
          "commit writer did not return to idle after force completion");
      Future<StatusCode> close = executor.submit(fixture.coordinator::close);
      assertEquals(StatusCode.OK, close.get(5, TimeUnit.SECONDS));
      fixture.success = true;
    } finally {
      fixture.force.releaseAll();
      fixture.selection.releaseAll();
      executor.shutdownNow();
      fixture.close(commit);
    }
  }

  private static void assertPending(PendingCommit... commits) {
    for (PendingCommit commit : commits) {
      assertFalse(commit.future.isDone());
      assertFalse(commit.outcome.isAvailable());
    }
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
    assertTrue(condition.getAsBoolean(), message);
  }

  private static void awaitDone(Future<?> future, String message) throws Exception {
    await(future::isDone, message);
  }

  private static ByteBuffer row(long value) {
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    return row;
  }

  private static IndexedPageFrameCache cache(IndexedTableStore store) throws Exception {
    IndexedPageSet pages = pageSet(store);
    Field field = IndexedPageSet.class.getDeclaredField("cache");
    field.setAccessible(true);
    return (IndexedPageFrameCache) field.get(pages);
  }

  private static void replaceWalFile(LocalWal wal, DurableFile file) throws Exception {
    Field field = LocalWal.class.getDeclaredField("file");
    field.setAccessible(true);
    field.set(wal, file);
  }

  private record PendingCommit(
      IndexedTransactionSession session,
      TransactionOutcome outcome,
      Future<StatusCode> future) {}

  private record PreparedCommit(
      IndexedTransactionSession session,
      TransactionOutcome outcome) {}

  private static final class Fixture {
    private final NioDurableDirectory directory;
    private final LocalWal wal;
    private final IndexedTableStore store;
    private final IndexedTable table;
    private final TransactionManager manager;
    private final IndexedGroupCommitCoordinator coordinator;
    private final IndexedSessionContext context;
    private final IndexedPageFrameCache cache;
    private final HeldForce force;
    private final HeldSelectionBatch selection;
    private boolean success;

    private Fixture(Path root, int failedForce) throws Exception {
      directory = openDirectory(root);
      wal = openWal(directory, false);
      IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
      assertEquals(StatusCode.OK, IndexedTableStore.create(
          directory, wal, DATABASE, GENERATION, databaseProviderLease(8), storeResult));
      store = storeResult.store();
      IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
      assertEquals(StatusCode.OK, IndexedTable.create(store, tableResult));
      table = tableResult.table();
      manager = new TransactionManager(
          DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 8);
      IndexedVacuum vacuum = new IndexedVacuum(manager, table);
      IndexedSessionContext seedContext = context(manager, table, null, vacuum);
      for (int offset = 0; offset < 3; offset++) {
        IndexedTransactionSession seed = session(seedContext, Long.BYTES);
        assertEquals(StatusCode.OK, seed.begin(IsolationLevel.REPEATABLE_READ));
        assertEquals(StatusCode.OK, seed.insert(0, KEY + offset, row(100 + offset)));
        assertEquals(StatusCode.OK, seed.commit(new TransactionOutcome()));
        assertEquals(StatusCode.OK, seed.close());
      }

      force = new HeldForce(walFile(wal), failedForce);
      replaceWalFile(wal, force);
      cache = cache(store);
      selection = new HeldSelectionBatch(manager, table, table.commitMetrics());
      coordinator = new IndexedGroupCommitCoordinator(manager, table, 0, selection);
      context = context(manager, table, coordinator, new IndexedVacuum(manager, table));
    }

    private IndexedTransactionSession begin() {
      IndexedTransactionSession session = session(context, Long.BYTES);
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
      return session;
    }

    private PreparedCommit prepare(
        IndexedTransactionSession session, int key, long value) {
      assertEquals(StatusCode.OK, session.update(0, key, row(value)));
      TransactionOutcome outcome = new TransactionOutcome();
      return new PreparedCommit(session, outcome);
    }

    private PendingCommit submit(ExecutorService executor, PreparedCommit prepared) {
      return new PendingCommit(
          prepared.session(), prepared.outcome(),
          executor.submit(() -> prepared.session().commit(prepared.outcome())));
    }

    private long commitSequence() {
      synchronized (table) {
        return table.currentCommitSequence();
      }
    }

    private void awaitPublished(long sequence, PendingCommit commit) throws Exception {
      await(() -> commitSequence() == sequence || commit.future().isDone(),
          "physical publication did not reach commit sequence " + sequence);
      if (commit.future().isDone()) {
        assertEquals(StatusCode.OK, commit.future().get(5, TimeUnit.SECONDS),
            "commit terminated before physical publication " + sequence);
      }
    }

    private IndexedPageFrame currentLeaf() {
      synchronized (table) {
        IndexedPageFrame frame = cache.currentFrame(IndexedTableKernel.INITIAL_LEAF_PAGE_ID, false);
        assertTrue(frame != null, "published leaf frame was missing");
        return frame;
      }
    }

    private void assertGenerationChain(IndexedPageFrame... oldestToNewest) {
      synchronized (table) {
        for (int index = 1; index < oldestToNewest.length; index++) {
          IndexedPageFrame newer = oldestToNewest[index];
          IndexedPageFrame older = oldestToNewest[index - 1];
          assertTrue(newer.previousVersionSlot >= 0);
          assertEquals(older, cache.currentFrames[newer.previousVersionSlot]);
          assertTrue(older.nextVersionSlot >= 0);
          assertEquals(newer, cache.currentFrames[older.nextVersionSlot]);
        }
      }
    }

    private void assertPins(int expected, IndexedPageFrame... frames) {
      synchronized (table) {
        for (IndexedPageFrame frame : frames) assertEquals(expected, frame.pinCount);
      }
    }

    private void close(PendingCommit... commits) {
      StatusCode coordinatorStatus = coordinator.close();
      if (success) assertTrue(coordinatorStatus.isOk() || coordinatorStatus == StatusCode.CLOSED);
      for (PendingCommit commit : commits) {
        if (commit != null) {
          StatusCode status = commit.session.close();
          if (success) assertEquals(StatusCode.OK, status);
        }
      }
      if (success) {
        assertEquals(StatusCode.OK, table.flush());
        assertEquals(StatusCode.OK, table.close());
      } else {
        store.closeOpenFile();
      }
      StatusCode walStatus = wal.close();
      if (success) assertTrue(walStatus.isOk() || walStatus == StatusCode.CLOSED);
      StatusCode directoryStatus = directory.close();
      if (success) assertTrue(directoryStatus.isOk() || directoryStatus == StatusCode.CLOSED);
    }
  }

  private static final class HeldSelectionBatch extends IndexedGroupCommitBatch {
    private static final int SLOTS = 3;
    private final CountDownLatch[] entered = new CountDownLatch[SLOTS];
    private final CountDownLatch[] released = new CountDownLatch[SLOTS];
    private int selections;

    private HeldSelectionBatch(
        TransactionManager manager, IndexedTable table, IndexedGroupCommitMetrics metrics) {
      super(manager, table, metrics);
      for (int index = 0; index < SLOTS; index++) {
        entered[index] = new CountDownLatch(1);
        released[index] = new CountDownLatch(1);
      }
    }

    @Override
    void process(int count, IndexedDurabilityCohortRing pending) {
      int selection = selections++;
      if (selection < SLOTS) {
        entered[selection].countDown();
        try {
          assertTrue(released[selection].await(15, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
      super.process(count, pending);
    }

    private void awaitEntered(int selection) throws InterruptedException {
      assertTrue(entered[selection].await(5, TimeUnit.SECONDS),
          "writer selection " + selection + " did not start");
    }

    private void release(int selection) { released[selection].countDown(); }

    private void releaseAll() {
      for (CountDownLatch gate : released) gate.countDown();
    }
  }

  private static DurableFile walFile(LocalWal wal) throws Exception {
    Field field = LocalWal.class.getDeclaredField("file");
    field.setAccessible(true);
    return (DurableFile) field.get(wal);
  }

  /** Holds each real mapped force independently after publication and before provider I/O. */
  private static final class HeldForce implements DurableFile {
    private static final int FORCE_SLOTS = 3;
    private final DurableFile delegate;
    private final int failedForce;
    private final CountDownLatch[] entered = new CountDownLatch[FORCE_SLOTS];
    private final CountDownLatch[] released = new CountDownLatch[FORCE_SLOTS];
    private int forceCount;

    private HeldForce(DurableFile file, int failedForceIndex) {
      delegate = file;
      failedForce = failedForceIndex;
      for (int index = 0; index < FORCE_SLOTS; index++) {
        entered[index] = new CountDownLatch(1);
        released[index] = new CountDownLatch(1);
      }
    }

    private synchronized int nextForce() {
      return forceCount++;
    }

    private StatusCode force(int index, ForceOperation operation) {
      if (index >= entered.length) return operation.force();
      entered[index].countDown();
      try {
        if (!released[index].await(15, TimeUnit.SECONDS)) return StatusCode.IO_FAILURE;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return StatusCode.CANCELLED;
      }
      return index == failedForce ? StatusCode.IO_FAILURE : operation.force();
    }

    private void awaitEntered(int index) throws InterruptedException {
      assertTrue(entered[index].await(5, TimeUnit.SECONDS),
          "force " + index + " did not start");
    }

    private void release(int index) {
      released[index].countDown();
    }

    private void releaseAll() {
      for (CountDownLatch gate : released) gate.countDown();
    }

    @Override
    public StatusCode force(ForceMode mode) {
      int index = nextForce();
      return force(index, () -> delegate.force(mode));
    }

    @Override
    public StatusCode force(
        long startInclusive, long endExclusive, ForceMode mode) {
      int index = nextForce();
      return force(index, () -> delegate.force(startInclusive, endExclusive, mode));
    }

    @Override
    public StatusCode read(long offset, ByteBuffer target, IoResult result) {
      return delegate.read(offset, target, result);
    }

    @Override
    public StatusCode write(long offset, ByteBuffer source, IoResult result) {
      return delegate.write(offset, source, result);
    }

    @Override
    public StatusCode truncate(long bytes) {
      return delegate.truncate(bytes);
    }

    @Override
    public StatusCode size(FileSizeResult result) {
      return delegate.size(result);
    }

    @Override
    public StatusCode close() {
      return delegate.close();
    }
  }

  @FunctionalInterface
  private interface ForceOperation {
    StatusCode force();
  }
}
