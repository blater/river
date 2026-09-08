package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Provider-owned scalar event and fixed-width encoder. */
final class AuditRecord {
  static final int MAGIC = 0x52415544;
  static final int VERSION = 1;
  static final int AUTHENTICATION_DECISION = 1;
  static final int STATEMENT_ADMISSION_DECISION = 2;

  private final AuditFormat format;
  private final ByteBuffer bytes;

  AuditRecord(AuditFormat format) {
    this.format = format;
    bytes = ByteBuffer.allocateDirect(format.eventBytes()).order(ByteOrder.BIG_ENDIAN);
  }

  StatusCode encode(
      long sequence,
      long auditGeneration,
      long instanceHigh,
      long instanceLow,
      long credentialGeneration,
      long principalId,
      long connectionCorrelation,
      long sessionCorrelation,
      long requestCorrelation,
      int eventClass,
      int phase,
      int programStep,
      int permission,
      boolean allowed,
      int stableStatus) {
    if (sequence <= 0 || sequence == Long.MAX_VALUE
        || auditGeneration <= 0 || auditGeneration == Long.MAX_VALUE
        || principalId <= 0 || connectionCorrelation < 0 || sessionCorrelation < 0
        || requestCorrelation < 0
        || (eventClass != AUTHENTICATION_DECISION
            && eventClass != STATEMENT_ADMISSION_DECISION)
        || (eventClass == AUTHENTICATION_DECISION && (phase != 0 || programStep != 0))
        || (eventClass == STATEMENT_ADMISSION_DECISION && (phase <= 0 || programStep < 0))
        || (eventClass == STATEMENT_ADMISSION_DECISION
            && (permission <= 0 || Integer.bitCount(permission) != 1))
        || stableStatus < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bytes.clear();
    if (bytes.remaining() < 9 * Long.BYTES + 9 * Integer.BYTES) {
      return StatusCode.INVARIANT_BROKEN;
    }
    bytes.putInt(MAGIC).putInt(VERSION);
    bytes.putLong(sequence).putLong(auditGeneration);
    bytes.putLong(instanceHigh).putLong(instanceLow).putLong(credentialGeneration);
    bytes.putLong(principalId).putLong(connectionCorrelation);
    bytes.putLong(sessionCorrelation).putLong(requestCorrelation);
    bytes.putInt(eventClass).putInt(phase).putInt(programStep).putInt(permission);
    bytes.putInt(allowed ? 1 : 0).putInt(stableStatus);
    int checksumPosition = bytes.position();
    bytes.putInt(0);
    while (bytes.hasRemaining()) bytes.put((byte) 0);
    int checksum = checksum(bytes, checksumPosition);
    bytes.putInt(checksumPosition, checksum);
    bytes.flip();
    return StatusCode.OK;
  }

  ByteBuffer bytes() {
    return bytes.asReadOnlyBuffer();
  }

  static StatusCode validate(
      ByteBuffer value,
      AuditFormat format,
      long expectedSequence,
      long expectedGeneration,
      long expectedInstanceHigh,
      long expectedInstanceLow,
      long expectedCredentialGeneration) {
    if (value.remaining() != format.eventBytes()
        || value.getInt(0) != MAGIC || value.getInt(4) != VERSION
        || value.getLong(8) != expectedSequence
        || value.getLong(16) != expectedGeneration
        || value.getLong(24) != expectedInstanceHigh
        || value.getLong(32) != expectedInstanceLow
        || value.getLong(40) != expectedCredentialGeneration
        || value.getLong(48) <= 0
        || value.getLong(56) < 0 || value.getLong(64) < 0 || value.getLong(72) < 0
        || (value.getInt(80) != AUTHENTICATION_DECISION
            && value.getInt(80) != STATEMENT_ADMISSION_DECISION)
        || (value.getInt(80) == AUTHENTICATION_DECISION
            && (value.getInt(84) != 0 || value.getInt(88) != 0 || value.getInt(92) != 0))
        || (value.getInt(80) == STATEMENT_ADMISSION_DECISION
            && (value.getInt(84) <= 0 || value.getInt(88) < 0
                || value.getInt(92) <= 0 || Integer.bitCount(value.getInt(92)) != 1))
        || (value.getInt(96) != 0 && value.getInt(96) != 1)
        || value.getInt(96) < 0 || value.getInt(100) < 0
        || value.getInt(104) != checksum(value, 104)) return StatusCode.CORRUPTION;
    for (int index = 108; index < value.limit(); index++) {
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
