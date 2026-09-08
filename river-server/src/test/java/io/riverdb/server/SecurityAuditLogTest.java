package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.concurrent.MutableCancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.SessionAuthorizationPhase;
import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** Focused checks of the actual audit owner’s force, cancellation, fencing, and recovery paths. */
final class SecurityAuditLogTest {
  private static final AuditFormat FORMAT = new AuditFormat();
  private static final long AUDIT_GENERATION = 7;
  private static final long INSTANCE_HIGH = 11;
  private static final long INSTANCE_LOW = 12;
  private static final long CREDENTIAL_GENERATION = 3;
  private static final long ACTIVE_BYTES = 64L + 108L * 8L;
  private static final long PENDING_BYTES = 256L * 4L;

  @Test
  void twoProducersRetainAnOrderedDurablePrefix() throws Exception {
    MemoryFile active = new MemoryFile();
    SecurityAuditLog owner = owner(active, ACTIVE_BYTES, PENDING_BYTES);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      assertEquals(StatusCode.OK, owner.start());
      Future<StatusCode> first = workers.submit(() -> append(owner, 101, 11));
      assertTrue(active.firstForce.await(5, TimeUnit.SECONDS));
      Future<StatusCode> second = workers.submit(() -> append(owner, 102, 12));
      active.releaseForces.countDown();
      assertEquals(StatusCode.OK, get(first));
      assertEquals(StatusCode.OK, get(second));
      assertEquals(2, active.recordCount());
      assertEquals(1, active.record(0).getLong(8));
      assertEquals(2, active.record(1).getLong(8));
      assertEquals(101, active.record(0).getLong(48));
      assertEquals(102, active.record(1).getLong(48));
    } finally {
      active.releaseForces.countDown();
      workers.shutdownNow();
      assertEquals(StatusCode.OK, owner.finishClose());
    }
  }

  @Test
  void cancelledCallerReturnsWhileProviderForceIsStillBlocked() throws Exception {
    MemoryFile active = new MemoryFile();
    SecurityAuditLog owner = owner(active, ACTIVE_BYTES, PENDING_BYTES);
    MutableCancellationToken cancelled = new MutableCancellationToken();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      assertEquals(StatusCode.OK, owner.start());
      Future<StatusCode> result = worker.submit(() -> owner.append(
          201, SecurityAuditLog.AUTHENTICATION_DECISION, 1, 1, 1, 0, 0, 0, true,
          StatusCode.OK, cancelled, 0));
      assertTrue(active.firstForce.await(5, TimeUnit.SECONDS));
      cancelled.cancel();
      owner.cancelRequest(1, 1, 1);
      assertEquals(StatusCode.CANCELLED, get(result));
      assertFalse(active.closed);
    } finally {
      active.releaseForces.countDown();
      worker.shutdownNow();
      assertEquals(StatusCode.OK, owner.finishClose());
      assertTrue(active.closed);
    }
  }

  @Test
  void forceFailureFencesLaterAdmission() throws Exception {
    MemoryFile active = new MemoryFile();
    active.forceStatus = StatusCode.IO_FAILURE;
    active.releaseForces.countDown();
    SecurityAuditLog owner = owner(active, ACTIVE_BYTES, PENDING_BYTES);
    try {
      assertEquals(StatusCode.OK, owner.start());
      assertEquals(StatusCode.IO_FAILURE, append(owner, 501, 1));
      assertEquals(StatusCode.FENCED, append(owner, 502, 2));
    } finally {
      assertEquals(StatusCode.IO_FAILURE, owner.finishClose());
    }
  }

  @Test
  void activeRecoveryRejectsCorruptHeaderAndEvent() {
    MemoryFile file = new MemoryFile();
    AuditHeader header = new AuditHeader(FORMAT);
    assertEquals(StatusCode.OK, header.encode(
        AUDIT_GENERATION, INSTANCE_HIGH, INSTANCE_LOW, CREDENTIAL_GENERATION, 1));
    write(file, 0, header.bytes());
    AuditRecord record = new AuditRecord(FORMAT);
    assertEquals(StatusCode.OK, record.encode(
        1, AUDIT_GENERATION, INSTANCE_HIGH, INSTANCE_LOW, CREDENTIAL_GENERATION,
        301, 1, 1, 1, AuditRecord.STATEMENT_ADMISSION_DECISION,
        SessionAuthorizationPhase.EXECUTE, 0, SessionPermissions.READ, true,
        StatusCode.OK.stableCode()));
    write(file, FORMAT.headerBytes(), record.bytes());
    assertEquals(StatusCode.OK, AuditRecovery.validateActive(
        file, FORMAT, 1, 1, AUDIT_GENERATION, INSTANCE_HIGH, INSTANCE_LOW,
        CREDENTIAL_GENERATION));

    file.flipByte(8);
    assertEquals(StatusCode.CORRUPTION, AuditRecovery.validateActive(
        file, FORMAT, 1, 1, AUDIT_GENERATION, INSTANCE_HIGH, INSTANCE_LOW,
        CREDENTIAL_GENERATION));
    file.flipByte(8);
    file.flipByte(FORMAT.headerBytes() + 80);
    assertEquals(StatusCode.CORRUPTION, AuditRecovery.validateActive(
        file, FORMAT, 1, 1, AUDIT_GENERATION, INSTANCE_HIGH, INSTANCE_LOW,
        CREDENTIAL_GENERATION));
  }

  @Test
  void equalGenerationControlCopiesMustBeIdentical() {
    MemoryFile first = new MemoryFile(FORMAT.controlBytes());
    MemoryFile second = new MemoryFile(FORMAT.controlBytes());
    byte[] activeDigest = AuditDigest.name("active-content");
    byte[] names = encodedNames();
    AuditControl control = new AuditControl(FORMAT);
    assertEquals(StatusCode.OK, control.encode(
        1, AuditControl.ACTIVE, INSTANCE_HIGH, INSTANCE_LOW, 1,
        1, 1, 0, FORMAT.headerBytes(), activeDigest, 0, AuditDigest.name(""),
        AuditDigest.name("audit-1.log"), AuditDigest.name("audit-1.log"), AuditDigest.name(""),
        ByteBuffer.wrap(names)));
    write(first, 0, control.bytes());
    AuditControl divergent = new AuditControl(FORMAT);
    assertEquals(StatusCode.OK, divergent.encode(
        1, AuditControl.ACTIVE, INSTANCE_HIGH, INSTANCE_LOW, 1,
        1, 2, 1, FORMAT.headerBytes() + FORMAT.eventBytes(), activeDigest, 0,
        AuditDigest.name(""), AuditDigest.name("audit-1.log"),
        AuditDigest.name("audit-1.log"), AuditDigest.name(""), ByteBuffer.wrap(names)));
    write(second, 0, divergent.bytes());
    AuditControlRecovery.Result result = new AuditControlRecovery.Result();
    assertEquals(StatusCode.CORRUPTION, AuditControlRecovery.recover(
        first, second, FORMAT, INSTANCE_HIGH, INSTANCE_LOW, result));
  }

  @Test
  void terminalExhaustionIsForcedBeforeResourceStatusReturns() throws Exception {
    AuditFormat format = new AuditFormat();
    MemoryFile active = new MemoryFile(format.headerBytes() + format.eventBytes());
    MemoryFile controlA = new MemoryFile(format.controlBytes());
    MemoryFile controlB = new MemoryFile(format.controlBytes());
    long firstSequence = Long.MAX_VALUE - 1;
    AuditHeader header = new AuditHeader(format);
    assertEquals(StatusCode.OK, header.encode(
        1, INSTANCE_HIGH, INSTANCE_LOW, CREDENTIAL_GENERATION, firstSequence));
    write(active, 0, header.bytes());
    AuditRecord record = new AuditRecord(format);
    assertEquals(StatusCode.OK, record.encode(
        firstSequence, 1, INSTANCE_HIGH, INSTANCE_LOW, CREDENTIAL_GENERATION,
        601, 1, 1, 1, AuditRecord.STATEMENT_ADMISSION_DECISION,
        SessionAuthorizationPhase.EXECUTE, 0, SessionPermissions.READ, true,
        StatusCode.OK.stableCode()));
    write(active, format.headerBytes(), record.bytes());
    byte[] activeDigest = AuditDigest.file(active);
    AuditControl initial = new AuditControl(format);
    ByteBuffer names = ByteBuffer.wrap(encodedNames());
    assertEquals(StatusCode.OK, initial.encode(
        1, AuditControl.ACTIVE, INSTANCE_HIGH, INSTANCE_LOW, 1,
        firstSequence, Long.MAX_VALUE, firstSequence, active.sizeBytes(), activeDigest,
        0, AuditDigest.name(""), AuditDigest.name("audit-1.log"),
        AuditDigest.name("audit-1.log"), AuditDigest.name(""), names));
    write(controlA, 0, initial.bytes());
    write(controlB, 0, initial.bytes());
    byte[] predecessorDigest = AuditDigest.file(controlA);
    SecurityAuditLog owner = new SecurityAuditLog(
        new EmptyDirectory(), active, controlA, controlB, format,
        format.headerBytes() + format.eventBytes() * 3L, format.slotBytes() * 2L,
        1, INSTANCE_HIGH, INSTANCE_LOW, CREDENTIAL_GENERATION,
        firstSequence, 1, true, predecessorDigest, AuditDigest.name("audit-1.log"),
        AuditDigest.name("audit-1.log"), AuditDigest.name(""), encodedNames(),
        firstSequence, active.sizeBytes(), false);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      assertEquals(StatusCode.OK, owner.start());
      Future<StatusCode> result = worker.submit(() -> append(owner, 601, 1));
      assertTrue(controlB.firstForce.await(5, TimeUnit.SECONDS));
      assertFalse(result.isDone());
      controlB.releaseForces.countDown();
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, get(result));
      assertTrue(controlB.forces >= 1);
    } finally {
      controlB.releaseForces.countDown();
      worker.shutdownNow();
      assertEquals(StatusCode.OK, owner.finishClose());
      AuditControlRecovery.Result recoveredControl = new AuditControlRecovery.Result();
      assertEquals(StatusCode.OK, AuditControlRecovery.recover(
          controlA, controlB, format, INSTANCE_HIGH, INSTANCE_LOW, recoveredControl));
      assertEquals(AuditControl.EXHAUSTED, recoveredControl.state());
      assertEquals(Long.MAX_VALUE, recoveredControl.generation());
      AuditRecovery.RecoveryResult recoveredActive = new AuditRecovery.RecoveryResult();
      assertEquals(StatusCode.OK, AuditRecovery.recoverActive(
          active, format, firstSequence, 1, INSTANCE_HIGH, INSTANCE_LOW,
          CREDENTIAL_GENERATION, recoveredActive));
      assertEquals(firstSequence, recoveredActive.durableSequence());
    }
  }

  private static SecurityAuditLog owner(MemoryFile active, long activeBytes, long pendingBytes) {
    return new SecurityAuditLog(
        new EmptyDirectory(), active, new MemoryFile(FORMAT.controlBytes()),
        new MemoryFile(FORMAT.controlBytes()), FORMAT, activeBytes, pendingBytes,
        AUDIT_GENERATION, INSTANCE_HIGH, INSTANCE_LOW, CREDENTIAL_GENERATION, 1, 1, true,
        new byte[32], AuditDigest.name("audit-1.log"), AuditDigest.name("audit-1.log"),
        AuditDigest.name(""), encodedNames(), 0, FORMAT.headerBytes(), false);
  }

  private static StatusCode append(SecurityAuditLog owner, long principal, long request) {
    return owner.append(principal, SecurityAuditLog.STATEMENT_ADMISSION_DECISION,
        1, 1, request, SessionAuthorizationPhase.EXECUTE, 0,
        SessionPermissions.READ, true, StatusCode.OK, CancellationToken.NONE, 0);
  }

  private static byte[] encodedNames() {
    ByteBuffer source = AuditNames.encode("audit-1.log", "audit-1.log", "");
    byte[] names = new byte[source.remaining()];
    source.get(names);
    return names;
  }

  private static void write(MemoryFile file, long position, ByteBuffer source) {
    ByteBuffer copy = source.duplicate();
    IoResult result = new IoResult();
    assertEquals(StatusCode.OK, file.write(position, copy, result));
    assertEquals(source.remaining(), result.bytesTransferred());
  }

  private static StatusCode get(Future<StatusCode> future)
      throws InterruptedException, ExecutionException, TimeoutException {
    return future.get(5, TimeUnit.SECONDS);
  }

  private static final class MemoryFile implements RiverFile {
    private byte[] bytes;
    private volatile boolean closed;
    private volatile StatusCode forceStatus = StatusCode.OK;
    private int forces;
    private final CountDownLatch firstForce = new CountDownLatch(1);
    private final CountDownLatch releaseForces = new CountDownLatch(1);

    MemoryFile() { this(64); }
    MemoryFile(int initialSize) { bytes = new byte[initialSize]; }

    @Override public synchronized StatusCode read(long position, ByteBuffer target, IoResult result) {
      if (position < 0 || position > bytes.length) return StatusCode.IO_FAILURE;
      int count = Math.min(target.remaining(), bytes.length - (int) position);
      target.put(bytes, (int) position, count);
      result.setBytesTransferred(count);
      return StatusCode.OK;
    }

    @Override public synchronized StatusCode write(long position, ByteBuffer source, IoResult result) {
      if (position < 0 || position > Integer.MAX_VALUE - source.remaining()) {
        return StatusCode.IO_FAILURE;
      }
      int start = (int) position;
      int count = source.remaining();
      int end = start + count;
      if (end > bytes.length) bytes = Arrays.copyOf(bytes, end);
      source.get(bytes, start, count);
      result.setBytesTransferred(count);
      return StatusCode.OK;
    }

    @Override public StatusCode force(ForceMode mode) {
      forces++;
      firstForce.countDown();
      try {
        releaseForces.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return StatusCode.CANCELLED;
      }
      return forceStatus;
    }

    @Override public synchronized StatusCode truncate(long sizeBytes) {
      if (sizeBytes < 0 || sizeBytes > Integer.MAX_VALUE) return StatusCode.IO_FAILURE;
      bytes = Arrays.copyOf(bytes, (int) sizeBytes);
      return StatusCode.OK;
    }

    @Override public synchronized StatusCode size(FileSizeResult result) {
      result.setSizeBytes(bytes.length);
      return StatusCode.OK;
    }

    long sizeBytes() { return bytes.length; }
    @Override public StatusCode close() { closed = true; return StatusCode.OK; }
    @Override public FileIdentity identity() { return new FileIdentity(1, 1, 1); }
    synchronized int recordCount() {
      return Math.max(0, (bytes.length - FORMAT.headerBytes()) / FORMAT.eventBytes());
    }
    synchronized ByteBuffer record(int index) {
      ByteBuffer value = ByteBuffer.wrap(bytes,
          FORMAT.headerBytes() + index * FORMAT.eventBytes(), FORMAT.eventBytes()).slice();
      return value.order(ByteOrder.BIG_ENDIAN);
    }
    synchronized void flipByte(int position) { bytes[position] ^= 1; }
  }

  private static class EmptyDirectory implements RiverDirectory {
    @Override public FileIdentity identity() { return new FileIdentity(1, 2, 2); }
    @Override public StatusCode createDirectory(String n, RiverDirectoryResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode openDirectory(String n, RiverDirectoryResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode openFile(String n, RiverOpenMode m, RiverFileResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode list(DirectoryListResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode publishExclusive(RiverFile f, String a, String b, DirectoryOperationResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode publishReplacement(RiverFile f, String a, String b, DirectoryOperationResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode publishDirectoryExclusive(RiverDirectory p, RiverDirectory d, String a, String b, DirectoryOperationResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode removeOwned(String n, FileIdentity i, DirectoryOperationResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode force(DirectoryOperationResult r) { return StatusCode.OK; }
    @Override public StatusCode close() { return StatusCode.OK; }
  }
}
