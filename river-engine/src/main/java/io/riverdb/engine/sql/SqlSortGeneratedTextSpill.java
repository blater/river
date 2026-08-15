package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableSchema;
import java.nio.ByteBuffer;

/** Owns the optional fixed-lane generated-text portion of sort spill records. */
final class SqlSortGeneratedTextSpill {
  static final int MAXIMUM_RECORD_BYTES =
      TableSchema.MAXIMUM_COLUMNS * (1 + SqlProjectedRow.MAXIMUM_GENERATED_TEXT);
  private static final int MAXIMUM_RUNS = 64;
  private static final int LANE_BYTES = 1 + SqlProjectedRow.MAXIMUM_GENERATED_TEXT;

  private byte[] mergeLengths;
  private char[] mergeCharacters;
  private byte[] outputLengths;
  private char[][] outputCharacters;
  private int projectionCount;
  private boolean enabled;

  void begin(boolean generatedText, int projections) {
    enabled = generatedText;
    projectionCount = projections;
    if (!enabled || mergeLengths != null) return;
    mergeLengths = new byte[MAXIMUM_RUNS * TableSchema.MAXIMUM_COLUMNS];
    mergeCharacters = new char[
        MAXIMUM_RUNS * TableSchema.MAXIMUM_COLUMNS
            * SqlProjectedRow.MAXIMUM_GENERATED_TEXT];
    outputLengths = new byte[TableSchema.MAXIMUM_COLUMNS];
    outputCharacters = new char[
        TableSchema.MAXIMUM_COLUMNS][SqlProjectedRow.MAXIMUM_GENERATED_TEXT];
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
    int laneStart = row * TableSchema.MAXIMUM_COLUMNS;
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
    int laneStart = run * TableSchema.MAXIMUM_COLUMNS;
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

  void capture(int run) {
    if (!enabled) return;
    int laneStart = run * TableSchema.MAXIMUM_COLUMNS;
    for (int projection = 0; projection < projectionCount; projection++) {
      int lane = laneStart + projection;
      int length = Byte.toUnsignedInt(mergeLengths[lane]);
      outputLengths[projection] = (byte) length;
      int textStart = lane * SqlProjectedRow.MAXIMUM_GENERATED_TEXT;
      for (int index = 0; index < length; index++) {
        outputCharacters[projection][index] = mergeCharacters[textStart + index];
      }
    }
  }

  int outputLength(int projection) {
    return enabled ? Byte.toUnsignedInt(outputLengths[projection]) : 0;
  }

  char[] output(int projection) {
    return outputCharacters[projection];
  }
}
