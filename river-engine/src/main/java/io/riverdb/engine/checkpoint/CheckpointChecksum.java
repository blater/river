package io.riverdb.engine.checkpoint;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Reusable CRC32C state for one checkpoint I/O owner. */
final class CheckpointChecksum {
  private final CRC32C checksum = new CRC32C();

  int value(ByteBuffer bytes, int length) {
    return value(bytes, length, length - 8);
  }

  int value(ByteBuffer bytes, int length, int zeroOffset) {
    checksum.reset();
    for (int index = 0; index < length; index++) {
      boolean zero = zeroOffset >= 0 && index >= zeroOffset && index < zeroOffset + 8;
      checksum.update(zero ? 0 : bytes.get(index));
    }
    return (int) checksum.getValue();
  }
}
