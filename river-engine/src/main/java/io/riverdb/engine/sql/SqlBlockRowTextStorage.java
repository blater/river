package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.engine.api.CommandResult;
import java.nio.ByteBuffer;

/** Retained UTF-8 text with UTF-16 scratch only for character consumers. */
final class SqlBlockRowTextStorage {
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  private char[][] lanes = new char[0][];
  private short[] lengths = new short[0];
  private byte[][] encoded = new byte[0][];
  private int[] encodedLengths = new int[0];
  private boolean[] encodedCurrent = new boolean[0];
  private boolean[] charactersCurrent = new boolean[0];
  private StatusCode readStatus = StatusCode.OK;

  SqlBlockRowTextStorage(SqlRetainedArrayAllocator retainedAllocator) {
    this(retainedAllocator, null);
  }

  SqlBlockRowTextStorage(
      SqlRetainedArrayAllocator retainedAllocator, SqlSessionShapeBudget shapeBudget) {
    allocator = retainedAllocator;
    budget = shapeBudget;
  }

  char[][] lanes() { return lanes; }
  short[] lengths() { return lengths; }
  byte[][] encodedLanes() { return encoded; }
  int[] encodedLengths() { return encodedLengths; }
  boolean[] encodedCurrent() { return encodedCurrent; }
  boolean[] charactersCurrent() { return charactersCurrent; }

  void publish(
      char[][] nextLanes, short[] nextLengths, byte[][] nextEncoded,
      int[] nextEncodedLengths, boolean[] nextEncodedCurrent,
      boolean[] nextCharactersCurrent) {
    lanes = nextLanes;
    lengths = nextLengths;
    encoded = nextEncoded;
    encodedLengths = nextEncodedLengths;
    encodedCurrent = nextEncodedCurrent;
    charactersCurrent = nextCharactersCurrent;
  }

  void clear(int columns) {
    for (int column = 0; column < columns; column++) {
      clearValue(column);
    }
  }

  void clearValue(int column) {
    if (charactersCurrent[column] && lanes[column] != null) {
      int length = length(column);
      for (int index = 0; index < length; index++) lanes[column][index] = 0;
    }
    int encodedLength = encodedLengths[column];
    if (encoded[column] != null) {
      for (int index = 0; index < encodedLength; index++) encoded[column][index] = 0;
    }
    lengths[column] = 0;
    encodedLengths[column] = 0;
    encodedCurrent[column] = false;
    charactersCurrent[column] = false;
    readStatus = StatusCode.OK;
  }

  int length(int column) {
    int length = Short.toUnsignedInt(lengths[column]);
    if (length == 0 && encodedCurrent[column] && encodedLengths[column] > 0) {
      length = utf16Length(encoded[column], encodedLengths[column]);
      lengths[column] = (short) length;
    }
    return length;
  }
  boolean hasValue(int column) {
    return lengths[column] != 0 || encodedCurrent[column];
  }
  void length(int column, int length) {
    lengths[column] = (short) length;
    charactersCurrent[column] = true;
    encodedCurrent[column] = false;
    int previous = encodedLengths[column];
    if (encoded[column] != null) {
      for (int index = 0; index < previous; index++) encoded[column][index] = 0;
    }
    encodedLengths[column] = 0;
  }

  boolean hasUtf8(int column) { return encodedCurrent[column]; }
  byte[] utf8(int column) { return encoded[column]; }
  int utf8Length(int column) { return encodedLengths[column]; }

  StatusCode setUtf8(int column, BoundedByteSource source, int offset, int length) {
    if (source == null || offset < 0 || length < 0
        || offset > source.length() - length) return StatusCode.CORRUPTION;
    StatusCode status = reserveBytes(column, length);
    if (!status.isOk()) return status;
    byte[] target = encoded[column];
    for (int index = 0; index < length; index++) {
      target[index] = source.getByte(offset + index);
    }
    publishUtf8(column, length);
    return StatusCode.OK;
  }

  StatusCode setUtf8(int column, ByteBuffer source, int offset, int length) {
    if (source == null || offset < 0 || length < 0
        || offset > source.limit() - length) return StatusCode.CORRUPTION;
    StatusCode status = reserveBytes(column, length);
    if (!status.isOk()) return status;
    byte[] target = encoded[column];
    for (int index = 0; index < length; index++) {
      target[index] = source.get(offset + index);
    }
    publishUtf8(column, length);
    return StatusCode.OK;
  }

  StatusCode setUtf8(int column, byte[] source, int offset, int length) {
    if (source == null || offset < 0 || length < 0
        || offset > source.length - length) return StatusCode.CORRUPTION;
    StatusCode status = reserveBytes(column, length);
    if (!status.isOk()) return status;
    System.arraycopy(source, offset, encoded[column], 0, length);
    publishUtf8(column, length);
    return StatusCode.OK;
  }

  private void publishUtf8(int column, int length) {
    int previous = encodedLengths[column];
    for (int index = length; index < previous; index++) encoded[column][index] = 0;
    if (charactersCurrent[column] && lanes[column] != null) {
      for (int index = 0; index < this.length(column); index++) lanes[column][index] = 0;
    }
    lengths[column] = 0;
    encodedLengths[column] = length;
    encodedCurrent[column] = true;
    charactersCurrent[column] = false;
  }

  private StatusCode reserveBytes(int column, int required) {
    if (required > 0xffff) return StatusCode.RESOURCE_EXHAUSTED;
    int current = encoded[column] == null ? 0 : encoded[column].length;
    if (current >= Math.max(1, required)) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(current, Math.max(1, required), 0xffff, 32);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = budget == null ? StatusCode.OK : budget.reserve(capacity - current);
    if (!status.isOk()) return status;
    try {
      byte[] next = allocator.bytes(capacity);
      encoded[column] = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      if (budget != null) budget.rollback(capacity - current);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static int utf16Length(byte[] bytes, int length) {
    int characters = 0;
    for (int index = 0; index < length;) {
      int first = Byte.toUnsignedInt(bytes[index]);
      int width = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
      characters += width == 4 ? 2 : 1;
      index += width;
    }
    return characters;
  }

  StatusCode prepare(int column) {
    return prepare(column, CommandResult.MAXIMUM_TEXT_CHARACTERS);
  }

  StatusCode prepare(int column, int characters) {
    if (characters <= 0 || characters > CommandResult.MAXIMUM_TEXT_CHARACTERS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int current = lanes[column] == null ? 0 : lanes[column].length;
    if (current >= characters) return StatusCode.OK;
    long charged = (long) (characters - current) * Character.BYTES;
    StatusCode status = budget == null ? StatusCode.OK : budget.reserve(charged);
    if (!status.isOk()) return status;
    try {
      char[] next = allocator.characters(characters);
      if (current > 0 && charactersCurrent[column]) {
        System.arraycopy(lanes[column], 0, next, 0, length(column));
      }
      lanes[column] = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      if (budget != null) budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  char[] existing(int column) { return lanes[column]; }
  StatusCode readStatus() { return readStatus; }

  char[] text(int column) {
    if (encodedCurrent[column] && !charactersCurrent[column]) {
      readStatus = prepare(column, Math.max(1, length(column)));
      if (!readStatus.isOk()) return null;
      int decoded = Utf8Text.trustedDecode(
          encoded[column], 0, encodedLengths[column], lanes[column], 0);
      if (decoded != length(column)) {
        readStatus = StatusCode.CORRUPTION;
        return null;
      }
      charactersCurrent[column] = true;
    }
    if (lanes[column] != null) return lanes[column];
    readStatus = prepare(column);
    return readStatus.isOk() ? lanes[column] : null;
  }
}
