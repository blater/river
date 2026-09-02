package io.riverdb.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.collection.IntRangeList;
import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import java.util.BitSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class ReusableShapePrimitivesPropertyTest {
  @Test
  void randomizedBitmapOperationsAndCodecMatchJdkBitSet() {
    int[] sizes = {0, 1, 7, 8, 9, 63, 64, 65, 127, 255, 511, 1_023, 1_664};
    Random random = new Random(0x52495645524cL);
    for (int size : sizes) {
      ColumnBitSet actual = new ColumnBitSet();
      assertEquals(StatusCode.OK, actual.reserve(size, 1_664));
      assertEquals(StatusCode.OK, actual.clearForSize(size));
      BitSet expected = new BitSet(size);
      for (int operation = 0; operation < 4_096 && size > 0; operation++) {
        int bit = random.nextInt(size);
        if (random.nextBoolean()) {
          assertTrue(actual.set(bit));
          expected.set(bit);
        } else {
          assertTrue(actual.clear(bit));
          expected.clear(bit);
        }
      }
      assertBitsEqual(expected, actual, size);

      byte[] encoded = new byte[actual.encodedByteCount()];
      assertEquals(StatusCode.OK, actual.encode(encoded, 0, encoded.length));
      ColumnBitSet decoded = new ColumnBitSet();
      assertEquals(StatusCode.OK, decoded.decode(encoded, 0, encoded.length, size, 1_664));
      assertBitsEqual(expected, decoded, size);
    }
  }

  @Test
  void randomizedCompletedRangesMatchFlatReference() {
    Random random = new Random(0x52414e474553L);
    IntRangeList ranges = new IntRangeList();
    int[] expectedValues = new int[1_024];
    int[] expectedStarts = new int[64];
    int[] expectedCounts = new int[64];
    int valueCount = 0;

    for (int range = 0; range < expectedStarts.length; range++) {
      assertEquals(StatusCode.OK, ranges.beginRange(expectedStarts.length));
      assertEquals(valueCount, ranges.valueCount());
      expectedStarts[range] = valueCount;
      int count = random.nextInt(17);
      for (int index = 0; index < count; index++) {
        int value = random.nextInt();
        assertEquals(StatusCode.OK, ranges.append(value, expectedValues.length));
        expectedValues[valueCount++] = value;
      }
      expectedCounts[range] = count;
      assertEquals(StatusCode.OK, ranges.endRange());
    }

    assertEquals(expectedStarts.length, ranges.rangeCount());
    assertEquals(valueCount, ranges.valueCount());
    for (int range = 0; range < expectedStarts.length; range++) {
      assertEquals(expectedStarts[range], ranges.rangeStart(range));
      assertEquals(expectedCounts[range], ranges.rangeCount(range));
    }
    for (int index = 0; index < valueCount; index++) {
      assertTrue(ranges.hasValue(index));
      assertEquals(expectedValues[index], ranges.valueAt(index));
    }
  }

  private static void assertBitsEqual(BitSet expected, ColumnBitSet actual, int size) {
    assertEquals(size, actual.bitCount());
    for (int bit = 0; bit < size; bit++) {
      assertEquals(expected.get(bit), actual.get(bit), "bit " + bit + " of " + size);
    }
  }
}
