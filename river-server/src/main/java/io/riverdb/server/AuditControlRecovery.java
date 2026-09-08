package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Selects the highest valid redundant control generation without mutation. */
final class AuditControlRecovery {
  private AuditControlRecovery() { }

  static StatusCode recover(
      RiverFile first,
      RiverFile second,
      AuditFormat format,
      long instanceHigh,
      long instanceLow,
      Result result) {
    if (first == null || second == null || format == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    Candidate a = read(first, format);
    Candidate b = read(second, format);
    if (a.status != StatusCode.OK && a.status != StatusCode.CORRUPTION) return a.status;
    if (b.status != StatusCode.OK && b.status != StatusCode.CORRUPTION) return b.status;
    if (a.status != StatusCode.OK && b.status != StatusCode.OK) return StatusCode.CORRUPTION;
    if (a.status == StatusCode.OK && b.status == StatusCode.OK
        && a.generation == b.generation && !a.samePayload(b)) return StatusCode.CORRUPTION;
    Candidate selected = a.status == StatusCode.OK && b.status == StatusCode.OK
        ? a.generation >= b.generation ? a : b
        : a.status == StatusCode.OK ? a : b;
    RiverFile selectedFile = selected == a ? first : second;
    if (selected.instanceHigh != instanceHigh || selected.instanceLow != instanceLow) {
      return StatusCode.CORRUPTION;
    }
    if (selected.state == AuditControl.ARCHIVING) return StatusCode.CORRUPTION;
    Candidate predecessor = selected == a ? b : a;
    if (selected.generation == 1) {
      if (selected.predecessorGeneration != 0
          || !Arrays.equals(selected.predecessorDigest, AuditDigest.name(""))) {
        return StatusCode.CORRUPTION;
      }
    } else if (predecessor.status != StatusCode.OK
        || predecessor.generation == Long.MAX_VALUE
        || selected.predecessorGeneration != predecessor.generation
        || (selected.state != AuditControl.EXHAUSTED
            && predecessor.generation + 1 != selected.generation)) {
      return StatusCode.CORRUPTION;
    } else {
      byte[] predecessorFileDigest = AuditDigest.file(selected == a ? second : first);
      if (predecessorFileDigest == null) return StatusCode.IO_FAILURE;
      if (!Arrays.equals(selected.predecessorDigest, predecessorFileDigest)) {
        return StatusCode.CORRUPTION;
      }
    }
    byte[] controlDigest = AuditDigest.file(selectedFile);
    if (controlDigest == null) return StatusCode.IO_FAILURE;
    result.set(selected.state, selected.generation, selected.auditGeneration, selected.firstSequence,
        selected.durableSequence, selected.durableLength, selected.activeDigest,
        selected == a ? 0 : 1, controlDigest);
    return StatusCode.OK;
  }

  private static Candidate read(RiverFile file, AuditFormat format) {
    if (format.controlBytes() < 244) return Candidate.invalid(StatusCode.INVARIANT_BROKEN);
    FileSizeResult size = new FileSizeResult();
    StatusCode sizeStatus = file.size(size);
    if (!sizeStatus.isOk()) return Candidate.invalid(sizeStatus);
    if (size.sizeBytes() != format.controlBytes()) return Candidate.invalid(StatusCode.CORRUPTION);
    ByteBuffer bytes = ByteBuffer.allocateDirect(format.controlBytes()).order(ByteOrder.BIG_ENDIAN);
    IoResult io = new IoResult();
    long position = 0;
    while (bytes.hasRemaining()) {
      io.reset();
      StatusCode status = file.read(position, bytes, io);
      if (!status.isOk()) return Candidate.invalid(status);
      if (io.bytesTransferred() <= 0) return Candidate.invalid(StatusCode.CORRUPTION);
      position += io.bytesTransferred();
    }
    bytes.flip();
    long generation = bytes.getLong();
    int state = bytes.getInt();
    int reserved = bytes.getInt();
    long instanceHigh = bytes.getLong();
    long instanceLow = bytes.getLong();
    long auditGeneration = bytes.getLong(32);
    long firstSequence = bytes.getLong(40);
    long nextSequence = bytes.getLong(48);
    bytes.position(56);
    long durableSequence = bytes.getLong();
    long durableLength = bytes.getLong();
    long predecessorGeneration = bytes.getLong(104);
    byte[] predecessorDigest = new byte[32];
    for (int index = 0; index < predecessorDigest.length; index++) {
      predecessorDigest[index] = bytes.get(112 + index);
    }
    int checksumPosition = format.controlBytes() - Integer.BYTES;
    int storedChecksum = bytes.getInt(checksumPosition);
    byte[] activeDigest = new byte[32];
    for (int index = 0; index < activeDigest.length; index++) activeDigest[index] = bytes.get(72 + index);
    if (reserved != 0 || storedChecksum != checksum(bytes, checksumPosition)
        || (state != AuditControl.ACTIVE
        && state != AuditControl.ARCHIVING && state != AuditControl.EXHAUSTED)
        || generation <= 0
        || (state == AuditControl.EXHAUSTED && generation != Long.MAX_VALUE)
        || (state != AuditControl.EXHAUSTED && generation == Long.MAX_VALUE)
        || predecessorGeneration >= generation
        || !validNames(bytes, checksumPosition)) return Candidate.invalid(StatusCode.CORRUPTION);
    if (auditGeneration <= 0 || auditGeneration == Long.MAX_VALUE || firstSequence <= 0
        || nextSequence < firstSequence || durableSequence < firstSequence - 1
        || durableSequence >= nextSequence || durableLength < format.headerBytes()) {
      return Candidate.invalid(StatusCode.CORRUPTION);
    }
    ByteBuffer copy = bytes.duplicate();
    copy.position(0);
    copy.limit(bytes.limit());
    byte[] raw = new byte[copy.remaining()];
    copy.get(raw);
    return new Candidate(StatusCode.OK, generation, state, instanceHigh, instanceLow,
        auditGeneration, firstSequence, durableSequence, durableLength, activeDigest,
        predecessorGeneration, predecessorDigest, raw);
  }

  private static boolean validNames(ByteBuffer bytes, int checksumPosition) {
    int start = 240;
    int nulCount = 0;
    int fieldStart = start;
    String[] names = new String[3];
    for (int index = start; index < checksumPosition; index++) {
      byte value = bytes.get(index);
      if (value != 0) continue;
      if (nulCount == 0 && index == fieldStart) return false;
      byte[] field = new byte[index - fieldStart];
      for (int offset = 0; offset < field.length; offset++) field[offset] = bytes.get(fieldStart + offset);
      String decoded = new String(field, StandardCharsets.UTF_8);
      if (!Arrays.equals(field, decoded.getBytes(StandardCharsets.UTF_8))) return false;
      if (!Arrays.equals(AuditDigest.name(decoded), digestAt(bytes, 144 + nulCount * 32))) return false;
      names[nulCount] = decoded;
      nulCount++;
      fieldStart = index + 1;
      if (nulCount == 3) {
        for (int tail = fieldStart; tail < checksumPosition; tail++) {
          if (bytes.get(tail) != 0) return false;
        }
        return "audit-1.log".equals(names[0])
            && "audit-1.log".equals(names[1]) && "".equals(names[2]);
      }
    }
    return false;
  }

  private static byte[] digestAt(ByteBuffer bytes, int offset) {
    byte[] digest = new byte[32];
    for (int index = 0; index < digest.length; index++) digest[index] = bytes.get(offset + index);
    return digest;
  }

  private static int checksum(ByteBuffer source, int checksumPosition) {
    int hash = 0x811c9dc5;
    for (int index = 0; index < source.limit(); index++) {
      if (index >= checksumPosition && index < checksumPosition + Integer.BYTES) continue;
      hash ^= source.get(index) & 0xff;
      hash *= 0x01000193;
    }
    return hash;
  }

  private record Candidate(
      StatusCode status,
      long generation,
      int state,
      long instanceHigh,
      long instanceLow,
      long auditGeneration,
      long firstSequence,
      long durableSequence,
      long durableLength,
      byte[] activeDigest,
      long predecessorGeneration,
      byte[] predecessorDigest,
      byte[] raw) {
    boolean samePayload(Candidate other) {
      return state == other.state && instanceHigh == other.instanceHigh
          && instanceLow == other.instanceLow && firstSequence == other.firstSequence
          && durableSequence == other.durableSequence && durableLength == other.durableLength
          && Arrays.equals(activeDigest, other.activeDigest)
          && predecessorGeneration == other.predecessorGeneration
          && Arrays.equals(predecessorDigest, other.predecessorDigest)
          && Arrays.equals(raw, other.raw);
    }
    static Candidate invalid(StatusCode status) {
      return new Candidate(status, 0, 0, 0, 0, 0, 0, 0, 0,
          new byte[0], 0, new byte[0], new byte[0]);
    }
  }

  static final class Result {
    private int state;
    private long generation;
    private long auditGeneration;
    private long firstSequence;
    private long durableSequence;
    private long durableLength;
    private byte[] activeDigest;
    private int selectedSlot;
    private byte[] controlDigest;
    void reset() { state = 0; generation = 0; auditGeneration = 0; firstSequence = 0; durableSequence = 0; durableLength = 0; activeDigest = null; selectedSlot = -1; controlDigest = null; }
    void set(int s, long g, long audit, long first, long sequence, long length, byte[] digest,
        int slot, byte[] selectedDigest) {
      state = s; generation = g; auditGeneration = audit; firstSequence = first;
      durableSequence = sequence; durableLength = length; activeDigest = digest.clone();
      selectedSlot = slot;
      controlDigest = selectedDigest == null ? null : selectedDigest.clone();
    }
    int state() { return state; }
    long generation() { return generation; }
    long auditGeneration() { return auditGeneration; }
    long firstSequence() { return firstSequence; }
    long durableSequence() { return durableSequence; }
    long durableLength() { return durableLength; }
    byte[] activeDigest() { return activeDigest.clone(); }
    int selectedSlot() { return selectedSlot; }
    byte[] controlDigest() { return controlDigest == null ? null : controlDigest.clone(); }
  }
}
