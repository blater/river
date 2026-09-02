package io.riverdb.engine.checkpoint;

import java.nio.ByteBuffer;

/** Versioned little-endian encoding for one logical-row-floor generation. */
final class CheckpointLogicalRowIdFormat {
  static final long MAGIC = 0x5249564C52494431L; // RIVLRID1
  static final int VERSION = 1;
  static final int HEADER_BYTES = 128;
  static final int RECORD_BYTES = 16;
  static final int DIGEST_OFFSET = 80;

  private CheckpointLogicalRowIdFormat() {
  }

  static long fileBytes(int count) {
    if (count < 0) return -1;
    try {
      return Math.addExact(HEADER_BYTES, Math.multiplyExact((long) count, RECORD_BYTES));
    } catch (ArithmeticException overflow) {
      return -1;
    }
  }

  static void encodeHeader(ByteBuffer bytes, CheckpointState state, int count, long fileBytes) {
    zero(bytes, HEADER_BYTES);
    bytes.putLong(0, MAGIC);
    bytes.putInt(8, VERSION);
    bytes.putInt(12, HEADER_BYTES);
    bytes.putLong(16, state.database().high());
    bytes.putLong(24, state.database().low());
    bytes.putLong(32, state.walGeneration().value());
    bytes.putLong(40, state.checkpointId());
    bytes.putLong(48, state.commitSequence());
    bytes.putLong(56, count);
    bytes.putInt(64, RECORD_BYTES);
    bytes.putLong(72, fileBytes);
  }

  static boolean validHeader(
      ByteBuffer bytes, CheckpointState state,
      CheckpointLogicalRowIdManifestReference reference) {
    int stored = bytes.getInt(DIGEST_OFFSET);
    return bytes.getLong(0) == MAGIC
        && bytes.getInt(8) == VERSION
        && bytes.getInt(12) == HEADER_BYTES
        && bytes.getLong(16) == state.database().high()
        && bytes.getLong(24) == state.database().low()
        && bytes.getLong(32) == state.walGeneration().value()
        && bytes.getLong(40) == state.checkpointId()
        && bytes.getLong(48) == state.commitSequence()
        && bytes.getLong(56) == reference.count()
        && bytes.getInt(64) == RECORD_BYTES
        && bytes.getInt(68) == 0
        && bytes.getLong(72) == reference.fileBytes()
        && stored == reference.digest()
        && bytes.getInt(DIGEST_OFFSET + Integer.BYTES) == ~stored
        && zeroRange(bytes, 88, HEADER_BYTES);
  }

  static void storeDigest(ByteBuffer bytes, int digest) {
    bytes.putInt(DIGEST_OFFSET, digest);
    bytes.putInt(DIGEST_OFFSET + Integer.BYTES, ~digest);
  }

  private static void zero(ByteBuffer bytes, int length) {
    for (int index = 0; index < length; index++) bytes.put(index, (byte) 0);
  }

  private static boolean zeroRange(ByteBuffer bytes, int start, int end) {
    for (int index = start; index < end; index++) {
      if (bytes.get(index) != 0) return false;
    }
    return true;
  }
}
