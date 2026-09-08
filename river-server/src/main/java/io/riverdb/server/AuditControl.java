package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Redundant control record; publication is file-force then directory-force. */
final class AuditControl {
  static final int ACTIVE = 1;
  static final int ARCHIVING = 2;
  static final int EXHAUSTED = 3;

  private final ByteBuffer bytes;
  private final AuditFormat format;

  AuditControl(AuditFormat format) {
    this.format = format;
    bytes = ByteBuffer.allocateDirect(format.controlBytes()).order(ByteOrder.BIG_ENDIAN);
  }

  StatusCode encode(
      long controlGeneration,
      int state,
      long instanceHigh,
      long instanceLow,
      long auditGeneration,
      long firstSequence,
      long nextSequence,
      long durableSequence,
      long durableLength,
      byte[] activeDigest,
      long predecessorGeneration,
      byte[] predecessorDigest,
      byte[] oldNameDigest,
      byte[] newNameDigest,
      byte[] archiveNameDigest,
      ByteBuffer encodedNames) {
    if (controlGeneration <= 0 || (controlGeneration == Long.MAX_VALUE && state != EXHAUSTED)
        || (state != ACTIVE && state != ARCHIVING && state != EXHAUSTED)
        || auditGeneration <= 0 || firstSequence <= 0 || nextSequence < firstSequence
        || durableSequence < firstSequence - 1 || durableSequence >= nextSequence
        || durableLength < 0 || predecessorGeneration < 0
        || !validDigest(activeDigest) || !validDigest(predecessorDigest)
        || !validDigest(oldNameDigest) || !validDigest(newNameDigest)
        || !validDigest(archiveNameDigest) || encodedNames == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bytes.clear();
    if (bytes.remaining() < 9 * Long.BYTES + 5 * 32 + 3 * Integer.BYTES) {
      return StatusCode.INVARIANT_BROKEN;
    }
    bytes.putLong(controlGeneration).putInt(state).putInt(0);
    bytes.putLong(instanceHigh).putLong(instanceLow).putLong(auditGeneration);
    bytes.putLong(firstSequence).putLong(nextSequence).putLong(durableSequence);
    bytes.putLong(durableLength).put(activeDigest);
    bytes.putLong(predecessorGeneration).put(predecessorDigest);
    bytes.put(oldNameDigest).put(newNameDigest).put(archiveNameDigest);
    ByteBuffer names = encodedNames.duplicate();
    if (names.remaining() > bytes.remaining() - Integer.BYTES) {
      return StatusCode.INVARIANT_BROKEN;
    }
    bytes.put(names);
    while (bytes.remaining() > Integer.BYTES) bytes.put((byte) 0);
    int checksumPosition = bytes.position();
    bytes.putInt(0);
    while (bytes.hasRemaining()) bytes.put((byte) 0);
    bytes.putInt(checksumPosition, checksum(bytes, checksumPosition));
    bytes.flip();
    return StatusCode.OK;
  }

  ByteBuffer bytes() {
    return bytes.asReadOnlyBuffer();
  }

  private static boolean validDigest(byte[] digest) {
    return digest != null && digest.length == 32;
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
}
