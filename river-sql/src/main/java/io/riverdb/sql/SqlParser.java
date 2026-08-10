package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Allocation-free parser for River's first executable SQL point-statement subset. */
public final class SqlParser {
  private final LongResult numberResult = new LongResult();
  private final LongPair pairResult = new LongPair();
  private int offset;

  public StatusCode parse(String sql, SqlCommand result) {
    if (sql == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    offset = 0;
    skipSpaces(sql);
    StatusCode status;
    SqlCommandType type;
    long key = 0;
    long value = 0;
    long scanLower = 0;
    long scanUpper = 0;
    boolean boundedScan = false;
    boolean serializableTransaction = false;
    if (consumeKeyword(sql, "BEGIN")) {
      type = SqlCommandType.BEGIN;
      status = StatusCode.OK;
      if (consumeKeyword(sql, "SERIALIZABLE")) {
        serializableTransaction = true;
      }
    } else if (consumeKeyword(sql, "COMMIT")) {
      type = SqlCommandType.COMMIT;
      status = StatusCode.OK;
    } else if (consumeKeyword(sql, "ROLLBACK")) {
      type = SqlCommandType.ROLLBACK;
      status = StatusCode.OK;
    } else if (consumeKeyword(sql, "CHECKPOINT")) {
      type = SqlCommandType.CHECKPOINT;
      status = StatusCode.OK;
    } else if (consumeKeyword(sql, "CREATE")) {
      if (consumeKeyword(sql, "TABLE")) {
        type = SqlCommandType.CREATE_TABLE;
        status = identifier(sql, result.writableTableName());
      } else {
        type = SqlCommandType.CREATE_UNIQUE_INDEX;
        status = requireKeyword(sql, "UNIQUE");
        if (status.isOk()) {
          status = requireKeyword(sql, "INDEX");
        }
        if (status.isOk()) {
          status = identifier(sql, result.writableIndexName());
        }
        if (status.isOk()) {
          status = requireKeyword(sql, "ON");
        }
        if (status.isOk()) {
          status = identifier(sql, result.writableTableName());
        }
        if (status.isOk()) {
          status = requireCharacter(sql, '(');
        }
        if (status.isOk()) {
          status = requireKeyword(sql, "VALUE");
        }
        if (status.isOk()) {
          status = requireCharacter(sql, ')');
        }
      }
    } else if (consumeKeyword(sql, "INSERT")) {
      type = SqlCommandType.INSERT;
      status = requireKeyword(sql, "INTO");
      if (status.isOk()) {
        status = identifier(sql, result.writableTableName());
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "VALUES");
      }
      if (status.isOk()) {
        status = pair(sql, pairResult);
        key = pairResult.first;
        value = pairResult.second;
      }
    } else if (consumeKeyword(sql, "SELECT")) {
      if (consumeKeyword(sql, "KEY")) {
        type = SqlCommandType.SCAN;
        status = requireCharacter(sql, ',');
        if (status.isOk()) {
          status = requireKeyword(sql, "VALUE");
        }
        if (status.isOk()) {
          status = requireKeyword(sql, "FROM");
        }
        if (status.isOk()) {
          status = identifier(sql, result.writableTableName());
        }
        if (status.isOk() && consumeKeyword(sql, "WHERE")) {
          if (consumeKeyword(sql, "KEY")) {
            boundedScan = true;
            status = requireCharacter(sql, '>');
            if (status.isOk()) {
              status = requireCharacter(sql, '=');
            }
            if (status.isOk()) {
              status = number(sql, numberResult);
              scanLower = numberResult.value;
            }
            if (status.isOk()) {
              status = requireKeyword(sql, "AND");
            }
            if (status.isOk()) {
              status = requireKeyword(sql, "KEY");
            }
            if (status.isOk()) {
              status = requireCharacter(sql, '<');
            }
            if (status.isOk()) {
              status = number(sql, numberResult);
              scanUpper = numberResult.value;
            }
          } else if (consumeKeyword(sql, "VALUE")) {
            type = SqlCommandType.SELECT_BY_VALUE;
            status = requireCharacter(sql, '=');
            if (status.isOk()) {
              status = number(sql, numberResult);
              value = numberResult.value;
            }
          } else {
            status = StatusCode.INVALID_EXTERNAL_INPUT;
          }
        }
      } else {
        type = SqlCommandType.SELECT;
        status = requireKeyword(sql, "VALUE");
        if (status.isOk()) {
          status = requireKeyword(sql, "FROM");
        }
        if (status.isOk()) {
          status = identifier(sql, result.writableTableName());
        }
        if (status.isOk()) {
          status = requireKeyword(sql, "WHERE");
        }
        if (status.isOk()) {
          status = requireKeyword(sql, "KEY");
        }
        if (status.isOk()) {
          status = requireCharacter(sql, '=');
        }
        if (status.isOk()) {
          status = number(sql, numberResult);
          key = numberResult.value;
        }
      }
    } else if (consumeKeyword(sql, "UPDATE")) {
      type = SqlCommandType.UPDATE;
      status = identifier(sql, result.writableTableName());
      if (status.isOk()) {
        status = requireKeyword(sql, "SET");
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "VALUE");
      }
      if (status.isOk()) {
        status = requireCharacter(sql, '=');
      }
      if (status.isOk()) {
        status = number(sql, numberResult);
        value = numberResult.value;
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "WHERE");
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "KEY");
      }
      if (status.isOk()) {
        status = requireCharacter(sql, '=');
      }
      if (status.isOk()) {
        status = number(sql, numberResult);
        key = numberResult.value;
      }
    } else if (consumeKeyword(sql, "DELETE")) {
      type = SqlCommandType.DELETE;
      status = requireKeyword(sql, "FROM");
      if (status.isOk()) {
        status = identifier(sql, result.writableTableName());
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "WHERE");
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "KEY");
      }
      if (status.isOk()) {
        status = requireCharacter(sql, '=');
      }
      if (status.isOk()) {
        status = number(sql, numberResult);
        key = numberResult.value;
      }
    } else {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk() || !finish(sql)) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    if (type == SqlCommandType.SCAN) {
      result.setScan(scanLower, scanUpper, boundedScan);
    } else if (type == SqlCommandType.BEGIN) {
      result.setBegin(serializableTransaction);
    } else {
      result.set(type, key, value);
    }
    return StatusCode.OK;
  }

  private StatusCode pair(String sql, LongPair result) {
    StatusCode status = requireCharacter(sql, '(');
    if (status.isOk()) {
      status = number(sql, numberResult);
      result.first = numberResult.value;
    }
    if (status.isOk()) {
      status = requireCharacter(sql, ',');
    }
    if (status.isOk()) {
      status = number(sql, numberResult);
      result.second = numberResult.value;
    }
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    return status;
  }

  private StatusCode identifier(String sql, SqlIdentifier result) {
    skipSpaces(sql);
    if (offset >= sql.length() || !identifierStart(sql.charAt(offset))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    while (offset < sql.length() && identifierPart(sql.charAt(offset))) {
      if (result.length() >= SqlIdentifier.MAXIMUM_LENGTH) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      result.append(sql.charAt(offset++));
    }
    return StatusCode.OK;
  }

  private StatusCode number(String sql, LongResult result) {
    skipSpaces(sql);
    if (offset >= sql.length()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean negative = sql.charAt(offset) == '-';
    if (negative) {
      offset++;
    }
    if (offset >= sql.length() || !digit(sql.charAt(offset))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
    long multiplyMinimum = limit / 10;
    long value = 0;
    while (offset < sql.length() && digit(sql.charAt(offset))) {
      int digit = sql.charAt(offset++) - '0';
      if (value < multiplyMinimum) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      value *= 10;
      if (value < limit + digit) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      value -= digit;
    }
    result.value = negative ? value : -value;
    return StatusCode.OK;
  }

  private StatusCode requireKeyword(String sql, String keyword) {
    return consumeKeyword(sql, keyword) ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private boolean consumeKeyword(String sql, String keyword) {
    skipSpaces(sql);
    if (sql.length() - offset < keyword.length()) {
      return false;
    }
    for (int index = 0; index < keyword.length(); index++) {
      if (upper(sql.charAt(offset + index)) != keyword.charAt(index)) {
        return false;
      }
    }
    int end = offset + keyword.length();
    if (end < sql.length() && identifierPart(sql.charAt(end))) {
      return false;
    }
    offset = end;
    return true;
  }

  private StatusCode requireCharacter(String sql, char expected) {
    skipSpaces(sql);
    if (offset >= sql.length() || sql.charAt(offset) != expected) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    offset++;
    return StatusCode.OK;
  }

  private boolean finish(String sql) {
    skipSpaces(sql);
    if (offset < sql.length() && sql.charAt(offset) == ';') {
      offset++;
      skipSpaces(sql);
    }
    return offset == sql.length();
  }

  private void skipSpaces(String sql) {
    while (offset < sql.length() && Character.isWhitespace(sql.charAt(offset))) {
      offset++;
    }
  }

  private static char upper(char character) {
    return character >= 'a' && character <= 'z' ? (char) (character - 32) : character;
  }

  private static boolean identifierStart(char character) {
    return character >= 'A' && character <= 'Z'
        || character >= 'a' && character <= 'z'
        || character == '_';
  }

  private static boolean identifierPart(char character) {
    return identifierStart(character) || digit(character);
  }

  private static boolean digit(char character) {
    return character >= '0' && character <= '9';
  }

  private static final class LongResult {
    private long value;
  }

  private static final class LongPair {
    private long first;
    private long second;
  }
}
