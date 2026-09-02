package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Reusable parse result for one scientific-literal significand. */
final class SqlApproximateSignificand {
  private double value;
  private int fractionalDigits;
  private int end;
  private boolean negative;

  StatusCode read(CharSequence sql, int start) {
    int position = start;
    negative = position < sql.length() && sql.charAt(position) == '-';
    if (negative) position++;
    value = 0;
    fractionalDigits = 0;
    int digits = 0;
    boolean point = false;
    while (position < sql.length()) {
      char character = sql.charAt(position);
      if (SqlParserInput.digit(character)) {
        value = value * 10.0d + character - '0';
        if (point) fractionalDigits++;
        digits++;
        position++;
      } else if (character == '.' && !point) {
        point = true;
        position++;
      } else break;
    }
    end = position;
    return digits > 0 && position < sql.length()
        && (sql.charAt(position) == 'e' || sql.charAt(position) == 'E')
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  double value() { return value; }
  int fractionalDigits() { return fractionalDigits; }
  int end() { return end; }
  boolean negative() { return negative; }
}
