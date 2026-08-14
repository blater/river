package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Owns the mutable cursor and fixed literal scratch for one parser invocation. */
final class SqlParserInput {
  private final char[] textCharacters = new char[Utf8Text.MAXIMUM_SCALARS * 2];
  private SqlCommand command;
  private int position;

  void reset(SqlCommand activeCommand) {
    command = activeCommand;
    position = 0;
  }

  int position() {
    return position;
  }

  void position(int next) {
    position = next;
  }

  StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    skipSpaces(sql);
    if (position >= sql.length() || !identifierStart(sql.charAt(position))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    while (position < sql.length() && identifierPart(sql.charAt(position))) {
      if (result.length() >= SqlIdentifier.MAXIMUM_LENGTH) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      result.append(lower(sql.charAt(position++)));
    }
    return StatusCode.OK;
  }

  StatusCode number(CharSequence sql, SqlParser.LongResult result) {
    result.varchar = false;
    result.textScalars = 0;
    result.typeDescriptor = SqlTypeDescriptor.BIGINT;
    skipSpaces(sql);
    if (position >= sql.length()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    boolean negative = sql.charAt(position) == '-';
    if (negative) {
      position++;
    }
    if (position >= sql.length() || !digit(sql.charAt(position))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
    long multiplyMinimum = limit / 10;
    long value = 0;
    while (position < sql.length() && digit(sql.charAt(position))) {
      int nextDigit = sql.charAt(position++) - '0';
      if (value < multiplyMinimum) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      value *= 10;
      if (value < limit + nextDigit) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      value -= nextDigit;
    }
    result.value = negative ? value : -value;
    return StatusCode.OK;
  }

  StatusCode literal(CharSequence sql, SqlParser.LongResult result) {
    if (startsText(sql)) {
      return packedText(sql, result);
    }
    if (consumeKeyword(sql, "TRUE")) {
      setBoolean(result, true);
      return StatusCode.OK;
    }
    if (consumeKeyword(sql, "FALSE")) {
      setBoolean(result, false);
      return StatusCode.OK;
    }
    return startsDecimal(sql) ? decimal(sql, result) : number(sql, result);
  }

  StatusCode packedText(CharSequence sql, SqlParser.LongResult result) {
    skipSpaces(sql);
    result.varchar = true;
    result.typeDescriptor = 0;
    if (position >= sql.length() || sql.charAt(position) != '\'') {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    position++;
    int length = 0;
    while (position < sql.length()) {
      char character = sql.charAt(position++);
      if (character == '\'') {
        if (position < sql.length() && sql.charAt(position) == '\'') {
          position++;
        } else {
          return storeText(result, length);
        }
      }
      if (length >= textCharacters.length) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      textCharacters[length++] = character;
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  boolean startsText(CharSequence sql) {
    int start = position;
    skipSpaces(sql);
    boolean text = position < sql.length() && sql.charAt(position) == '\'';
    position = start;
    return text;
  }

  boolean startsNumber(CharSequence sql) {
    skipSpaces(sql);
    if (position >= sql.length()) {
      return false;
    }
    char first = sql.charAt(position);
    return digit(first)
        || first == '-'
            && position + 1 < sql.length()
            && digit(sql.charAt(position + 1));
  }

  boolean startsLiteral(CharSequence sql) {
    int start = position;
    boolean literal = startsText(sql)
        || startsNumber(sql)
        || consumeKeyword(sql, "TRUE")
        || consumeKeyword(sql, "FALSE");
    position = start;
    return literal;
  }

  StatusCode typeDescriptor(CharSequence sql, SqlParser.LongResult result) {
    if (consumeKeyword(sql, "BIGINT")) {
      result.value = SqlTypeDescriptor.BIGINT;
      result.typeDescriptor = SqlTypeDescriptor.BIGINT;
      return StatusCode.OK;
    }
    if (consumeKeyword(sql, "BOOLEAN")) {
      result.value = SqlTypeDescriptor.BOOLEAN;
      result.typeDescriptor = SqlTypeDescriptor.BOOLEAN;
      return StatusCode.OK;
    }
    boolean varchar = consumeKeyword(sql, "VARCHAR");
    if (!varchar && !consumeKeyword(sql, "DECIMAL")) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = requireCharacter(sql, '(');
    if (status.isOk()) {
      status = number(sql, result);
    }
    int first = status.isOk() && result.value <= Integer.MAX_VALUE
        ? (int) result.value : -1;
    int second = 0;
    if (status.isOk() && !varchar && consumeCharacter(sql, ',')) {
      status = number(sql, result);
      second = status.isOk() && result.value <= Integer.MAX_VALUE
          ? (int) result.value : -1;
    }
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    int descriptor = varchar
        ? SqlTypeDescriptor.varchar(first)
        : SqlTypeDescriptor.decimal(first, second);
    if (status.isOk() && descriptor == 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      result.value = descriptor;
      result.typeDescriptor = descriptor;
    }
    return status;
  }

  private StatusCode decimal(CharSequence sql, SqlParser.LongResult result) {
    result.varchar = false;
    result.textScalars = 0;
    skipSpaces(sql);
    boolean negative = position < sql.length() && sql.charAt(position) == '-';
    if (negative) {
      position++;
    }
    int digits = 0;
    int scale = 0;
    long value = 0;
    boolean point = false;
    while (position < sql.length()) {
      char character = sql.charAt(position);
      if (digit(character)) {
        if (++digits > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION) {
          return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
        }
        value = value * 10 + character - '0';
        if (point) {
          scale++;
        }
        position++;
      } else if (character == '.' && !point) {
        point = true;
        position++;
      } else {
        break;
      }
    }
    if (!point || scale == 0 || digits == scale) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.value = negative ? -value : value;
    result.typeDescriptor = SqlTypeDescriptor.decimal(digits, scale);
    return StatusCode.OK;
  }

  private boolean startsDecimal(CharSequence sql) {
    int start = position;
    skipSpaces(sql);
    if (position < sql.length() && sql.charAt(position) == '-') {
      position++;
    }
    while (position < sql.length() && digit(sql.charAt(position))) {
      position++;
    }
    boolean decimal = position < sql.length() && sql.charAt(position) == '.';
    position = start;
    return decimal;
  }

  private static void setBoolean(SqlParser.LongResult result, boolean value) {
    result.value = value ? 1 : 0;
    result.varchar = false;
    result.textScalars = 0;
    result.typeDescriptor = SqlTypeDescriptor.BOOLEAN;
  }

  StatusCode requireKeyword(CharSequence sql, String keyword) {
    return consumeKeyword(sql, keyword)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  boolean consumeKeyword(CharSequence sql, String keyword) {
    skipSpaces(sql);
    if (sql.length() - position < keyword.length()) {
      return false;
    }
    for (int index = 0; index < keyword.length(); index++) {
      if (upper(sql.charAt(position + index)) != keyword.charAt(index)) {
        return false;
      }
    }
    int end = position + keyword.length();
    if (end < sql.length() && identifierPart(sql.charAt(end))) {
      return false;
    }
    position = end;
    return true;
  }

  StatusCode requireCharacter(CharSequence sql, char expected) {
    skipSpaces(sql);
    if (position >= sql.length() || sql.charAt(position) != expected) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    position++;
    return StatusCode.OK;
  }

  boolean consumeCharacter(CharSequence sql, char expected) {
    skipSpaces(sql);
    if (position >= sql.length() || sql.charAt(position) != expected) {
      return false;
    }
    position++;
    return true;
  }

  boolean finish(CharSequence sql) {
    skipSpaces(sql);
    if (position < sql.length() && sql.charAt(position) == ';') {
      position++;
      skipSpaces(sql);
    }
    return position == sql.length();
  }

  void skipSpaces(CharSequence sql) {
    while (position < sql.length() && Character.isWhitespace(sql.charAt(position))) {
      position++;
    }
  }

  static char upper(char character) {
    return character >= 'a' && character <= 'z' ? (char) (character - 32) : character;
  }

  static boolean identifierStart(char character) {
    return character >= 'A' && character <= 'Z'
        || character >= 'a' && character <= 'z'
        || character == '_';
  }

  private StatusCode storeText(SqlParser.LongResult result, int length) {
    int scalars = Utf8Text.scalarCount(textCharacters, 0, length);
    if (scalars < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (scalars > Utf8Text.MAXIMUM_SCALARS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    result.value = command.storeText(textCharacters, 0, length);
    result.textScalars = scalars;
    result.typeDescriptor = SqlTypeDescriptor.varchar(Math.max(1, result.textScalars));
    return result.value == SqlCommand.INVALID_TEXT_HANDLE
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  private static char lower(char character) {
    return character >= 'A' && character <= 'Z' ? (char) (character + 32) : character;
  }

  private static boolean identifierPart(char character) {
    return identifierStart(character) || digit(character);
  }

  private static boolean digit(char character) {
    return character >= '0' && character <= '9';
  }
}
