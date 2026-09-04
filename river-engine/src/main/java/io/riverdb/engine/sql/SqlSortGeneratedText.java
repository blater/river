package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Retained generated-text lanes for one in-memory sort run. */
final class SqlSortGeneratedText {
  private final SqlRetainedArrayAllocator allocator;
  private byte[] lengths;
  private char[] text;

  SqlSortGeneratedText(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
  }

  StatusCode reserve(int rows, int projections, boolean requiredText) {
    if (!requiredText) return StatusCode.OK;
    long requiredLong = (long) rows * projections;
    if (requiredLong > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    int required = (int) requiredLong;
    int maximum = Integer.MAX_VALUE;
    int initial = rows;
    int capacity = BoundedArrayGrowth.capacity(
        lengths == null ? 0 : lengths.length, required, maximum, initial);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (lengths != null && capacity == lengths.length) return StatusCode.OK;
    try {
      byte[] nextLengths = allocator.bytes(capacity);
      long characters = (long) capacity * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
      if (characters > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
      char[] nextText = allocator.characters((int) characters);
      lengths = nextLengths;
      text = nextText;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void copyFrom(int row, int projections, SqlProjectedRow source) {
    int laneStart = row * projections;
    for (int projection = 0; projection < projections; projection++) {
      int length = source.textLength(projection);
      int lane = laneStart + projection;
      lengths[lane] = (byte) length;
      int start = lane * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
      for (int index = 0; index < length; index++) {
        text[start + index] = source.textCharacter(projection, index);
      }
    }
  }

  StatusCode setResult(SqlScanRowResult target, int row, int projections) {
    int laneStart = row * projections;
    for (int projection = 0; projection < projections; projection++) {
      int length = Byte.toUnsignedInt(lengths[laneStart + projection]);
      if (length == 0) continue;
      StatusCode status = target.setTextAt(
          projection,
          text,
          (laneStart + projection) * SqlProjectedRow.MAXIMUM_GENERATED_TEXT,
          length);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode setResult(
      SqlScanRowResult target,
      int row,
      int projections,
      boolean spilled,
      SqlSortSpill spill) {
    if (!spilled) return setResult(target, row, projections);
    for (int projection = 0; projection < projections; projection++) {
      int length = spill.outputTextLength(projection);
      if (length == 0) continue;
      StatusCode status = target.setTextAt(
          projection, spill.outputText(), spill.outputTextOffset(projection), length);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  void copyTo(SqlProjectedRow target, int row, int projections) {
    int laneStart = row * projections;
    for (int projection = 0; projection < projections; projection++) {
      int length = Byte.toUnsignedInt(lengths[laneStart + projection]);
      if (length == 0) continue;
      int start = (laneStart + projection) * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
      char[] output = target.text(projection);
      for (int index = 0; index < length; index++) output[index] = text[start + index];
      target.setText(projection, output, length);
    }
  }

  void copyTo(
      SqlProjectedRow target,
      int row,
      int projections,
      boolean spilled,
      SqlSortSpill spill) {
    if (!spilled) {
      copyTo(target, row, projections);
      return;
    }
    for (int projection = 0; projection < projections; projection++) {
      int length = spill.outputTextLength(projection);
      if (length > 0) {
        target.setText(
            projection, spill.outputText(), spill.outputTextOffset(projection), length);
      }
    }
  }

  void swap(int left, int right, int projections) {
    int leftStart = left * projections;
    int rightStart = right * projections;
    for (int projection = 0; projection < projections; projection++) {
      int leftLane = leftStart + projection;
      int rightLane = rightStart + projection;
      byte length = lengths[leftLane];
      lengths[leftLane] = lengths[rightLane];
      lengths[rightLane] = length;
      swapText(leftLane, rightLane);
    }
  }

  byte[] lengths() { return lengths; }
  char[] text() { return text; }

  long retainedBytes() {
    return (lengths == null ? 0 : lengths.length)
        + (text == null ? 0 : (long) text.length * Character.BYTES);
  }

  long requiredBytes(int rows, int projections, boolean requiredText) {
    if (!requiredText) return retainedBytes();
    long requiredLong = (long) rows * projections;
    if (requiredLong > Integer.MAX_VALUE) return Long.MAX_VALUE;
    int required = (int) requiredLong;
    int maximum = Integer.MAX_VALUE;
    int initial = rows;
    int capacity = BoundedArrayGrowth.capacity(
        lengths == null ? 0 : lengths.length, required, maximum, initial);
    long characters = (long) capacity * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
    return capacity < 0 || characters > Integer.MAX_VALUE ? Long.MAX_VALUE
        : (long) capacity * (1 + Character.BYTES * SqlProjectedRow.MAXIMUM_GENERATED_TEXT);
  }

  static long cleanRequiredBytes(int rows, int projections, boolean requiredText) {
    if (!requiredText) return 0;
    long requiredLong = (long) rows * projections;
    if (requiredLong > Integer.MAX_VALUE) return Long.MAX_VALUE;
    int capacity = BoundedArrayGrowth.capacity(
        0, (int) requiredLong, Integer.MAX_VALUE, rows);
    long characters = (long) capacity * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
    return capacity < 0 || characters > Integer.MAX_VALUE ? Long.MAX_VALUE
        : (long) capacity * (1 + Character.BYTES * SqlProjectedRow.MAXIMUM_GENERATED_TEXT);
  }

  void release() {
    lengths = null;
    text = null;
  }

  private void swapText(int leftLane, int rightLane) {
    int left = leftLane * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
    int right = rightLane * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
    for (int index = 0; index < SqlProjectedRow.MAXIMUM_GENERATED_TEXT; index++) {
      char character = text[left + index];
      text[left + index] = text[right + index];
      text[right + index] = character;
    }
  }
}
