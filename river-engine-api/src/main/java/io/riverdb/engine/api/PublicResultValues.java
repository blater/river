package io.riverdb.engine.api;

import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.text.Utf8TextArena;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Reusable packed values shared by command and streaming-row result carriers. */
final class PublicResultValues {
  private static final char[] EMPTY_CHARACTERS = new char[0];
  private static final int RETAINED_COLUMN_FLOOR = 16;
  private static final int RETAINED_TEXT_FLOOR = 4 * 1024;
  private static final int BYTES_PER_COLUMN = Long.BYTES * 2 + Integer.BYTES * 3;
  private final PublicResultArrays lanes = new PublicResultArrays();
  private final ColumnBitSet nulls = new ColumnBitSet();
  private final Utf8TextArena text = new Utf8TextArena();
  private final RetainedMemoryLease memory;
  private char[] characterScratch = EMPTY_CHARACTERS;
  private int count;

  PublicResultValues() {
    this(RetainedMemoryLease.unbounded());
  }

  PublicResultValues(RetainedMemoryLease retainedMemory) {
    if (retainedMemory == null) throw new IllegalArgumentException("retainedMemory");
    memory = retainedMemory;
  }

  StatusCode reserve(int columns, int textBytes) {
    if (columns < 0 || textBytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (columns > SqlShapeLimits.MAX_RESULT_COLUMNS
        || textBytes > SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int laneCapacity = growth(lanes.capacity(), columns, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    int nullBits = growth(nulls.capacity(), columns, SqlShapeLimits.MAX_RESULT_COLUMNS, Long.SIZE);
    int textCapacity = growth(
        text.capacity(), textBytes, SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES, 8);
    StatusCode admitted = memory.resize(retainedBytes(
        laneCapacity, nullBits, textCapacity, characterScratch.length));
    if (!admitted.isOk()) return admitted;
    StatusCode status = lanes.reserve(columns, count);
    if (!status.isOk()) {
      memory.resize(currentRetainedBytes());
      return status;
    }
    status = nulls.reserve(columns, SqlShapeLimits.MAX_RESULT_COLUMNS);
    if (status.isOk()) {
      status = text.reserve(textBytes, SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES);
    }
    if (!status.isOk()) memory.resize(currentRetainedBytes());
    return status;
  }

  StatusCode beginLegacy(long[] sourceValues, long nullMask, int[] sourceDescriptors, int columns) {
    if (!PublicResultInput.legacy(sourceValues, nullMask, sourceDescriptors, columns)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = prepare(columns);
    if (!status.isOk()) return status;
    if (columns != 0 && !nulls.setWord(0, nullMask)) return StatusCode.INVARIANT_BROKEN;
    lanes.copy(sourceValues, sourceDescriptors, columns);
    return StatusCode.OK;
  }

  StatusCode begin(
      long[] sourceValues,
      long[] sourceNullWords,
      int sourceNullWordCount,
      int[] sourceDescriptors,
      int columns) {
    int requiredWords = PublicResultInput.wordCount(columns);
    if (!PublicResultInput.words(
        sourceValues, sourceNullWords, sourceNullWordCount, sourceDescriptors, columns)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = prepare(columns);
    if (!status.isOk()) return status;
    for (int word = 0; word < requiredWords; word++) {
      if (!nulls.setWord(word, sourceNullWords[word])) return StatusCode.INVARIANT_BROKEN;
    }
    lanes.copy(sourceValues, sourceDescriptors, columns);
    return StatusCode.OK;
  }

  StatusCode begin(
      long[] sourceHighValues,
      long[] sourceValues,
      long[] sourceNullWords,
      int sourceNullWordCount,
      int[] sourceDescriptors,
      int columns) {
    int requiredWords = PublicResultInput.wordCount(columns);
    if (!PublicResultInput.words(
        sourceHighValues, sourceValues, sourceNullWords, sourceNullWordCount,
        sourceDescriptors, columns)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = prepare(columns);
    if (!status.isOk()) return status;
    for (int word = 0; word < requiredWords; word++) {
      if (!nulls.setWord(word, sourceNullWords[word])) return StatusCode.INVARIANT_BROKEN;
    }
    lanes.copy(sourceHighValues, sourceValues, sourceDescriptors, columns);
    return StatusCode.OK;
  }

  void reset() {
    lanes.reset(count);
    nulls.reset();
    text.reset();
    count = 0;
  }

  StatusCode setText(int index, char[] source, int offset, int length) {
    if (!isText(index) || isNull(index) || source == null || offset < 0 || length < 0
        || offset > source.length - length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int maximumScalars = SqlTypeDescriptor.parameterOne(lanes.descriptor(index));
    int scalars = Utf8Text.scalarCount(source, offset, length);
    if (scalars < 0 || scalars > maximumScalars) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = reserveScratch(length);
    if (!status.isOk()) return status;
    status = text.append(source, offset, length, maximumScalars);
    if (!status.isOk()) return status;
    lanes.text(index, text.lastOffset(), text.lastLength());
    return StatusCode.OK;
  }

  int count() { return count; }

  long valueAt(int index) { return validIndex(index) ? lanes.value(index) : 0; }
  long decimalHighAt(int index) {
    return validIndex(index) ? lanes.decimalHigh(index) : 0;
  }

  boolean isNull(int index) { return validIndex(index) && nulls.get(index); }

  long nullWord(int word) { return nulls.word(word); }

  int nullWordCount() { return nulls.wordCount(); }

  boolean isText(int index) {
    return validIndex(index)
        && SqlTypeDescriptor.typeId(lanes.descriptor(index)) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  int descriptorAt(int index) { return validIndex(index) ? lanes.descriptor(index) : 0; }

  int textLengthAt(int index) {
    if (!isText(index) || isNull(index)) return -1;
    return text.copyChars(
        lanes.textOffset(index), lanes.textLength(index), characterScratch, 0);
  }

  int copyTextAt(int index, char[] destination, int offset) {
    int bytes = isText(index) && !isNull(index) ? lanes.textLength(index) : -1;
    return bytes < 0
        ? -1 : text.copyChars(lanes.textOffset(index), bytes, destination, offset);
  }

  char textCharacterAt(int index, int character) {
    int length = textLengthAt(index);
    return character >= 0 && character < length ? characterScratch[character] : 0;
  }

  StatusCode releaseHighWater() {
    reset();
    int lanesToKeep = Math.min(lanes.capacity(), RETAINED_COLUMN_FLOOR);
    int textToKeep = Math.min(text.capacity(), RETAINED_TEXT_FLOOR);
    int scratchToKeep = Math.min(characterScratch.length, RETAINED_TEXT_FLOOR);
    if (lanes.capacity() > lanesToKeep) lanes.release();
    if (nulls.capacity() > lanesToKeep) nulls.release();
    if (text.capacity() > textToKeep) text.release();
    if (characterScratch.length > scratchToKeep) characterScratch = new char[scratchToKeep];
    StatusCode status = reserve(lanesToKeep, textToKeep);
    if (!status.isOk()) return status;
    return memory.resize(currentRetainedBytes());
  }

  StatusCode release() {
    reset();
    lanes.release();
    nulls.release();
    text.release();
    characterScratch = EMPTY_CHARACTERS;
    return memory.resize(0);
  }

  long retainedBytes() { return memory.retainedBytes(); }
  static long maximumRetainedBytes() {
    return retainedBytes(
        SqlShapeLimits.MAX_RESULT_COLUMNS,
        SqlShapeLimits.MAX_RESULT_COLUMNS,
        SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES,
        CommandResult.MAXIMUM_TEXT_CHARACTERS);
  }
  static long retainedFloorBytes() {
    return retainedBytes(
        RETAINED_COLUMN_FLOOR,
        Long.SIZE,
        RETAINED_TEXT_FLOOR,
        RETAINED_TEXT_FLOOR);
  }

  private StatusCode prepare(int columns) {
    StatusCode status = reserve(columns, 0);
    if (!status.isOk()) return status;
    reset();
    status = nulls.clearForSize(columns);
    if (status.isOk()) count = columns;
    return status;
  }

  private boolean validIndex(int index) { return index >= 0 && index < count; }

  private StatusCode reserveScratch(int required) {
    if (required <= characterScratch.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        characterScratch.length, required, CommandResult.MAXIMUM_TEXT_CHARACTERS, 8);
    StatusCode admitted = memory.resize(retainedBytes(
        lanes.capacity(), nulls.capacity(), text.capacity(), capacity));
    if (!admitted.isOk()) return admitted;
    try {
      characterScratch = java.util.Arrays.copyOf(characterScratch, capacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resize(currentRetainedBytes());
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private long currentRetainedBytes() {
    return retainedBytes(
        lanes.capacity(), nulls.capacity(), text.capacity(), characterScratch.length);
  }

  private static long retainedBytes(int columns, int nullBits, int textBytes, int characters) {
    return (long) columns * BYTES_PER_COLUMN
        + ((long) nullBits + Long.SIZE - 1) / Long.SIZE * Long.BYTES
        + textBytes
        + (long) characters * Character.BYTES;
  }

  private static int growth(int current, int required, int maximum, int initial) {
    return required <= current
        ? current : BoundedArrayGrowth.capacity(current, required, maximum, initial);
  }
}
