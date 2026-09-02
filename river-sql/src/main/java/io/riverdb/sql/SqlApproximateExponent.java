package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Reusable parse result for one bounded scientific-literal exponent. */
final class SqlApproximateExponent {
  private int value;
  private int end;

  StatusCode read(CharSequence sql, int start) {
    int position = start;
    boolean negative = position < sql.length() && sql.charAt(position) == '-';
    if (negative || position < sql.length() && sql.charAt(position) == '+') position++;
    int exponent = 0;
    int digits = 0;
    while (position < sql.length() && SqlParserInput.digit(sql.charAt(position))) {
      exponent = exponent <= 1_000
          ? exponent * 10 + sql.charAt(position) - '0' : 10_001;
      digits++;
      position++;
    }
    end = position;
    value = negative ? -exponent : exponent;
    return digits == 0 || exponent > 10_000
        ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }

  int value() { return value; }
  int end() { return end; }
}
