package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Disjoint catalog and logical-table key encoding for the first physical keyspace. */
final class RelationalKey {
  static final long CATALOG_SEQUENCE_KEY = Long.MIN_VALUE;
  static final long MAXIMUM_USER_KEY = (1L << 48) - 2;
  static final int MAXIMUM_TABLE_ID = 0x7fff;

  private RelationalKey() {
  }

  static StatusCode catalogTableKey(String name, LongKeyResult result) {
    if (!validName(name) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < name.length(); index++) {
      hash ^= name.charAt(index);
      hash *= 0x100000001b3L;
    }
    long key = hash | Long.MIN_VALUE;
    if (key == CATALOG_SEQUENCE_KEY || key == Long.MAX_VALUE) {
      key++;
    }
    result.set(key);
    return StatusCode.OK;
  }

  static StatusCode tableRowKey(int tableId, long userKey, LongKeyResult result) {
    if (tableId <= 0
        || tableId > MAXIMUM_TABLE_ID
        || userKey < 0
        || userKey > MAXIMUM_USER_KEY
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.set((long) tableId << 48 | userKey);
    return StatusCode.OK;
  }

  static boolean validName(String name) {
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

  static final class LongKeyResult {
    private long key;

    void set(long value) {
      key = value;
    }

    long key() {
      return key;
    }
  }
}
