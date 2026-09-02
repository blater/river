package io.riverdb.engine.checkpoint;

import java.nio.ByteBuffer;

/** Shared v3 sparse-version layout and checksum operations. */
final class CheckpointVersionFormat {
  static final int VERSION = 3;
  static final int PAGE_SHIFT = 11;
  static final int PAGE_ROWS = 1 << PAGE_SHIFT;
  static final int PAGE_MASK = PAGE_ROWS - 1;
  static final int RECORD_BYTES = Long.BYTES * 3;
  static final long SEGMENT_MAGIC = 0x5249565653454733L; // RIVVSEG3
  static final int SEGMENT_HEADER_BYTES = 80;
  static final int SEGMENT_BYTES = SEGMENT_HEADER_BYTES + PAGE_ROWS * RECORD_BYTES + 8;

  private CheckpointVersionFormat() {
  }

  static void zero(ByteBuffer bytes, int length) {
    bytes.clear();
    for (int index = 0; index < length; index++) bytes.put(index, (byte) 0);
  }

  static boolean zeroRange(ByteBuffer bytes, int start, int end) {
    for (int index = start; index < end; index++) {
      if (bytes.get(index) != 0) return false;
    }
    return true;
  }
}
