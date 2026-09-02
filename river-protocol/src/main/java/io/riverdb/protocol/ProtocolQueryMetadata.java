package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.RiverQuery;

/** Reusable protocol-owned snapshot of query metadata that survives reactive query close. */
public final class ProtocolQueryMetadata {
  private int[] descriptors = new int[8];
  private long[] nullableWords = new long[1];
  private int[] nameOffsets = new int[8];
  private int[] nameLengths = new int[8];
  private char[] names = new char[64];
  private int columnCount;
  private int nameCharacters;

  public StatusCode capture(RiverQuery query) {
    if (query == null || !query.isActive()) return StatusCode.INVALID_EXTERNAL_INPUT;
    int columns = query.columnCount();
    if (columns <= 0 || columns > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int characters = 0;
    for (int index = 0; index < columns; index++) {
      CharSequence name = query.columnName(index);
      if (!validName(name)
          || !SqlTypeDescriptor.isValid(query.columnTypeDescriptor(index))
          || characters > SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES - name.length()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      characters += name.length();
    }
    long encodedBytes = (columns + 7L) / 8L
        + (long) columns * (Integer.BYTES + 1L) + characters;
    if (encodedBytes > SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = reserve(columns, characters);
    if (!status.isOk()) return status;
    clear();
    for (int index = 0; index < columns; index++) {
      CharSequence name = query.columnName(index);
      descriptors[index] = query.columnTypeDescriptor(index);
      if (query.columnIsNullable(index)) {
        nullableWords[index >>> 6] |= 1L << (index & 63);
      }
      nameOffsets[index] = nameCharacters;
      nameLengths[index] = name.length();
      for (int character = 0; character < name.length(); character++) {
        names[nameCharacters++] = name.charAt(character);
      }
    }
    columnCount = columns;
    return StatusCode.OK;
  }

  public int columnCount() { return columnCount; }
  public int typeDescriptorAt(int index) { return descriptors[index]; }
  public boolean columnIsNullable(int index) {
    return (nullableWords[index >>> 6] & 1L << (index & 63)) != 0;
  }
  public int nameLengthAt(int index) { return nameLengths[index]; }
  public char nameCharacterAt(int index, int character) {
    return names[nameOffsets[index] + character];
  }

  private StatusCode reserve(int columns, int characters) {
    if (columns <= descriptors.length && characters <= names.length) return StatusCode.OK;
    int columnCapacity = Math.min(
        SqlShapeLimits.MAX_RESULT_COLUMNS,
        Math.max(columns, descriptors.length << 1));
    int nameCapacity = Math.min(
        SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES,
        Math.max(characters, names.length << 1));
    try {
      int[] nextDescriptors = new int[columnCapacity];
      long[] nextNullable = new long[(columnCapacity + 63) >>> 6];
      int[] nextOffsets = new int[columnCapacity];
      int[] nextLengths = new int[columnCapacity];
      char[] nextNames = new char[nameCapacity];
      descriptors = nextDescriptors;
      nullableWords = nextNullable;
      nameOffsets = nextOffsets;
      nameLengths = nextLengths;
      names = nextNames;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private void clear() {
    for (int word = 0; word < (columnCount + 63) >>> 6; word++) {
      nullableWords[word] = 0;
    }
    columnCount = 0;
    nameCharacters = 0;
  }

  private static boolean validName(CharSequence name) {
    if (name == null || name.length() == 0
        || name.length() > ProtocolFrameCodec.MAXIMUM_COLUMN_NAME_BYTES
        || !identifierStart(name.charAt(0))) {
      return false;
    }
    for (int index = 1; index < name.length(); index++) {
      if (!identifierPart(name.charAt(index))) return false;
    }
    return true;
  }

  private static boolean identifierStart(char value) {
    return value >= 'a' && value <= 'z'
        || value >= 'A' && value <= 'Z' || value == '_';
  }

  private static boolean identifierPart(char value) {
    return identifierStart(value) || value >= '0' && value <= '9';
  }
}
