package io.riverdb.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;

final class FormatBytesTest {
  @Test
  void writesCanonicalLittleEndianBytesToHeapAndDirectBuffers() {
    ByteBuffer heap = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
    heap.position(2);
    heap.limit(30);
    writeAndAssert(heap, 4);

    ByteBuffer direct = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN);
    direct.position(1);
    direct.limit(29);
    writeAndAssert(direct, 3);
  }

  @Test
  void readsAlignedAndUnalignedSlicedReadOnlyBuffersWithoutChangingState() {
    ByteBuffer heap = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
    putEncodedBytes(heap, 4);
    assertReadsAndState(heap, 4);

    ByteBuffer direct = ByteBuffer.allocateDirect(40).order(ByteOrder.BIG_ENDIAN);
    putEncodedBytes(direct, 5);
    direct.position(1);
    direct.limit(33);
    ByteBuffer slicedReadOnly = direct.slice().order(ByteOrder.BIG_ENDIAN).asReadOnlyBuffer();
    assertReadsAndState(slicedReadOnly, 4);
  }

  @Test
  void rejectsReadOnlyWritesAndOutOfBoundsAccess() {
    ByteBuffer readOnly = ByteBuffer.allocate(8).asReadOnlyBuffer();
    assertThrows(
        ReadOnlyBufferException.class,
        () -> FormatBytes.putShort(readOnly, 0, (short) 1));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> FormatBytes.putLong(ByteBuffer.allocate(7), 0, 1));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> FormatBytes.getInt(ByteBuffer.allocate(3), 0));
  }

  private static void writeAndAssert(ByteBuffer buffer, int offset) {
    int position = buffer.position();
    int limit = buffer.limit();
    ByteOrder order = buffer.order();

    FormatBytes.putShort(buffer, offset, (short) 0xD234);
    FormatBytes.putInt(buffer, offset + 4, 0x89ABCDEF);
    FormatBytes.putLong(buffer, offset + 12, 0xFEDCBA9876543210L);

    assertArrayEquals(
        new byte[] {0x34, (byte) 0xD2}, bytesAt(buffer, offset, 2));
    assertArrayEquals(
        new byte[] {(byte) 0xEF, (byte) 0xCD, (byte) 0xAB, (byte) 0x89},
        bytesAt(buffer, offset + 4, 4));
    assertArrayEquals(
        new byte[] {
          0x10, 0x32, 0x54, 0x76,
          (byte) 0x98, (byte) 0xBA, (byte) 0xDC, (byte) 0xFE
        },
        bytesAt(buffer, offset + 12, 8));
    assertEquals(position, buffer.position());
    assertEquals(limit, buffer.limit());
    assertEquals(order, buffer.order());
  }

  private static void putEncodedBytes(ByteBuffer buffer, int offset) {
    byte[] encoded = {
      0x34, (byte) 0xD2,
      (byte) 0xEF, (byte) 0xCD, (byte) 0xAB, (byte) 0x89,
      (byte) 0xEF, (byte) 0xCD, (byte) 0xAB, (byte) 0x89,
      0x67, 0x45, 0x23, 0x01
    };
    for (int index = 0; index < encoded.length; index++) {
      buffer.put(offset + index, encoded[index]);
    }
  }

  private static void assertReadsAndState(ByteBuffer buffer, int offset) {
    int position = buffer.position();
    int limit = buffer.limit();
    ByteOrder order = buffer.order();

    assertEquals((short) 0xD234, FormatBytes.getShort(buffer, offset));
    assertEquals(0x89ABCDEF, FormatBytes.getInt(buffer, offset + 2));
    assertEquals(0x0123456789ABCDEFL, FormatBytes.getLong(buffer, offset + 6));
    assertEquals(position, buffer.position());
    assertEquals(limit, buffer.limit());
    assertEquals(order, buffer.order());
  }

  private static byte[] bytesAt(ByteBuffer buffer, int offset, int length) {
    byte[] bytes = new byte[length];
    for (int index = 0; index < length; index++) {
      bytes[index] = buffer.get(offset + index);
    }
    return bytes;
  }
}
