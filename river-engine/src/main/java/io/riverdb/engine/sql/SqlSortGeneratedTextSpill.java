package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import java.nio.ByteBuffer;

/** Owns the canonical generated-text record fields and one decoded output row. */
final class SqlSortGeneratedTextSpill {
  private static final int LANE_BYTES = 1 + SqlProjectedRow.MAXIMUM_GENERATED_TEXT;

  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  private byte[] outputLengths;
  private char[] outputCharacters;
  private int projectionCount;
  private int projectionCapacity;
  private long retainedBytes;
  private boolean enabled;

  SqlSortGeneratedTextSpill(
      SqlRetainedArrayAllocator retainedAllocator, SqlSessionShapeBudget shapeBudget) {
    allocator = retainedAllocator;
    budget = shapeBudget;
  }

  StatusCode begin(boolean generatedText, int projections) {
    if (!generatedText) {
      enabled = false;
      projectionCount = projections;
      return StatusCode.OK;
    }
    int capacity = BoundedArrayGrowth.capacity(
        projectionCapacity, projections, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == projectionCapacity) {
      enabled = true;
      projectionCount = projections;
      return StatusCode.OK;
    }
    long characters = (long) capacity * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
    if (characters > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    long targetBytes = capacity + characters * Character.BYTES;
    long delta = targetBytes - retainedBytes;
    StatusCode status = budget == null ? StatusCode.OK : budget.reserve(delta);
    if (!status.isOk()) return status;
    try {
      byte[] nextLengths = allocator.bytes(capacity);
      char[] nextCharacters = allocator.characters((int) characters);
      outputLengths = nextLengths;
      outputCharacters = nextCharacters;
      projectionCapacity = capacity;
      projectionCount = projections;
      retainedBytes = targetBytes;
      enabled = true;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      if (budget != null) budget.rollback(delta);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  int recordBytes() {
    return enabled ? projectionCount * LANE_BYTES : 0;
  }

  static int recordBytes(boolean generatedText, int projections) {
    long bytes = generatedText ? (long) projections * LANE_BYTES : 0;
    return bytes > Integer.MAX_VALUE ? -1 : (int) bytes;
  }

  void write(ByteBuffer record, int row, byte[] textLengths, char[] text) {
    if (!enabled) return;
    int laneStart = row * projectionCount;
    for (int projection = 0; projection < projectionCount; projection++) {
      int lane = laneStart + projection;
      int length = Byte.toUnsignedInt(textLengths[lane]);
      record.put((byte) length);
      int textStart = lane * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
      for (int index = 0; index < SqlProjectedRow.MAXIMUM_GENERATED_TEXT; index++) {
        record.put((byte) (index < length ? text[textStart + index] : 0));
      }
    }
  }

  StatusCode readOutput(ByteBuffer record) {
    if (!enabled) return StatusCode.OK;
    for (int projection = 0; projection < projectionCount; projection++) {
      int length = Byte.toUnsignedInt(record.get());
      if (length > SqlProjectedRow.MAXIMUM_GENERATED_TEXT) return StatusCode.CORRUPTION;
      outputLengths[projection] = (byte) length;
      int textStart = projection * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
      for (int index = 0; index < SqlProjectedRow.MAXIMUM_GENERATED_TEXT; index++) {
        int character = Byte.toUnsignedInt(record.get());
        if (index < length && (character < 0x20 || character > 0x7e)) {
          return StatusCode.CORRUPTION;
        }
        outputCharacters[textStart + index] = (char) character;
      }
    }
    return StatusCode.OK;
  }

  void skip(ByteBuffer record) {
    record.position(record.position() + recordBytes());
  }

  int outputLength(int projection) {
    return enabled ? Byte.toUnsignedInt(outputLengths[projection]) : 0;
  }

  char[] output() { return outputCharacters; }

  int outputOffset(int projection) {
    return projection * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
  }

  long retainedBytes() { return retainedBytes; }

  long requiredBytes(boolean generatedText, int projections) {
    if (!generatedText) return retainedBytes;
    int capacity = BoundedArrayGrowth.capacity(
        projectionCapacity, projections, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    long characters = (long) capacity * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
    return capacity < 0 || characters > Integer.MAX_VALUE ? Long.MAX_VALUE
        : capacity + characters * Character.BYTES;
  }

  static long cleanRequiredBytes(boolean generatedText, int projections) {
    if (!generatedText) return 0;
    int capacity = BoundedArrayGrowth.capacity(
        0, projections, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    long characters = (long) capacity * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
    return capacity < 0 || characters > Integer.MAX_VALUE ? Long.MAX_VALUE
        : capacity + characters * Character.BYTES;
  }

  void releaseRetainedStorage() {
    outputLengths = null;
    outputCharacters = null;
    projectionCapacity = 0;
    retainedBytes = 0;
    enabled = false;
  }
}
