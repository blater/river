package io.riverdb.wal.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.DirectoryOperationResult;
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
      assertEquals(last.endOffset(), target.endOffset());
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
    assertEquals(last.endOffset(), wal.durableEnd());
    assertEquals(2, wal.currentCommitSequence());
    LocalWalForcedCursor cursor = new LocalWalForcedCursor();
    assertEquals(StatusCode.OK, wal.openForcedCursor(target, target.token(), cursor));
    LocalWalReadResult read = new LocalWalReadResult();
    assertEquals(StatusCode.OK, cursor.next(read));
    assertEquals(1, read.header().commitSequence());
    assertEquals(StatusCode.OK, cursor.next(read));
    assertEquals(2, read.header().commitSequence());
    assertEquals(last.endOffset(), read.nextOffset());
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
    assertEquals(append.endOffset(), target.endOffset());
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
    assertEquals(append.endOffset(), fixture.wal.durableEnd());
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
      assertEquals(StatusCode.OK, directory.reopen(LocalWal.FILE_NAME, operation));
      file = new ObservedFile(operation.file());
      wal = new LocalWal(file, DATABASE, GENERATION, LocalWal.FILE_NAME);
      assertEquals(StatusCode.OK, wal.recoverValidTailForOpen());
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

    ObservedFile(DurableFile file) { delegate = file; }
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      return delegate.read(position, target, result);
    }
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      return delegate.write(position, source, result);
    }
    public StatusCode force(ForceMode mode) {
      forces++;
      if (duringForce != null) duringForce.run();
      return forceStatus.isOk() ? delegate.force(mode) : forceStatus;
    }
    public StatusCode truncate(long size) { return delegate.truncate(size); }
    public StatusCode size(FileSizeResult result) { return delegate.size(result); }
    public StatusCode close() { closes++; return delegate.close(); }
  }
}
