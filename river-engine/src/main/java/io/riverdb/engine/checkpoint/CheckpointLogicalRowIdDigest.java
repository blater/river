package io.riverdb.engine.checkpoint;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Reusable streaming CRC32C with explicit zeroing for the stored digest pair. */
final class CheckpointLogicalRowIdDigest {
  private final CRC32C checksum = new CRC32C();

  void reset(ByteBuffer header) {
    checksum.reset();
    update(header, CheckpointLogicalRowIdFormat.HEADER_BYTES,
        CheckpointLogicalRowIdFormat.DIGEST_OFFSET);
  }

  void update(ByteBuffer bytes, int length) {
    update(bytes, length, -1);
  }

  int value() {
    return (int) checksum.getValue();
  }

  private void update(ByteBuffer bytes, int length, int zeroOffset) {
    for (int index = 0; index < length; index++) {
      boolean zero = zeroOffset >= 0 && index >= zeroOffset && index < zeroOffset + 8;
      checksum.update(zero ? 0 : bytes.get(index));
    }
  }
}
