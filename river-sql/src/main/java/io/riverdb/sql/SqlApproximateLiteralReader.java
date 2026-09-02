package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Detects and parses scientific-notation approximate literals. */
final class SqlApproximateLiteralReader {
  private final SqlParserInput input;
  private final SqlApproximateSignificand significand = new SqlApproximateSignificand();
  private final SqlApproximateExponent exponent = new SqlApproximateExponent();

  SqlApproximateLiteralReader(SqlParserInput parserInput) {
    input = parserInput;
  }

  boolean starts(CharSequence sql) {
    int start = input.position();
    input.skipSpaces(sql);
    int position = skipSignAndDigits(sql, input.position());
    if (position < sql.length() && sql.charAt(position) == '.') {
      position = skipDigits(sql, position + 1);
    }
    boolean approximate = position < sql.length()
        && (sql.charAt(position) == 'e' || sql.charAt(position) == 'E');
    input.position(start);
    return approximate;
  }

  StatusCode read(CharSequence sql, SqlParser.LongResult result) {
    result.varchar = false;
    result.textScalars = 0;
    input.skipSpaces(sql);
    StatusCode status = significand.read(sql, input.position());
    if (!status.isOk()) {
      input.position(significand.end());
      return status;
    }
    status = exponent.read(sql, significand.end() + 1);
    input.position(exponent.end());
    if (!status.isOk()) return status;
    double value = significand.value()
        * Math.pow(10.0d, exponent.value() - significand.fractionalDigits());
    if (significand.negative()) value = -value;
    long bits = SqlApproximateNumeric.doubleBits(value);
    if (!SqlApproximateNumeric.validDoubleBits(bits)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = bits;
    result.typeDescriptor = SqlTypeDescriptor.DOUBLE;
    return StatusCode.OK;
  }

  private static int skipSignAndDigits(CharSequence sql, int position) {
    if (position < sql.length() && sql.charAt(position) == '-') position++;
    return skipDigits(sql, position);
  }

  private static int skipDigits(CharSequence sql, int position) {
    while (position < sql.length() && SqlParserInput.digit(sql.charAt(position))) position++;
    return position;
  }
}
