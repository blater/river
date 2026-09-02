package io.riverdb.base.column;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class ColumnBitSetTest {
  private static volatile long allocationGuard;

  @Test
  void startsEmptyWithoutCapacityAndHandlesBoundarySizes() {
    ColumnBitSet bits = new ColumnBitSet();
    assertEquals(0, bits.bitCount());
    assertEquals(0, bits.wordCount());
    assertEquals(0, bits.capacity());
    assertTrue(bits.isEmpty());

    int[] sizes = {0, 8, 63, 64, 65, 1_024, 1_664};
    for (int size : sizes) {
      assertEquals(StatusCode.OK, bits.reserve(size, 1_664));
      assertEquals(StatusCode.OK, bits.clearForSize(size));
      assertEquals(size, bits.bitCount());
      assertEquals((size + 63) >>> 6, bits.wordCount());
      assertTrue(bits.capacity() >= size);
      if (size != 0) {
        assertTrue(bits.set(size - 1));
        assertTrue(bits.get(size - 1));
        assertTrue(bits.clear(size - 1));
      }
      assertTrue(bits.isEmpty());
    }
  }

  @Test
  void rejectsNonCanonicalTrailingWords() {
    ColumnBitSet bits = new ColumnBitSet();
    assertEquals(StatusCode.OK, bits.reserve(65, 65));
    assertEquals(StatusCode.OK, bits.clearForSize(65));
    assertTrue(bits.setWord(0, -1L));
    assertTrue(bits.setWord(1, 1));
    assertFalse(bits.setWord(1, 2));
    assertEquals(1, bits.word(1));

    assertEquals(StatusCode.OK, bits.clearForSize(64));
    assertTrue(bits.setWord(0, -1L));
    assertEquals(-1L, bits.word(0));
  }

  @Test
  void copiesWithoutAliasingAndHonorsMaximum() {
    ColumnBitSet source = new ColumnBitSet();
    assertEquals(StatusCode.OK, source.reserve(65, 1_664));
    assertEquals(StatusCode.OK, source.clearForSize(65));
    assertTrue(source.set(0));
    assertTrue(source.set(64));

    long[] copiedWords = new long[4];
    assertTrue(source.copyWords(copiedWords, 1));
    assertEquals(1, copiedWords[1]);
    assertEquals(1, copiedWords[2]);
    assertFalse(source.copyWords(null));
    assertFalse(source.copyWords(new long[1]));
    assertFalse(source.copyWords(copiedWords, -1));

    ColumnBitSet copy = new ColumnBitSet();
    assertEquals(StatusCode.OK, copy.copyFrom(source, 65));
    assertEquals(65, copy.bitCount());
    assertTrue(copy.get(0));
    assertTrue(copy.get(64));
    assertTrue(source.clear(64));
    assertTrue(copy.get(64));

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, copy.copyFrom(source, 64));
    assertTrue(copy.get(64));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, copy.copyFrom(null, 65));
  }

  @Test
  void shrinkAndRegrowCannotRevealStaleBits() {
    ColumnBitSet bits = new ColumnBitSet();
    assertEquals(StatusCode.OK, bits.reserve(1_664, 1_664));
    assertEquals(StatusCode.OK, bits.clearForSize(1_664));
    assertTrue(bits.set(7));
    assertTrue(bits.set(63));
    assertTrue(bits.set(64));
    assertTrue(bits.set(1_023));
    assertTrue(bits.set(1_663));

    int capacity = bits.capacity();
    assertEquals(StatusCode.OK, bits.clearForSize(8));
    assertTrue(bits.isEmpty());
    assertEquals(StatusCode.OK, bits.clearForSize(1_664));
    assertEquals(capacity, bits.capacity());
    assertTrue(bits.isEmpty());
    assertFalse(bits.get(7));
    assertFalse(bits.get(63));
    assertFalse(bits.get(64));
    assertFalse(bits.get(1_023));
    assertFalse(bits.get(1_663));
  }

  @Test
  void invalidAndOutOfBoundsOperationsDoNotChangeState() {
    ColumnBitSet bits = new ColumnBitSet();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.reserve(-1, 8));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.reserve(1, -1));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, bits.reserve(9, 8));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, bits.clearForSize(1));

    assertEquals(StatusCode.OK, bits.reserve(8, 8));
    assertEquals(StatusCode.OK, bits.clearForSize(8));
    assertFalse(bits.get(-1));
    assertFalse(bits.get(8));
    assertFalse(bits.set(-1));
    assertFalse(bits.set(8));
    assertFalse(bits.clear(-1));
    assertFalse(bits.clear(8));
    assertEquals(0, bits.word(-1));
    assertEquals(0, bits.word(1));
    assertFalse(bits.setWord(-1, 1));
    assertFalse(bits.setWord(1, 1));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.clearForSize(-1));
    assertEquals(8, bits.bitCount());
  }

  @Test
  void reserveRejectsMaximumBelowTheExistingLogicalSizeWithoutChangingState() {
    ColumnBitSet bits = new ColumnBitSet();
    assertEquals(StatusCode.OK, bits.reserve(65, 65));
    assertEquals(StatusCode.OK, bits.clearForSize(65));
    assertTrue(bits.set(64));
    int capacity = bits.capacity();

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, bits.reserve(8, 64));
    assertEquals(65, bits.bitCount());
    assertEquals(capacity, bits.capacity());
    assertTrue(bits.get(64));
  }

  @Test
  void bitmapEncodingRoundTripsEveryByteAndWordBoundary() {
    int[] sizes = {0, 1, 7, 8, 9, 63, 64, 65, 1_664};
    for (int size : sizes) {
      ColumnBitSet source = bitSet(size, 1_664);
      if (size > 0) {
        assertTrue(source.set(0));
        assertTrue(source.set(size - 1));
      }
      if (size > 8) assertTrue(source.set(8));
      if (size > 63) assertTrue(source.set(63));

      int encodedBytes = (size + 7) >>> 3;
      assertEquals(encodedBytes, source.encodedByteCount());
      byte[] encoded = new byte[encodedBytes + 2];
      encoded[0] = 0x55;
      encoded[encoded.length - 1] = 0x55;
      assertEquals(StatusCode.OK, source.encode(encoded, 1, encodedBytes));
      assertEquals(0x55, encoded[0]);
      assertEquals(0x55, encoded[encoded.length - 1]);
      if (size > 0) assertNotEquals(0, encoded[1] & 1);
      if (size > 8) assertNotEquals(0, encoded[2] & 1);

      ColumnBitSet decoded = bitSet(1_664, 1_664);
      assertTrue(decoded.set(1_663));
      assertEquals(StatusCode.OK, decoded.decode(encoded, 1, encodedBytes, size, 1_664));
      assertEquals(size, decoded.bitCount());
      assertEquals(source.wordCount(), decoded.wordCount());
      for (int bit = 0; bit < size; bit++) {
        assertEquals(source.get(bit), decoded.get(bit), "bit " + bit + " at size " + size);
      }
    }
  }

  @Test
  void bitmapDecodeRejectsTrailingBitsAndPreservesPriorState() {
    ColumnBitSet bits = bitSet(65, 1_664);
    assertTrue(bits.set(0));
    assertTrue(bits.set(64));
    int capacity = bits.capacity();

    byte[] malformedOneBit = {2};
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT, bits.decode(malformedOneBit, 0, 1, 1, 1_664));
    assertBitStatePreserved(bits, capacity);

    byte[] malformedNineBits = {0, 2};
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT, bits.decode(malformedNineBits, 0, 2, 9, 1_664));
    assertBitStatePreserved(bits, capacity);
  }

  @Test
  void bitmapCodecRejectsInvalidOrShortRegionsWithoutChangingState() {
    ColumnBitSet bits = bitSet(65, 65);
    assertTrue(bits.set(0));
    assertTrue(bits.set(64));
    int capacity = bits.capacity();
    byte[] bytes = new byte[9];

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.encode(null, 0, 9));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.encode(bytes, 0, 8));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.encode(bytes, 1, 9));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.encode(bytes, -1, 9));
    assertBitStatePreserved(bits, capacity);

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.decode(null, 0, 9, 65, 65));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.decode(bytes, 0, 8, 65, 65));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.decode(bytes, 1, 9, 65, 65));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.decode(bytes, 0, 9, -1, 65));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, bits.decode(bytes, 0, 9, 65, -1));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, bits.decode(bytes, 0, 9, 65, 64));
    assertBitStatePreserved(bits, capacity);
  }

  @Test
  void warmedBitmapCodecDoesNotAllocate() {
    ThreadMXBean bean = allocationBean();
    ColumnBitSet source = bitSet(1_664, 1_664);
    ColumnBitSet decoded = bitSet(1_664, 1_664);
    assertTrue(source.set(0));
    assertTrue(source.set(63));
    assertTrue(source.set(64));
    assertTrue(source.set(1_663));
    byte[] encoded = new byte[source.encodedByteCount()];

    exerciseCodec(source, decoded, encoded, 10_000);
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    exerciseCodec(source, decoded, encoded, 100_000);
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertTrue(allocated <= 256, "warmed bitmap codec allocated: " + allocated);
  }

  @Test
  void warmedBitOperationsDoNotAllocate() {
    ThreadMXBean bean = allocationBean();
    ColumnBitSet bits = new ColumnBitSet();
    assertEquals(StatusCode.OK, bits.reserve(1_664, 1_664));
    assertEquals(StatusCode.OK, bits.clearForSize(1_664));

    exercise(bits, 100_000);
    long threadId = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(threadId);
    exercise(bits, 1_000_000);
    long allocated = bean.getThreadAllocatedBytes(threadId) - before;

    assertTrue(allocated <= 256, "warmed column bit operations allocated: " + allocated);
  }

  private static void exercise(ColumnBitSet bits, int iterations) {
    for (int index = 0; index < iterations; index++) {
      int bit = index & 1_663;
      bits.set(bit);
      allocationGuard += bits.get(bit) ? bits.word(bit >>> 6) : 0;
      bits.clear(bit);
    }
  }

  private static void exerciseCodec(
      ColumnBitSet source, ColumnBitSet decoded, byte[] encoded, int iterations) {
    for (int index = 0; index < iterations; index++) {
      allocationGuard += source.encode(encoded, 0, encoded.length).stableCode();
      allocationGuard +=
          decoded.decode(encoded, 0, encoded.length, 1_664, 1_664).stableCode();
      allocationGuard += decoded.word(index & 15);
    }
  }

  private static ColumnBitSet bitSet(int size, int maximum) {
    ColumnBitSet bits = new ColumnBitSet();
    assertEquals(StatusCode.OK, bits.reserve(size, maximum));
    assertEquals(StatusCode.OK, bits.clearForSize(size));
    return bits;
  }

  private static void assertBitStatePreserved(ColumnBitSet bits, int capacity) {
    assertEquals(65, bits.bitCount());
    assertEquals(capacity, bits.capacity());
    assertTrue(bits.get(0));
    assertTrue(bits.get(64));
  }

  private static ThreadMXBean allocationBean() {
    java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
    Assumptions.assumeTrue(standardBean instanceof ThreadMXBean);
    ThreadMXBean bean = (ThreadMXBean) standardBean;
    Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    return bean;
  }
}
