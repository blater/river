package io.riverdb.format;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Canonical little-endian durable primitives independent of caller buffer order. */
public final class FormatBytes {
  private FormatBytes() {
  }

  public static int getInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset))
        | Byte.toUnsignedInt(source.get(offset + 1)) << 8
        | Byte.toUnsignedInt(source.get(offset + 2)) << 16
        | source.get(offset + 3) << 24;
  }

  public static void putInt(ByteBuffer target, int offset, int value) {
    target.put(offset, (byte) value);
    target.put(offset + 1, (byte) (value >>> 8));
    target.put(offset + 2, (byte) (value >>> 16));
    target.put(offset + 3, (byte) (value >>> 24));
  }

  public static long getLong(ByteBuffer source, int offset) {
    return Integer.toUnsignedLong(getInt(source, offset))
        | (long) getInt(source, offset + 4) << 32;
  }

  public static void putLong(ByteBuffer target, int offset, long value) {
    putInt(target, offset, (int) value);
    putInt(target, offset + 4, (int) (value >>> 32));
  }

  public static int checksum(
      ByteBuffer source, int offset, int length, CRC32C checksum) {
    checksum.reset();
    for (int index = 0; index < length; index++) checksum.update(source.get(offset + index));
    return (int) checksum.getValue();
  }
}
