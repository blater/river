package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses one signed integral literal without overflow or allocation. */
final class SqlIntegralLiteralReader {
  private final SqlParserInput input;

  SqlIntegralLiteralReader(SqlParserInput parserInput) {
    input = parserInput;
  }

  StatusCode read(CharSequence sql, SqlParser.LongResult result) {
    reset(result);
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
    long minimum = limit / 10;
    long value = 0;
    while (position < sql.length() && SqlParserInput.digit(sql.charAt(position))) {
      int digit = sql.charAt(position++) - '0';
      if (value < minimum || (value *= 10) < limit + digit) {
        input.position(position);
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      value -= digit;
    }
    input.position(position);
    result.value = negative ? value : -value;
    if (result.value < Integer.MIN_VALUE || result.value > Integer.MAX_VALUE) {
      result.typeDescriptor = SqlTypeDescriptor.BIGINT;
    }
    return StatusCode.OK;
  }

  private static void reset(SqlParser.LongResult result) {
    result.nullValue = false;
    result.varchar = false;
    result.textScalars = 0;
    result.typeDescriptor = SqlTypeDescriptor.INTEGER;
  }
}
