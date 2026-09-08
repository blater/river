package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverFile;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class AuditControlRecoveryTest {
  @Test
  void controlIoFailureSurvivesCandidateClassification() {
    AuditControlRecovery.Result result = new AuditControlRecovery.Result();
    StatusCode status = AuditControlRecovery.recover(
        new FailingFile(), new FailingFile(), new AuditFormat(), 1, 2, result);
    assertEquals(StatusCode.IO_FAILURE, status);
  }

  @Test
  void exhaustedGenerationLinksToTheOrdinaryPredecessor() {
    AuditFormat format = new AuditFormat();
    MemoryFile predecessor = new MemoryFile(format.controlBytes());
    MemoryFile terminal = new MemoryFile(format.controlBytes());
    byte[] activeDigest = AuditDigest.name("active-content");
    ByteBuffer names = AuditNames.encode("audit-1.log", "audit-1.log", "");
    AuditControl active = new AuditControl(format);
    assertEquals(StatusCode.OK, active.encode(
        1, AuditControl.ACTIVE, 1, 2, 1, 1, 1, 0, format.headerBytes(), activeDigest,
        0, AuditDigest.name(""), AuditDigest.name("audit-1.log"),
        AuditDigest.name("audit-1.log"), AuditDigest.name(""), names));
    write(predecessor, active.bytes());
    byte[] predecessorDigest = AuditDigest.file(predecessor);

    AuditControl exhausted = new AuditControl(format);
    names = AuditNames.encode("audit-1.log", "audit-1.log", "");
    assertEquals(StatusCode.OK, exhausted.encode(
        Long.MAX_VALUE, AuditControl.EXHAUSTED, 1, 2, 1, 1, 1, 0, format.headerBytes(),
        activeDigest, 1, predecessorDigest, AuditDigest.name("audit-1.log"),
        AuditDigest.name("audit-1.log"), AuditDigest.name(""), names));
    write(terminal, exhausted.bytes());

    AuditControlRecovery.Result result = new AuditControlRecovery.Result();
    assertEquals(StatusCode.OK, AuditControlRecovery.recover(
        predecessor, terminal, format, 1, 2, result));
    assertEquals(Long.MAX_VALUE, result.generation());
    assertEquals(1, result.selectedSlot());
  }

  @Test
  void predecessorDigestReadFailureSurvivesRecoveryClassification() {
    AuditFormat format = new AuditFormat();
    MemoryFile predecessor = new MemoryFile(format.controlBytes());
    MemoryFile selected = new MemoryFile(format.controlBytes());
    byte[] activeDigest = AuditDigest.name("active-content");
    AuditControl first = new AuditControl(format);
    assertEquals(StatusCode.OK, first.encode(
        1, AuditControl.ACTIVE, 1, 2, 1, 1, 1, 0, format.headerBytes(), activeDigest,
        0, AuditDigest.name(""), AuditDigest.name("audit-1.log"),
        AuditDigest.name("audit-1.log"), AuditDigest.name(""),
        AuditNames.encode("audit-1.log", "audit-1.log", "")));
    write(predecessor, first.bytes());
    byte[] predecessorDigest = AuditDigest.file(predecessor);
    AuditControl second = new AuditControl(format);
    assertEquals(StatusCode.OK, second.encode(
        2, AuditControl.ACTIVE, 1, 2, 1, 1, 1, 0, format.headerBytes(), activeDigest,
        1, predecessorDigest, AuditDigest.name("audit-1.log"),
        AuditDigest.name("audit-1.log"), AuditDigest.name(""),
        AuditNames.encode("audit-1.log", "audit-1.log", "")));
    write(selected, second.bytes());

    predecessor.sizeCalls = 0;
    predecessor.failAfterFirstSize = true;
    assertEquals(StatusCode.IO_FAILURE, AuditControlRecovery.recover(
        predecessor, selected, format, 1, 2, new AuditControlRecovery.Result()));
  }

  private static void write(MemoryFile file, ByteBuffer source) {
    ByteBuffer copy = source.duplicate();
    IoResult io = new IoResult();
    assertEquals(StatusCode.OK, file.write(0, copy, io));
    assertEquals(source.remaining(), io.bytesTransferred());
  }

  private static final class FailingFile implements RiverFile {
    @Override public FileIdentity identity() { return new FileIdentity(1, 2, 3); }
    @Override public StatusCode read(long position, ByteBuffer target, IoResult result) {
      return StatusCode.IO_FAILURE;
    }
    @Override public StatusCode write(long position, ByteBuffer source, IoResult result) {
      return StatusCode.IO_FAILURE;
    }
    @Override public StatusCode force(ForceMode mode) { return StatusCode.IO_FAILURE; }
    @Override public StatusCode truncate(long sizeBytes) { return StatusCode.IO_FAILURE; }
    @Override public StatusCode size(FileSizeResult result) { return StatusCode.IO_FAILURE; }
    @Override public StatusCode close() { return StatusCode.OK; }
  }

  private static final class MemoryFile implements RiverFile {
    private byte[] bytes;
    private boolean failAfterFirstSize;
    private int sizeCalls;

    MemoryFile(int size) { bytes = new byte[size]; }

    @Override public FileIdentity identity() { return new FileIdentity(1, 2, 3); }

    @Override public synchronized StatusCode read(
        long position, ByteBuffer target, IoResult result) {
      if (position < 0 || position > bytes.length) return StatusCode.IO_FAILURE;
      int count = Math.min(target.remaining(), bytes.length - (int) position);
      target.put(bytes, (int) position, count);
      result.setBytesTransferred(count);
      return StatusCode.OK;
    }

    @Override public synchronized StatusCode write(
        long position, ByteBuffer source, IoResult result) {
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

    @Override public StatusCode force(ForceMode mode) { return StatusCode.OK; }
    @Override public StatusCode truncate(long sizeBytes) { return StatusCode.IO_FAILURE; }
    @Override public synchronized StatusCode size(FileSizeResult result) {
      if (failAfterFirstSize && sizeCalls++ > 0) return StatusCode.IO_FAILURE;
      result.setSizeBytes(bytes.length);
      return StatusCode.OK;
    }
    @Override public StatusCode close() { return StatusCode.OK; }
  }
}
