package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Primitive physical key-space assignment for catalog and relational table ownership. */
final class RelationalKey {
  static final int CATALOG_OBJECT_SPACE = 0;
  static final int CATALOG_SEQUENCE_SPACE = 1;
  static final int MAXIMUM_TABLE_ID = 0x7fff;

  private RelationalKey() {
  }

  static StatusCode catalogTableKey(CharSequence name, KeyResult result) {
    if (!validName(name) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < name.length(); index++) {
      hash ^= name.charAt(index);
      hash *= 0x100000001b3L;
    }
    result.set(CATALOG_OBJECT_SPACE, hash);
    return StatusCode.OK;
  }

  static long identitySequenceKey(int tableId) {
    return tableId;
  }

  static long tableStatisticsKey(int tableId) {
    return -tableId;
  }

  static StatusCode tableRowKey(int tableId, long userKey, KeyResult result) {
    if (tableId <= 0
        || tableId > MAXIMUM_TABLE_ID
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.set(dataSpace(tableId), userKey);
    return StatusCode.OK;
  }

  static int dataSpace(int tableId) {
    return tableId << 1;
  }

  static int auxiliarySpace(int tableId) {
    return (tableId << 1) + 1;
  }

  static boolean validName(CharSequence name) {
    if (name == null || name.isEmpty() || name.length() > 64) {
      return false;
    }
    char first = name.charAt(0);
    if (!asciiLetter(first) && first != '_') {
      return false;
    }
    for (int index = 1; index < name.length(); index++) {
      char character = name.charAt(index);
      if (!asciiLetter(character)
          && (character < '0' || character > '9')
          && character != '_') {
        return false;
      }
    }
    return true;
  }

  private static boolean asciiLetter(char character) {
    return character >= 'A' && character <= 'Z'
        || character >= 'a' && character <= 'z';
  }

  static final class KeyResult {
    private int space;
    private long key;

    void set(int valueSpace, long value) {
      space = valueSpace;
      key = value;
    }

    int space() {
      return space;
    }

    long key() {
      return key;
    }
  }
}
