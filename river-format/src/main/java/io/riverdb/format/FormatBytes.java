package io.riverdb.format;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/** Canonical little-endian durable primitives independent of caller buffer order. */
public final class FormatBytes {
  private static final VarHandle SHORT_VIEW =
      MethodHandles.byteBufferViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
  private static final VarHandle INT_VIEW =
      MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
  private static final VarHandle LONG_VIEW =
      MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  private FormatBytes() {
  }

  public static short getShort(ByteBuffer source, int offset) {
    return (short) SHORT_VIEW.get(source, offset);
  }

  public static void putShort(ByteBuffer target, int offset, short value) {
    SHORT_VIEW.set(target, offset, value);
  }

  public static int getInt(ByteBuffer source, int offset) {
    return (int) INT_VIEW.get(source, offset);
  }

  public static void putInt(ByteBuffer target, int offset, int value) {
    INT_VIEW.set(target, offset, value);
  }

  public static long getLong(ByteBuffer source, int offset) {
    return (long) LONG_VIEW.get(source, offset);
  }

  public static void putLong(ByteBuffer target, int offset, long value) {
    LONG_VIEW.set(target, offset, value);
  }

  public static int checksum(
      ByteBuffer source, int offset, int length, CRC32C checksum) {
    checksum.reset();
    for (int index = 0; index < length; index++) checksum.update(source.get(offset + index));
    return (int) checksum.getValue();
  }
}
