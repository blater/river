package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Fixed active-stream identity header; all fields are validated on reopen. */
final class AuditHeader {
  static final int MAGIC = 0x52415548;
  static final int VERSION = 1;
  private final AuditFormat format;
  private final ByteBuffer bytes;

  AuditHeader(AuditFormat format) {
    this.format = format;
    bytes = ByteBuffer.allocateDirect(format.headerBytes()).order(ByteOrder.BIG_ENDIAN);
  }

  StatusCode encode(
      long auditGeneration,
      long instanceHigh,
      long instanceLow,
      long credentialGeneration,
      long firstSequence) {
    if (auditGeneration <= 0 || auditGeneration == Long.MAX_VALUE || firstSequence <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bytes.clear();
    if (bytes.remaining() < 5 * Long.BYTES + 6 * Integer.BYTES) {
      return StatusCode.INVARIANT_BROKEN;
    }
    bytes.putInt(MAGIC).putInt(VERSION).putInt(format.headerBytes()).putInt(format.eventBytes());
    bytes.putLong(auditGeneration).putLong(instanceHigh).putLong(instanceLow);
    bytes.putLong(credentialGeneration).putLong(firstSequence);
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

  static StatusCode validate(
      ByteBuffer value,
      AuditFormat format,
      long expectedGeneration,
      long expectedInstanceHigh,
      long expectedInstanceLow,
      long expectedCredentialGeneration,
      long expectedFirstSequence) {
    if (value.remaining() != format.headerBytes()
        || value.getInt(0) != MAGIC || value.getInt(4) != VERSION
        || value.getInt(8) != format.headerBytes()
        || value.getInt(12) != format.eventBytes()
        || value.getLong(16) != expectedGeneration
        || value.getLong(24) != expectedInstanceHigh
        || value.getLong(32) != expectedInstanceLow
        || value.getLong(40) != expectedCredentialGeneration
        || value.getLong(48) != expectedFirstSequence
        || value.getInt(56) != checksum(value, 56)) return StatusCode.CORRUPTION;
    for (int index = 60; index < value.limit(); index++) {
      if (value.get(index) != 0) return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
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
