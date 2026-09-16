package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Detects and parses fixed-point decimal literals. */
final class SqlDecimalLiteralReader {
  private final SqlParserInput input;
  private int position;
  private int digits;
  private int significantIntegerDigits;
  private int scale;
  private long high;
  private long value;
  private boolean point;
  private boolean significantInteger;

  SqlDecimalLiteralReader(SqlParserInput parserInput) {
    input = parserInput;
  }

  boolean starts(CharSequence sql) {
    int start = input.position();
    input.skipSpaces(sql);
    int position = input.position();
    if (position < sql.length() && sql.charAt(position) == '-') position++;
    while (position < sql.length() && SqlParserInput.digit(sql.charAt(position))) position++;
    boolean decimal = position < sql.length() && sql.charAt(position) == '.';
    input.position(start);
    return decimal;
  }

  StatusCode read(CharSequence sql, SqlParser.LongResult result) {
    return read(sql, result, true);
  }

  StatusCode readIntegral(CharSequence sql, SqlParser.LongResult result) {
    return read(sql, result, false);
  }

  private StatusCode read(
      CharSequence sql, SqlParser.LongResult result, boolean requirePoint) {
    result.varchar = false;
    result.textScalars = 0;
    input.skipSpaces(sql);
    position = input.position();
    boolean negative = position < sql.length() && sql.charAt(position) == '-';
    if (negative) position++;
    digits = 0;
    significantIntegerDigits = 0;
    scale = 0;
    high = 0;
    value = 0;
    point = false;
    significantInteger = false;
    StatusCode status = readDigits(sql, requirePoint);
    if (!status.isOk()) return status;
    if (negative) {
      result.value = ~value + 1;
      result.high = ~high + (result.value == 0 ? 1 : 0);
    } else {
      result.high = high;
      result.value = value;
    }
    int precision = Math.max(1, significantIntegerDigits + scale);
    result.typeDescriptor = SqlTypeDescriptor.decimal(precision, scale);
    return StatusCode.OK;
  }

  private StatusCode readDigits(CharSequence sql, boolean requirePoint) {
    while (position < sql.length()) {
      char character = sql.charAt(position);
      if (SqlParserInput.digit(character)) {
        if (!readDigit(character)) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      } else if (character == '.' && !point) {
        point = true;
        position++;
      } else {
        break;
      }
    }
    input.position(position);
    return digits == 0
        || requirePoint && (!point || scale == 0 || digits == scale)
        || !requirePoint && point ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }

  private boolean readDigit(char character) {
    digits++;
    if (point) {
      scale++;
    } else if (significantInteger || character != '0') {
      significantInteger = true;
      significantIntegerDigits++;
    }
    if (significantIntegerDigits + scale > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION) {
      input.position(position);
      return false;
    }
    long multiplied = value * 10;
    long carry = Math.multiplyHigh(value, 10) + (value < 0 ? 10 : 0);
    high = high * 10 + carry;
    value = multiplied + character - '0';
    if (Long.compareUnsigned(value, multiplied) < 0) high++;
    position++;
    return true;
  }
}
