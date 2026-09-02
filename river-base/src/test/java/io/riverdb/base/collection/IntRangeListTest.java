package io.riverdb.base.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class IntRangeListTest {
  @Test
  void storesGrowingRangesInOneFlatPrimitiveArray() {
    IntRangeList ranges = new IntRangeList();
    assertEquals(StatusCode.OK, ranges.beginRange(4));
    assertEquals(StatusCode.OK, ranges.append(10, 8));
    assertEquals(StatusCode.OK, ranges.append(11, 8));
    assertEquals(StatusCode.OK, ranges.endRange());
    assertEquals(StatusCode.OK, ranges.beginRange(4));
    assertEquals(StatusCode.OK, ranges.append(20, 8));
    assertEquals(StatusCode.OK, ranges.endRange());

    assertEquals(2, ranges.rangeCount());
    assertEquals(3, ranges.valueCount());
    assertEquals(0, ranges.rangeStart(0));
    assertEquals(2, ranges.rangeCount(0));
    assertEquals(2, ranges.rangeStart(1));
    assertEquals(1, ranges.rangeCount(1));
    assertEquals(11, ranges.valueAt(1));
    assertEquals(20, ranges.valueAt(2));
  }

  @Test
  void cancellationAndFailedAppendPreserveCompletedRanges() {
    IntRangeList ranges = new IntRangeList();
    assertEquals(StatusCode.OK, ranges.beginRange(2));
    assertEquals(StatusCode.OK, ranges.append(7, 2));
    assertEquals(StatusCode.OK, ranges.endRange());
    int values = ranges.valueCount();
    assertEquals(StatusCode.OK, ranges.beginRange(2));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, ranges.append(8, values));
    assertEquals(values, ranges.valueCount());
    assertEquals(StatusCode.OK, ranges.cancelRange());
    assertEquals(1, ranges.rangeCount());
    assertEquals(values, ranges.valueCount());
    assertEquals(7, ranges.valueAt(0));
  }

  @Test
  void openRangeIsInvisibleAndMinimumValueIsUnambiguous() {
    IntRangeList ranges = new IntRangeList();
    assertEquals(StatusCode.OK, ranges.beginRange(1));
    assertEquals(StatusCode.OK, ranges.append(Integer.MIN_VALUE, 1));
    assertEquals(0, ranges.valueCount());
    assertEquals(false, ranges.hasValue(0));
    assertEquals(Integer.MIN_VALUE, ranges.valueAt(0));

    assertEquals(StatusCode.OK, ranges.endRange());
    assertEquals(1, ranges.valueCount());
    assertEquals(true, ranges.hasValue(0));
    assertEquals(Integer.MIN_VALUE, ranges.valueAt(0));
  }

  @Test
  void rejectsInvalidBoundariesAndResetRetainsCapacity() {
    IntRangeList ranges = new IntRangeList();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, ranges.beginRange(-1));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, ranges.append(1, 1));
    assertEquals(StatusCode.OK, ranges.beginRange(1));
    assertEquals(StatusCode.OK, ranges.endRange());
    int valueCapacity = ranges.valueCapacity();
    int rangeCapacity = ranges.rangeCapacity();
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, ranges.beginRange(1));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, ranges.endRange());
    ranges.reset();
    assertEquals(0, ranges.rangeCount());
    assertEquals(0, ranges.valueCount());
    assertEquals(valueCapacity, ranges.valueCapacity());
    assertEquals(rangeCapacity, ranges.rangeCapacity());
    assertEquals(StatusCode.OK, ranges.beginRange(1));
    assertEquals(StatusCode.OK, ranges.append(3, 1));
  }
}
