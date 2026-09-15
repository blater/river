package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.wal.WalCommitGroupCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileIoMode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalWalForceTargetTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(401, 409);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void capturesBeforeIoAndRetainsExclusiveCoverageUntilRelease(@TempDir Path root) {
    Fixture fixture = new Fixture(root);
    LocalWal wal = fixture.wal;
    LocalWalAppendResult first = append(wal, 1);
    LocalWalAppendResult last = append(wal, 2);
    LocalWalForceTarget target = new LocalWalForceTarget();
    fixture.file.duringForce = () -> {
      assertEquals(first.startOffset(), target.startOffset());
      assertEquals(
          last.endOffset() + WalCommitGroupCodec.FOOTER_BYTES, target.endOffset());
      assertEquals(2, target.recordCount());
      assertEquals(2, target.commitSequence());
      assertEquals(GENERATION, target.generation());
      assertFalse(target.locallyForced());
      assertFalse(target.durabilityComplete());
      assertEquals(first.startOffset(), wal.durableEnd());
      assertEquals(StatusCode.CONFLICT, target.reset());
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, wal.reserve(0, new LocalWalReservation()));
      assertEquals(StatusCode.CONFLICT, wal.forcePending(new LocalWalForceTarget()));
      assertEquals(StatusCode.CONFLICT, wal.releaseForcedBatch(target, target.token()));
      assertEquals(StatusCode.CONFLICT,
          wal.openForcedCursor(target, target.token(), new LocalWalForcedCursor()));
      assertEquals(StatusCode.CONFLICT, wal.completeRecovery());
      assertEquals(StatusCode.CONFLICT, wal.close());
    };
    assertEquals(StatusCode.OK, wal.forcePending(target));
    assertTrue(target.locallyForced());
    assertTrue(target.durabilityComplete());
    assertEquals(
        last.endOffset() + WalCommitGroupCodec.FOOTER_BYTES, wal.durableEnd());
    assertEquals(2, wal.currentCommitSequence());
    LocalWalForcedCursor cursor = new LocalWalForcedCursor();
    assertEquals(StatusCode.OK, wal.openForcedCursor(target, target.token(), cursor));
    LocalWalReadResult read = new LocalWalReadResult();
    assertEquals(StatusCode.OK, cursor.next(read));
    assertEquals(1, read.header().commitSequence());
    assertEquals(StatusCode.OK, cursor.next(read));
    assertEquals(2, read.header().commitSequence());
    assertEquals(
        last.endOffset() + WalCommitGroupCodec.FOOTER_BYTES, read.nextOffset());
    assertEquals(StatusCode.OK, wal.releaseForcedBatch(target, target.token()));
    assertEquals(StatusCode.CONFLICT, wal.releaseForcedBatch(target, target.token()));
    assertEquals(StatusCode.OK, target.reset());
    fixture.close();
  }

  @Test
  void failedForceRetainsIdentityWithoutClaimingDurability(@TempDir Path root) {
    Fixture fixture = new Fixture(root);
    LocalWalAppendResult append = append(fixture.wal, 1);
    LocalWalForceTarget target = new LocalWalForceTarget();
    fixture.file.forceStatus = StatusCode.IO_FAILURE;
    assertEquals(StatusCode.IO_FAILURE, fixture.wal.forcePending(target));
    assertEquals(
        append.endOffset() + WalCommitGroupCodec.FOOTER_BYTES, target.endOffset());
    assertFalse(target.locallyForced());
    assertFalse(target.durabilityComplete());
    assertEquals(append.startOffset(), fixture.wal.durableEnd());
    assertEquals(0, fixture.wal.currentCommitSequence());
    assertEquals(StatusCode.CONFLICT, target.reset());
    assertEquals(StatusCode.FENCED, fixture.wal.releaseForcedBatch(target, target.token()));
    assertEquals(StatusCode.FENCED, fixture.wal.reserve(0, new LocalWalReservation()));
    fixture.close();
    assertEquals(StatusCode.OK, target.reset());
  }

  @Test
  void successiveForcesSubmitOnlyTheirPendingIntervals(@TempDir Path root) {
    Fixture fixture = new Fixture(root);
    LocalWalAppendResult first = append(fixture.wal, 1);
    LocalWalForceTarget target = new LocalWalForceTarget();
    assertEquals(StatusCode.OK, fixture.wal.forcePending(target));
    assertEquals(first.startOffset(), fixture.file.lastForceStart);
    assertEquals(
        first.endOffset() + WalCommitGroupCodec.FOOTER_BYTES,
        fixture.file.lastForceEnd);
    assertEquals(StatusCode.OK, fixture.wal.releaseForcedBatch(target, target.token()));

    LocalWalAppendResult second = append(fixture.wal, 2);
    assertEquals(StatusCode.OK, fixture.wal.forcePending(target));
    assertEquals(
        first.endOffset() + WalCommitGroupCodec.FOOTER_BYTES,
        fixture.file.lastForceStart);
    assertEquals(
        second.endOffset() + WalCommitGroupCodec.FOOTER_BYTES,
        fixture.file.lastForceEnd);
    assertEquals(StatusCode.OK, fixture.wal.releaseForcedBatch(target, target.token()));
    fixture.close();
  }

  @Test
  void fencingDuringIoCannotCloseTheFileOrTurnLocalSuccessIntoAcknowledgement(
      @TempDir Path root) {
    Fixture fixture = new Fixture(root);
    LocalWalAppendResult append = append(fixture.wal, 1);
    LocalWalForceTarget target = new LocalWalForceTarget();
    fixture.file.duringForce = () -> {
      assertEquals(StatusCode.OK, fixture.wal.fencePendingBatch());
      assertEquals(StatusCode.CONFLICT, fixture.wal.close());
      assertEquals(0, fixture.file.closes);
      assertEquals(StatusCode.CONFLICT, target.reset());
    };
    assertEquals(StatusCode.FENCED, fixture.wal.forcePending(target));
    assertTrue(target.locallyForced());
    assertFalse(target.durabilityComplete());
    assertEquals(
        append.endOffset() + WalCommitGroupCodec.FOOTER_BYTES, fixture.wal.durableEnd());
    assertEquals(1, fixture.wal.currentCommitSequence());
    assertEquals(StatusCode.FENCED, fixture.wal.releaseForcedBatch(target, target.token()));
    fixture.close();
    assertEquals(1, fixture.file.closes);
    assertEquals(StatusCode.OK, target.reset());
  }

  @Test
  void unexpectedProviderFailureUnwindsForceOwnershipAndAllowsFencedClose(@TempDir Path root) {
    Fixture fixture = new Fixture(root);
    append(fixture.wal, 1);
    LocalWalForceTarget target = new LocalWalForceTarget();
    fixture.file.duringForce = () -> { throw new IllegalStateException("injected provider bug"); };
    assertThrows(IllegalStateException.class, () -> fixture.wal.forcePending(target));
    assertFalse(target.locallyForced());
    assertFalse(target.durabilityComplete());
    assertEquals(StatusCode.CONFLICT, target.reset());
    assertEquals(StatusCode.FENCED, fixture.wal.reserve(0, new LocalWalReservation()));
    fixture.close();
    assertEquals(1, fixture.file.closes);
    assertEquals(StatusCode.OK, target.reset());
  }

  @Test
  void staleTokensAndCursorsCannotConsumeAReusedOrRotatedTarget(@TempDir Path root) {
    Fixture fixture = new Fixture(root);
    LocalWal wal = fixture.wal;
    LocalWalForceTarget target = new LocalWalForceTarget();
    append(wal, 1);
    assertEquals(StatusCode.OK, wal.forcePending(target));
    long firstToken = target.token();
    LocalWalForcedCursor stale = new LocalWalForcedCursor();
    assertEquals(StatusCode.OK, wal.openForcedCursor(target, firstToken, stale));
    assertEquals(StatusCode.OK, wal.releaseForcedBatch(target, firstToken));
    assertEquals(StatusCode.CONFLICT, stale.next(new LocalWalReadResult()));
    append(wal, 2);
    assertEquals(StatusCode.OK, wal.forcePending(target));
    long secondToken = target.token();
    assertTrue(secondToken > firstToken);
    assertEquals(StatusCode.CONFLICT, wal.releaseForcedBatch(target, firstToken));
    assertEquals(StatusCode.CONFLICT,
        wal.openForcedCursor(target, firstToken, new LocalWalForcedCursor()));
    assertEquals(StatusCode.CONFLICT, stale.next(new LocalWalReadResult()));
    assertEquals(StatusCode.OK, wal.releaseForcedBatch(target, secondToken));
    WalGeneration next = WalGeneration.of(2);
    assertEquals(StatusCode.OK, wal.rotate(
        fixture.directory, LocalWal.generationFileName(next), next, 3));
    append(wal, 3);
    assertEquals(StatusCode.OK, wal.forcePending(target));
    assertEquals(next, target.generation());
    assertTrue(target.token() > secondToken);
    assertEquals(StatusCode.CONFLICT, wal.releaseForcedBatch(target, secondToken));
    assertEquals(StatusCode.CONFLICT, stale.next(new LocalWalReadResult()));
    assertEquals(StatusCode.OK, wal.releaseForcedBatch(target, target.token()));
    fixture.close();
  }

  @Test
  void foreignProviderCannotCaptureOrReleaseAnOwnedTarget(@TempDir Path root) throws Exception {
    Fixture first = new Fixture(Files.createDirectory(root.resolve("first")));
    Fixture second = new Fixture(Files.createDirectory(root.resolve("second")));
    append(first.wal, 1);
    append(second.wal, 1);
    LocalWalForceTarget target = new LocalWalForceTarget();
    assertEquals(StatusCode.OK, first.wal.forcePending(target));
    long token = target.token();
    assertEquals(StatusCode.CONFLICT, second.wal.forcePending(target));
    assertEquals(0, second.file.forces);
    assertEquals(StatusCode.CONFLICT, second.wal.releaseForcedBatch(target, token));
    assertEquals(StatusCode.CONFLICT,
        second.wal.openForcedCursor(target, token, new LocalWalForcedCursor()));
    assertEquals(StatusCode.OK, first.wal.releaseForcedBatch(target, token));
    assertEquals(StatusCode.OK, second.wal.forcePending(target));
    assertEquals(StatusCode.CONFLICT, first.wal.releaseForcedBatch(target, token));
    assertEquals(StatusCode.OK, second.wal.releaseForcedBatch(target, target.token()));
    first.close();
    second.close();
  }

  @Test
  void exhaustedIdentitySpaceRefusesForceWithoutWrappingOrDoingIo(@TempDir Path root)
      throws Exception {
    Fixture fixture = new Fixture(root);
    var tokens = LocalWal.class.getDeclaredField("nextForceToken");
    tokens.setAccessible(true);
    tokens.setLong(fixture.wal, Long.MAX_VALUE);
    LocalWalForceTarget target = new LocalWalForceTarget();
    append(fixture.wal, 1);
    assertEquals(StatusCode.OK, fixture.wal.forcePending(target));
    assertEquals(Long.MAX_VALUE, target.token());
    assertEquals(StatusCode.OK, fixture.wal.releaseForcedBatch(target, target.token()));
    append(fixture.wal, 2);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, fixture.wal.forcePending(target));
    assertEquals(1, fixture.file.forces);
    assertEquals(1, fixture.wal.currentCommitSequence());
    assertEquals(StatusCode.OK, fixture.wal.fencePendingBatch());
    fixture.close();
  }

  @Test
  void heldAsyncForceAllowsASealedSuccessorWithoutAdvancingItsDurability(
      @TempDir Path root) throws Exception {
    Fixture fixture = new Fixture(root);
    CountDownLatch forceEntered = new CountDownLatch(1);
    CountDownLatch releaseForce = new CountDownLatch(1);
    fixture.file.duringForce = () -> {
      forceEntered.countDown();
      await(releaseForce);
    };
    assertEquals(StatusCode.OK, fixture.wal.enableForceWorker(Thread.currentThread()));

    LocalWalAppendResult first = append(fixture.wal, 1);
    assertEquals(StatusCode.OK, fixture.wal.sealPendingBatch());
    LocalWalForceTarget target = new LocalWalForceTarget();
    assertEquals(StatusCode.OK,
        fixture.wal.submitSealedForce(target, LocalWalForceCause.SHARED_GROUP));
    assertTrue(forceEntered.await(5, TimeUnit.SECONDS));

    LocalWalAppendResult second;
    try {
      second = append(fixture.wal, 2);
      assertEquals(StatusCode.OK, fixture.wal.sealPendingBatch());
      assertEquals(first.startOffset(), fixture.wal.durableEnd());
      assertEquals(
          second.endOffset() + WalCommitGroupCodec.FOOTER_BYTES,
          fixture.wal.tailEnd());
    } finally {
      releaseForce.countDown();
    }
    awaitForceResult(fixture.wal, target);
    long firstToken = target.token();
    assertEquals(StatusCode.OK, fixture.wal.completeSubmittedForce(target, firstToken));
    assertEquals(target.endOffset(), fixture.wal.durableEnd());
    assertTrue(fixture.wal.tailEnd() > fixture.wal.durableEnd());
    assertEquals(StatusCode.OK, fixture.wal.releaseForcedBatch(target, firstToken));

    fixture.file.duringForce = null;
    assertEquals(StatusCode.OK,
        fixture.wal.submitSealedForce(target, LocalWalForceCause.SHARED_GROUP));
    awaitForceResult(fixture.wal, target);
    assertEquals(StatusCode.OK,
        fixture.wal.completeSubmittedForce(target, target.token()));
    assertEquals(fixture.wal.tailEnd(), fixture.wal.durableEnd());
    assertEquals(2, fixture.wal.currentCommitSequence());
    assertEquals(StatusCode.OK,
        fixture.wal.releaseForcedBatch(target, target.token()));
    fixture.close();
  }

  @Test
  void oneAsyncForceCoversSeveralSealedFooterBearingGroups(@TempDir Path root) {
    Fixture fixture = new Fixture(root);
    assertEquals(StatusCode.OK, fixture.wal.enableForceWorker(Thread.currentThread()));
    LocalWalAppendResult first = append(fixture.wal, 1);
    assertEquals(StatusCode.OK, fixture.wal.sealPendingBatch());
    append(fixture.wal, 2);
    assertEquals(StatusCode.OK, fixture.wal.sealPendingBatch());
    LocalWalAppendResult third = append(fixture.wal, 3);
    assertEquals(StatusCode.OK, fixture.wal.sealPendingBatch());

    LocalWalForceTarget target = new LocalWalForceTarget();
    assertEquals(StatusCode.OK,
        fixture.wal.submitSealedForce(target, LocalWalForceCause.SHARED_GROUP));
    awaitForceResult(fixture.wal, target);
    assertEquals(3, target.recordCount());
    assertEquals(first.startOffset(), target.startOffset());
    assertEquals(
        third.endOffset() + WalCommitGroupCodec.FOOTER_BYTES,
        target.endOffset());
    assertEquals(StatusCode.OK,
        fixture.wal.completeSubmittedForce(target, target.token()));

    LocalWalForcedCursor cursor = new LocalWalForcedCursor();
    assertEquals(StatusCode.OK,
        fixture.wal.openForcedCursor(target, target.token(), cursor));
    LocalWalReadResult read = new LocalWalReadResult();
    for (int sequence = 1; sequence <= 3; sequence++) {
      assertEquals(StatusCode.OK, cursor.next(read));
      assertEquals(sequence, read.header().commitSequence());
    }
    assertEquals(StatusCode.OK,
        fixture.wal.releaseForcedBatch(target, target.token()));
    fixture.close();
  }

  @Test
  void asyncSubmitRejectsOpenReservationAndUnsealedRecords(
      @TempDir Path root) {
    Fixture fixture = new Fixture(root);
    assertEquals(StatusCode.OK, fixture.wal.enableForceWorker(Thread.currentThread()));
    append(fixture.wal, 1);
    assertEquals(StatusCode.OK, fixture.wal.sealPendingBatch());
    LocalWalForceTarget target = new LocalWalForceTarget();

    LocalWalReservation reservation = new LocalWalReservation();
    assertEquals(StatusCode.OK, fixture.wal.reserve(0, reservation));
    assertEquals(StatusCode.CONFLICT,
        fixture.wal.submitSealedForce(target, LocalWalForceCause.SHARED_GROUP));
    assertEquals(StatusCode.OK, fixture.wal.cancel(reservation));

    append(fixture.wal, 2);
    assertEquals(StatusCode.CONFLICT,
        fixture.wal.submitSealedForce(target, LocalWalForceCause.SHARED_GROUP));
    assertEquals(StatusCode.OK, fixture.wal.sealPendingBatch());
    assertEquals(StatusCode.OK,
        fixture.wal.submitSealedForce(target, LocalWalForceCause.SHARED_GROUP));
    awaitForceResult(fixture.wal, target);
    assertEquals(StatusCode.OK,
        fixture.wal.completeSubmittedForce(target, target.token()));
    assertEquals(StatusCode.OK,
        fixture.wal.releaseForcedBatch(target, target.token()));
    fixture.close();
  }

  @Test
  void asyncLocalSuccessCannotAcknowledgeAfterReentrantFence(@TempDir Path root)
      throws Exception {
    Fixture fixture = new Fixture(root);
    CountDownLatch forceEntered = new CountDownLatch(1);
    CountDownLatch releaseForce = new CountDownLatch(1);
    fixture.file.duringForce = () -> {
      forceEntered.countDown();
      await(releaseForce);
    };
    assertEquals(StatusCode.OK, fixture.wal.enableForceWorker(Thread.currentThread()));
    LocalWalAppendResult first = append(fixture.wal, 1);
    assertEquals(StatusCode.OK, fixture.wal.sealPendingBatch());
    LocalWalForceTarget target = new LocalWalForceTarget();
    assertEquals(StatusCode.OK,
        fixture.wal.submitSealedForce(target, LocalWalForceCause.SHARED_GROUP));
    assertTrue(forceEntered.await(5, TimeUnit.SECONDS));
    try {
      append(fixture.wal, 2);
      assertEquals(StatusCode.OK, fixture.wal.sealPendingBatch());
      assertEquals(StatusCode.OK, fixture.wal.fencePendingBatch());
    } finally {
      releaseForce.countDown();
    }
    awaitForceResult(fixture.wal, target);

    assertEquals(StatusCode.FENCED,
        fixture.wal.completeSubmittedForce(target, target.token()));
    assertTrue(target.locallyForced());
    assertFalse(target.durabilityComplete());
    assertEquals(target.endOffset(), fixture.wal.durableEnd());
    assertEquals(first.endOffset() + WalCommitGroupCodec.FOOTER_BYTES, target.endOffset());
    fixture.close();
    assertEquals(StatusCode.OK, target.reset());
  }

  @Test
  void interruptedFencedCloseJoinsOutstandingForceBeforeClosingProvider(
      @TempDir Path root) throws Exception {
    Fixture fixture = new Fixture(root);
    CountDownLatch forceEntered = new CountDownLatch(1);
    CountDownLatch releaseForce = new CountDownLatch(1);
    fixture.file.duringForce = () -> {
      forceEntered.countDown();
      await(releaseForce);
    };
    assertEquals(StatusCode.OK, fixture.wal.enableForceWorker(Thread.currentThread()));
    append(fixture.wal, 1);
    assertEquals(StatusCode.OK, fixture.wal.sealPendingBatch());
    LocalWalForceTarget target = new LocalWalForceTarget();
    assertEquals(StatusCode.OK,
        fixture.wal.submitSealedForce(target, LocalWalForceCause.SHARED_GROUP));
    assertTrue(forceEntered.await(5, TimeUnit.SECONDS));
    append(fixture.wal, 2);
    assertEquals(StatusCode.OK, fixture.wal.fencePendingBatch());

    AtomicReference<StatusCode> closeStatus = new AtomicReference<>();
    AtomicBoolean interruptedAfterClose = new AtomicBoolean();
    CountDownLatch closeStarted = new CountDownLatch(1);
    Thread closer = Thread.ofPlatform().start(() -> {
      closeStarted.countDown();
      closeStatus.set(fixture.wal.close());
      interruptedAfterClose.set(Thread.currentThread().isInterrupted());
    });
    try {
      assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
      closer.interrupt();
      closer.join(100);
      assertTrue(closer.isAlive());
      assertEquals(0, fixture.file.closes);
    } finally {
      releaseForce.countDown();
    }
    closer.join(5_000);
    assertFalse(closer.isAlive());
    assertEquals(StatusCode.OK, closeStatus.get());
    assertTrue(interruptedAfterClose.get());
    assertEquals(1, fixture.file.closes);
    assertEquals(StatusCode.OK, target.reset());
    assertEquals(StatusCode.OK, fixture.directory.close());
  }

  @Test
  void partialFooterWriteFencesExplicitSealAndImplicitForce(@TempDir Path root)
      throws Exception {
    Fixture explicit = new Fixture(Files.createDirectory(root.resolve("explicit")));
    append(explicit.wal, 1);
    explicit.file.partialNextWrite = true;
    assertEquals(StatusCode.IO_FAILURE, explicit.wal.sealPendingBatch());
    assertEquals(StatusCode.FENCED,
        explicit.wal.reserve(Long.BYTES, new LocalWalReservation()));
    explicit.close();

    Fixture implicit = new Fixture(Files.createDirectory(root.resolve("implicit")));
    append(implicit.wal, 1);
    implicit.file.partialNextWrite = true;
    assertEquals(StatusCode.IO_FAILURE,
        implicit.wal.forcePending(new LocalWalForceTarget()));
    assertEquals(StatusCode.FENCED,
        implicit.wal.reserve(Long.BYTES, new LocalWalReservation()));
    implicit.close();
  }

  @Test
  void capturedTargetRequiresExactDurableStartButAcceptsZeroTerminalDigest(
      @TempDir Path root) throws Exception {
    Fixture gap = new Fixture(Files.createDirectory(root.resolve("gap")));
    assertEquals(StatusCode.OK, gap.wal.enableForceWorker(Thread.currentThread()));
    append(gap.wal, 1);
    assertEquals(StatusCode.OK, gap.wal.sealPendingBatch());
    LocalWalForceTarget gapTarget = new LocalWalForceTarget();
    assertEquals(StatusCode.OK,
        gap.wal.submitSealedForce(gapTarget, LocalWalForceCause.SHARED_GROUP));
    awaitForceResult(gap.wal, gapTarget);
    setLong(gapTarget, "startOffset", gapTarget.startOffset() + 1);
    assertEquals(StatusCode.INVARIANT_BROKEN,
        gap.wal.completeSubmittedForce(gapTarget, gapTarget.token()));
    gap.close();

    Fixture zero = new Fixture(Files.createDirectory(root.resolve("zero")));
    assertEquals(StatusCode.OK, zero.wal.enableForceWorker(Thread.currentThread()));
    append(zero.wal, 1);
    assertEquals(StatusCode.OK, zero.wal.sealPendingBatch());
    LocalWalForceTarget zeroTarget = new LocalWalForceTarget();
    assertEquals(StatusCode.OK,
        zero.wal.submitSealedForce(zeroTarget, LocalWalForceCause.SHARED_GROUP));
    awaitForceResult(zero.wal, zeroTarget);
    setInt(zeroTarget, "finalDigest", 0);
    assertEquals(StatusCode.OK,
        zero.wal.completeSubmittedForce(zeroTarget, zeroTarget.token()));
    assertTrue(zeroTarget.durabilityComplete());
    assertEquals(StatusCode.OK,
        zero.wal.releaseForcedBatch(zeroTarget, zeroTarget.token()));
    zero.close();
  }

  private static void awaitForceResult(LocalWal wal, LocalWalForceTarget target) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!wal.submittedForceComplete(target) && System.nanoTime() < deadline) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
    assertTrue(wal.submittedForceComplete(target));
  }

  private static void await(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException wakeup) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static void setLong(Object target, String name, long value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.setLong(target, value);
  }

  private static void setInt(Object target, String name, int value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.setInt(target, value);
  }

  private static LocalWalAppendResult append(LocalWal wal, long csn) {
    LocalWalReservation reservation = new LocalWalReservation();
    assertEquals(StatusCode.OK, wal.reserve(Long.BYTES, reservation));
    reservation.writablePayload().putLong(csn);
    LocalWalAppendResult append = new LocalWalAppendResult();
    assertEquals(StatusCode.OK, wal.appendUnforced(reservation, csn, csn, 1, 7, 1, append));
    return append;
  }

  private static final class Fixture {
    final NioDurableDirectory directory;
    final ObservedFile file;
    final LocalWal wal;

    Fixture(Path root) {
      NioDirectoryOpenResult opened = new NioDirectoryOpenResult();
      assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
          root, new FatalStateFence(), new NioIoCounters(), 4, opened));
      directory = opened.directory();
      LocalWalOpenResult created = new LocalWalOpenResult();
      assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, created));
      assertEquals(StatusCode.OK, created.wal().close());
      DirectoryOperationResult operation = new DirectoryOperationResult();
      assertEquals(StatusCode.OK, directory.reopen(LocalWal.FILE_NAME, FileIoMode.MAPPED, operation));
      file = new ObservedFile(operation.file());
      wal = new LocalWal(file, DATABASE, GENERATION, LocalWal.FILE_NAME);
      assertEquals(StatusCode.OK, wal.recoverValidTailForOpen());
      // Open-time tail repair is maintenance I/O, not part of the force under test.
      file.forces = 0;
    }

    void close() {
      assertEquals(StatusCode.OK, wal.close());
      assertEquals(StatusCode.OK, directory.close());
    }
  }

  private static final class ObservedFile implements DurableFile {
    private final DurableFile delegate;
    Runnable duringForce;
    StatusCode forceStatus = StatusCode.OK;
    int forces;
    int closes;
    long lastForceStart;
    long lastForceEnd;
    boolean partialNextWrite;

    ObservedFile(DurableFile file) { delegate = file; }
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      return delegate.read(position, target, result);
    }
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      if (partialNextWrite) {
        partialNextWrite = false;
        ByteBuffer firstByte = source.slice();
        firstByte.limit(1);
        StatusCode status = delegate.write(position, firstByte, result);
        return status.isOk() ? StatusCode.IO_FAILURE : status;
      }
      return delegate.write(position, source, result);
    }
    public StatusCode force(ForceMode mode) {
      forces++;
      lastForceStart = 0;
      lastForceEnd = Long.MAX_VALUE;
      if (duringForce != null && forces == 1) duringForce.run();
      return forceStatus.isOk() ? delegate.force(mode) : forceStatus;
    }
    @Override
    public StatusCode force(long startInclusive, long endExclusive, ForceMode mode) {
      forces++;
      lastForceStart = startInclusive;
      lastForceEnd = endExclusive;
      if (duringForce != null && forces == 1) duringForce.run();
      return forceStatus.isOk()
          ? delegate.force(startInclusive, endExclusive, mode) : forceStatus;
    }
    public StatusCode truncate(long size) { return delegate.truncate(size); }
    public StatusCode size(FileSizeResult result) { return delegate.size(result); }
    public StatusCode close() { closes++; return delegate.close(); }
  }
}
