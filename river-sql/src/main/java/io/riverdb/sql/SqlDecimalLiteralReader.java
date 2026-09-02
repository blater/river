package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Detects and parses fixed-point decimal literals. */
final class SqlDecimalLiteralReader {
  private final SqlParserInput input;

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
    int position = input.position();
    boolean negative = position < sql.length() && sql.charAt(position) == '-';
    if (negative) position++;
    int digits = 0;
    int significantIntegerDigits = 0;
    int scale = 0;
    long high = 0;
    long value = 0;
    boolean point = false;
    boolean significantInteger = false;
    while (position < sql.length()) {
      char character = sql.charAt(position);
      if (SqlParserInput.digit(character)) {
        digits++;
        if (point) {
          scale++;
        } else if (significantInteger || character != '0') {
          significantInteger = true;
          significantIntegerDigits++;
        }
        if (significantIntegerDigits + scale
            > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION) {
          input.position(position);
          return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
        }
        long multiplied = value * 10;
        long carry = Math.multiplyHigh(value, 10) + (value < 0 ? 10 : 0);
        high = high * 10 + carry;
        value = multiplied + character - '0';
        if (Long.compareUnsigned(value, multiplied) < 0) high++;
        position++;
      } else if (character == '.' && !point) {
        point = true;
        position++;
      } else break;
    }
    input.position(position);
    if (digits == 0
        || requirePoint && (!point || scale == 0 || digits == scale)
        || !requirePoint && point) return StatusCode.INVALID_EXTERNAL_INPUT;
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
}
