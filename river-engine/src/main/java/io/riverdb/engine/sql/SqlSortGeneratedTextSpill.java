package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import java.nio.ByteBuffer;

/** Owns the optional fixed-lane generated-text portion of sort spill records. */
final class SqlSortGeneratedTextSpill {
  private static final int MERGE_SLOTS = 2;
  private static final int LANE_BYTES = 1 + SqlProjectedRow.MAXIMUM_GENERATED_TEXT;

  private final SqlRetainedArrayAllocator allocator;
  private byte[] mergeLengths;
  private char[] mergeCharacters;
  private byte[] outputLengths;
  private char[] outputCharacters;
  private int projectionCount;
  private int projectionCapacity;
  private boolean enabled;

  SqlSortGeneratedTextSpill() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlSortGeneratedTextSpill(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
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
    try {
      byte[] nextMergeLengths = allocator.bytes(MERGE_SLOTS * capacity);
      char[] nextMergeCharacters = allocator.characters(
          MERGE_SLOTS * capacity * SqlProjectedRow.MAXIMUM_GENERATED_TEXT);
      byte[] nextOutputLengths = allocator.bytes(capacity);
      char[] nextOutputCharacters = allocator.characters(
          capacity * SqlProjectedRow.MAXIMUM_GENERATED_TEXT);
      mergeLengths = nextMergeLengths;
      mergeCharacters = nextMergeCharacters;
      outputLengths = nextOutputLengths;
      outputCharacters = nextOutputCharacters;
      projectionCapacity = capacity;
      projectionCount = projections;
      enabled = true;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  int recordBytes() {
    return enabled ? projectionCount * LANE_BYTES : 0;
  }

  void write(
      ByteBuffer record,
      int row,
      byte[] textLengths,
      char[] text) {
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

  StatusCode read(ByteBuffer record, int run) {
    if (!enabled) return StatusCode.OK;
    int laneStart = run * projectionCapacity;
    for (int projection = 0; projection < projectionCount; projection++) {
      int lane = laneStart + projection;
      int length = Byte.toUnsignedInt(record.get());
      if (length > SqlProjectedRow.MAXIMUM_GENERATED_TEXT) {
        return StatusCode.CORRUPTION;
      }
      mergeLengths[lane] = (byte) length;
      int textStart = lane * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
      for (int index = 0; index < SqlProjectedRow.MAXIMUM_GENERATED_TEXT; index++) {
        int character = Byte.toUnsignedInt(record.get());
        if (index < length && (character < 0x20 || character > 0x7e)) {
          return StatusCode.CORRUPTION;
        }
        mergeCharacters[textStart + index] = (char) character;
      }
    }
    return StatusCode.OK;
  }

  void skip(ByteBuffer record) {
    record.position(record.position() + recordBytes());
  }

  void writeSlot(ByteBuffer record, int slot) {
    if (!enabled) return;
    int laneStart = slot * projectionCapacity;
    for (int projection = 0; projection < projectionCount; projection++) {
      int lane = laneStart + projection;
      int length = Byte.toUnsignedInt(mergeLengths[lane]);
      record.put((byte) length);
      int textStart = lane * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
      for (int index = 0; index < SqlProjectedRow.MAXIMUM_GENERATED_TEXT; index++) {
        record.put((byte) (index < length ? mergeCharacters[textStart + index] : 0));
      }
    }
  }

  void capture(int run) {
    if (!enabled) return;
    int laneStart = run * projectionCapacity;
    for (int projection = 0; projection < projectionCount; projection++) {
      int lane = laneStart + projection;
      int length = Byte.toUnsignedInt(mergeLengths[lane]);
      outputLengths[projection] = (byte) length;
      int textStart = lane * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
      for (int index = 0; index < length; index++) {
        outputCharacters[
            projection * SqlProjectedRow.MAXIMUM_GENERATED_TEXT + index] =
            mergeCharacters[textStart + index];
      }
    }
  }

  int outputLength(int projection) {
    return enabled ? Byte.toUnsignedInt(outputLengths[projection]) : 0;
  }

  char[] output() { return outputCharacters; }

  int outputOffset(int projection) {
    return projection * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
  }
}
