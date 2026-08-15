package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Owns fixed literal, descriptor, text, temporal, and parameter parsing. */
final class SqlLiteralReader {
  private final char[] textCharacters = new char[Utf8Text.MAXIMUM_SCALARS * 2];
  private final SqlParserInput input;
  private final SqlTemporalParser temporal;
  private final SqlTypeDescriptorReader types;
  private final SqlParameterReader parameters = new SqlParameterReader();
  private SqlCommand command;

  SqlLiteralReader(SqlParserInput parserInput) {
    input = parserInput;
    temporal = new SqlTemporalParser(input);
    types = new SqlTypeDescriptorReader(input, temporal, this);
  }

  void command(SqlCommand activeCommand) {
    command = activeCommand;
  }

  void beginParameters(SqlParameterSource source) {
    parameters.begin(source);
  }

  StatusCode finishParameters() {
    return parameters.finish();
  }

  void clearParameters() {
    parameters.reset();
  }

  StatusCode number(CharSequence sql, SqlParser.LongResult result) {
    result.nullValue = false;
    result.varchar = false;
    result.textScalars = 0;
    result.typeDescriptor = SqlTypeDescriptor.BIGINT;
    input.skipSpaces(sql);
    int position = input.position();
    if (position >= sql.length()) return StatusCode.INVALID_EXTERNAL_INPUT;
    boolean negative = sql.charAt(position) == '-';
    if (negative) position++;
    if (position >= sql.length() || !SqlParserInput.digit(sql.charAt(position))) {
      input.position(position);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
    long multiplyMinimum = limit / 10;
    long value = 0;
    while (position < sql.length() && SqlParserInput.digit(sql.charAt(position))) {
      int nextDigit = sql.charAt(position++) - '0';
      if (value < multiplyMinimum) {
        input.position(position);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      value *= 10;
      if (value < limit + nextDigit) {
        input.position(position);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      value -= nextDigit;
    }
    input.position(position);
    result.value = negative ? value : -value;
    return StatusCode.OK;
  }

  StatusCode literal(CharSequence sql, SqlParser.LongResult result) {
    result.nullValue = false;
    if (input.consumeCharacter(sql, '?')) {
      return parameters.read(command, textCharacters, result);
    }
    if (temporal.starts(sql)) return temporal.literal(sql, result);
    if (startsText(sql)) return packedText(sql, result);
    if (input.consumeKeyword(sql, "TRUE")) return setBoolean(result, true);
    if (input.consumeKeyword(sql, "FALSE")) return setBoolean(result, false);
    return startsDecimal(sql) ? decimal(sql, result) : number(sql, result);
  }

  StatusCode packedText(CharSequence sql, SqlParser.LongResult result) {
    input.skipSpaces(sql);
    result.nullValue = false;
    result.varchar = true;
    result.typeDescriptor = 0;
    int position = input.position();
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
          input.position(position);
          return storeText(result, length);
        }
      }
      if (length >= textCharacters.length) {
        input.position(position);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      textCharacters[length++] = character;
    }
    input.position(position);
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  boolean startsText(CharSequence sql) {
    int start = input.position();
    input.skipSpaces(sql);
    boolean text = input.position() < sql.length() && sql.charAt(input.position()) == '\'';
    input.position(start);
    return text;
  }

  long textSuccessor(long handle) {
    return command.textSuccessor(handle);
  }

  int compareText(long left, long right) {
    return command.compareText(left, right);
  }

  boolean startsNumber(CharSequence sql) {
    input.skipSpaces(sql);
    int position = input.position();
    if (position >= sql.length()) return false;
    char first = sql.charAt(position);
    return SqlParserInput.digit(first)
        || first == '-' && position + 1 < sql.length()
            && SqlParserInput.digit(sql.charAt(position + 1));
  }

  boolean startsLiteral(CharSequence sql) {
    int start = input.position();
    boolean literal = startsText(sql)
        || startsNumber(sql)
        || input.consumeCharacter(sql, '?')
        || input.consumeKeyword(sql, "TRUE")
        || input.consumeKeyword(sql, "FALSE")
        || temporal.starts(sql);
    input.position(start);
    return literal;
  }

  StatusCode typeDescriptor(CharSequence sql, SqlParser.LongResult result) {
    return types.read(sql, result);
  }

  private StatusCode decimal(CharSequence sql, SqlParser.LongResult result) {
    result.varchar = false;
    result.textScalars = 0;
    input.skipSpaces(sql);
    int position = input.position();
    boolean negative = position < sql.length() && sql.charAt(position) == '-';
    if (negative) position++;
    int digits = 0;
    int scale = 0;
    long value = 0;
    boolean point = false;
    while (position < sql.length()) {
      char character = sql.charAt(position);
      if (SqlParserInput.digit(character)) {
        if (++digits > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION) {
          input.position(position);
          return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
        }
        value = value * 10 + character - '0';
        if (point) scale++;
        position++;
      } else if (character == '.' && !point) {
        point = true;
        position++;
      } else {
        break;
      }
    }
    input.position(position);
    if (!point || scale == 0 || digits == scale) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.value = negative ? -value : value;
    result.typeDescriptor = SqlTypeDescriptor.decimal(digits, scale);
    return StatusCode.OK;
  }

  private boolean startsDecimal(CharSequence sql) {
    int start = input.position();
    input.skipSpaces(sql);
    int position = input.position();
    if (position < sql.length() && sql.charAt(position) == '-') position++;
    while (position < sql.length() && SqlParserInput.digit(sql.charAt(position))) position++;
    boolean decimal = position < sql.length() && sql.charAt(position) == '.';
    input.position(start);
    return decimal;
  }

  private StatusCode storeText(SqlParser.LongResult result, int length) {
    int scalars = Utf8Text.scalarCount(textCharacters, 0, length);
    if (scalars < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (scalars > Utf8Text.MAXIMUM_SCALARS) return StatusCode.RESOURCE_EXHAUSTED;
    result.value = command.storeText(textCharacters, 0, length);
    result.textScalars = scalars;
    result.typeDescriptor = SqlTypeDescriptor.varchar(Math.max(1, scalars));
    return result.value == SqlCommand.INVALID_TEXT_HANDLE
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  private static StatusCode setBoolean(
      SqlParser.LongResult result, boolean value) {
    result.value = value ? 1 : 0;
    result.varchar = false;
    result.textScalars = 0;
    result.typeDescriptor = SqlTypeDescriptor.BOOLEAN;
    return StatusCode.OK;
  }

}
