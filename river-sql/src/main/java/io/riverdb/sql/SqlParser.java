package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Allocation-free parser for River's first executable SQL point-statement subset. */
public final class SqlParser {
  private final LongResult numberResult = new LongResult();
  private final LongRow rowResult = new LongRow();
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
    boolean equalityPredicate = false;
    boolean serializableTransaction = false;
    if (consumeKeyword(sql, "BEGIN")) {
      type = SqlCommandType.BEGIN;
      status = StatusCode.OK;
      if (consumeKeyword(sql, "SERIALIZABLE")) {
        serializableTransaction = true;
      }
    } else if (consumeKeyword(sql, "SAVEPOINT")) {
      type = SqlCommandType.SAVEPOINT;
      status = identifier(sql, result.writableSavepointName());
    } else if (consumeKeyword(sql, "COMMIT")) {
      type = SqlCommandType.COMMIT;
      status = StatusCode.OK;
    } else if (consumeKeyword(sql, "ROLLBACK")) {
      if (consumeKeyword(sql, "TO")) {
        type = SqlCommandType.ROLLBACK_TO_SAVEPOINT;
        consumeKeyword(sql, "SAVEPOINT");
        status = identifier(sql, result.writableSavepointName());
      } else {
        type = SqlCommandType.ROLLBACK;
        status = StatusCode.OK;
      }
    } else if (consumeKeyword(sql, "RELEASE")) {
      type = SqlCommandType.RELEASE_SAVEPOINT;
      status = requireKeyword(sql, "SAVEPOINT");
      if (status.isOk()) {
        status = identifier(sql, result.writableSavepointName());
      }
    } else if (consumeKeyword(sql, "CHECKPOINT")) {
      type = SqlCommandType.CHECKPOINT;
      status = StatusCode.OK;
    } else if (consumeKeyword(sql, "CREATE")) {
      if (consumeKeyword(sql, "TABLE")) {
        type = SqlCommandType.CREATE_TABLE;
        status = identifier(sql, result.writableTableName());
        if (status.isOk() && consumeCharacter(sql, '(')) {
          status = columnIdentifier(sql, result);
          if (status.isOk()) {
            status = requireKeyword(sql, "BIGINT");
          }
          if (status.isOk()) {
            status = requireKeyword(sql, "PRIMARY");
          }
          if (status.isOk()) {
            status = requireKeyword(sql, "KEY");
          }
          while (status.isOk() && !consumeCharacter(sql, ')')) {
            status = requireCharacter(sql, ',');
            if (status.isOk()) {
              status = columnIdentifier(sql, result);
            }
            if (status.isOk()) {
              status = requireKeyword(sql, "BIGINT");
            }
          }
          if (status.isOk() && result.columnCount() < 2) {
            status = StatusCode.INVALID_EXTERNAL_INPUT;
          }
        } else if (status.isOk()) {
          setIdentifier(result.writableNextColumnName(), "key");
          setIdentifier(result.writableNextColumnName(), "value");
        }
      } else {
        boolean unique = consumeKeyword(sql, "UNIQUE");
        type = unique ? SqlCommandType.CREATE_UNIQUE_INDEX : SqlCommandType.CREATE_INDEX;
        status = requireKeyword(sql, "INDEX");
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
          status = columnIdentifier(sql, result);
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
      if (status.isOk() && consumeCharacter(sql, '(')) {
        while (status.isOk()) {
          status = columnIdentifier(sql, result);
          if (!status.isOk() || consumeCharacter(sql, ')')) {
            break;
          }
          status = requireCharacter(sql, ',');
        }
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "VALUES");
      }
      if (status.isOk()) {
        status = row(sql, rowResult);
        key = rowResult.values[0];
        value = rowResult.values[1];
        if (status.isOk()) {
          result.appendInsert(rowResult.values, rowResult.count);
        }
      }
      while (status.isOk() && consumeCharacter(sql, ',')) {
        if (result.insertRowCount() >= SqlCommand.MAXIMUM_INSERT_ROWS) {
          status = StatusCode.RESOURCE_EXHAUSTED;
        } else {
          status = row(sql, rowResult);
          if (status.isOk() && rowResult.count != result.insertColumnCount()) {
            status = StatusCode.INVALID_EXTERNAL_INPUT;
          }
          if (status.isOk()) {
            result.appendInsert(rowResult.values, rowResult.count);
          }
        }
      }
    } else if (consumeKeyword(sql, "SELECT")) {
      if (consumeKeyword(sql, "COUNT")) {
        type = SqlCommandType.COUNT;
        status = requireCharacter(sql, '(');
        if (status.isOk()) {
          status = requireCharacter(sql, '*');
        }
        if (status.isOk()) {
          status = requireCharacter(sql, ')');
        }
        if (status.isOk()) {
          status = requireKeyword(sql, "FROM");
        }
        if (status.isOk()) {
          status = identifier(sql, result.writableTableName());
        }
        if (status.isOk() && consumeKeyword(sql, "WHERE")) {
          status = identifier(sql, result.writablePredicateColumnName());
          if (status.isOk() && consumeCharacter(sql, '=')) {
            equalityPredicate = true;
            status = number(sql, numberResult);
            key = numberResult.value;
          } else if (status.isOk()) {
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
              status = matchingIdentifier(sql, result.predicateColumnName());
            }
            if (status.isOk()) {
              status = requireCharacter(sql, '<');
            }
            if (status.isOk()) {
              status = number(sql, numberResult);
              scanUpper = numberResult.value;
            }
          }
        }
      } else {
        type = SqlCommandType.SCAN;
        if (consumeCharacter(sql, '*')) {
          result.setSelectAll();
          status = StatusCode.OK;
        } else {
          status = columnIdentifier(sql, result);
          while (status.isOk() && consumeCharacter(sql, ',')) {
            status = columnIdentifier(sql, result);
          }
        }
        if (status.isOk()) {
          status = requireKeyword(sql, "FROM");
        }
        if (status.isOk()) {
          status = identifier(sql, result.writableTableName());
        }
        if (status.isOk() && consumeKeyword(sql, "WHERE")) {
          status = identifier(sql, result.writablePredicateColumnName());
          if (status.isOk() && consumeCharacter(sql, '=')) {
            type = SqlCommandType.SELECT;
            equalityPredicate = true;
            status = number(sql, numberResult);
            key = numberResult.value;
          } else if (status.isOk()) {
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
              status = matchingIdentifier(sql, result.predicateColumnName());
            }
            if (status.isOk()) {
              status = requireCharacter(sql, '<');
            }
            if (status.isOk()) {
              status = number(sql, numberResult);
              scanUpper = numberResult.value;
            }
          }
        }
        if (status.isOk() && consumeKeyword(sql, "ORDER")) {
          status = requireKeyword(sql, "BY");
          if (status.isOk()) {
            status = identifier(sql, result.writableOrderColumnName());
          }
          if (status.isOk()) {
            consumeKeyword(sql, "ASC");
          }
        }
      }
    } else if (consumeKeyword(sql, "UPDATE")) {
      type = SqlCommandType.UPDATE;
      status = identifier(sql, result.writableTableName());
      if (status.isOk()) {
        status = requireKeyword(sql, "SET");
      }
      while (status.isOk()) {
        status = columnIdentifier(sql, result);
        if (status.isOk()) {
          status = requireCharacter(sql, '=');
        }
        if (status.isOk()) {
          status = number(sql, numberResult);
        }
        if (status.isOk()) {
          result.appendUpdate(numberResult.value);
          if (result.updateColumnCount() == 1) {
            value = numberResult.value;
          }
        }
        if (!status.isOk() || !consumeCharacter(sql, ',')) {
          break;
        }
      }
      if (status.isOk()) {
        status = requireKeyword(sql, "WHERE");
      }
      if (status.isOk()) {
        status = identifier(sql, result.writablePredicateColumnName());
      }
      if (status.isOk() && consumeCharacter(sql, '=')) {
        equalityPredicate = true;
        status = number(sql, numberResult);
        key = numberResult.value;
      } else if (status.isOk()) {
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
          status = matchingIdentifier(sql, result.predicateColumnName());
        }
        if (status.isOk()) {
          status = requireCharacter(sql, '<');
        }
        if (status.isOk()) {
          status = number(sql, numberResult);
          scanUpper = numberResult.value;
        }
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
        status = identifier(sql, result.writablePredicateColumnName());
      }
      if (status.isOk() && consumeCharacter(sql, '=')) {
        equalityPredicate = true;
        status = number(sql, numberResult);
        key = numberResult.value;
      } else if (status.isOk()) {
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
          status = matchingIdentifier(sql, result.predicateColumnName());
        }
        if (status.isOk()) {
          status = requireCharacter(sql, '<');
        }
        if (status.isOk()) {
          status = number(sql, numberResult);
          scanUpper = numberResult.value;
        }
      }
    } else {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk() || !finish(sql)) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    if (type == SqlCommandType.INSERT) {
      result.setInsert();
    } else if (type == SqlCommandType.SCAN) {
      result.setScan(scanLower, scanUpper, boundedScan);
    } else if (type == SqlCommandType.BEGIN) {
      result.setBegin(serializableTransaction);
    } else {
      result.set(type, key, value);
    }
    if (type == SqlCommandType.COUNT
        || type == SqlCommandType.UPDATE
        || type == SqlCommandType.DELETE) {
      result.setPredicate(
          key, scanLower, scanUpper, boundedScan, equalityPredicate);
    }
    return StatusCode.OK;
  }

  private StatusCode row(String sql, LongRow result) {
    result.count = 0;
    StatusCode status = requireCharacter(sql, '(');
    while (status.isOk()) {
      if (result.count >= SqlCommand.MAXIMUM_COLUMNS) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = number(sql, numberResult);
      if (status.isOk()) {
        result.values[result.count++] = numberResult.value;
      }
      if (!status.isOk() || consumeCharacter(sql, ')')) {
        break;
      }
      status = requireCharacter(sql, ',');
    }
    return status.isOk() && result.count >= 2
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode columnIdentifier(String sql, SqlCommand result) {
    SqlIdentifier column = result.writableNextColumnName();
    return column == null ? StatusCode.RESOURCE_EXHAUSTED : identifier(sql, column);
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
      result.append(lower(sql.charAt(offset++)));
    }
    return StatusCode.OK;
  }

  private StatusCode matchingIdentifier(String sql, CharSequence expected) {
    SqlIdentifier actual = rowResult.identifier;
    actual.reset();
    StatusCode status = identifier(sql, actual);
    if (!status.isOk() || actual.length() != expected.length()) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    for (int index = 0; index < actual.length(); index++) {
      if (actual.charAt(index) != expected.charAt(index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return StatusCode.OK;
  }

  private static void setIdentifier(SqlIdentifier target, String value) {
    for (int index = 0; index < value.length(); index++) {
      target.append(value.charAt(index));
    }
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

  private boolean consumeCharacter(String sql, char expected) {
    skipSpaces(sql);
    if (offset >= sql.length() || sql.charAt(offset) != expected) {
      return false;
    }
    offset++;
    return true;
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

  private static char lower(char character) {
    return character >= 'A' && character <= 'Z' ? (char) (character + 32) : character;
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

  private static final class LongRow {
    private final long[] values = new long[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlIdentifier identifier = new SqlIdentifier();
    private int count;
  }
}
