package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses bounded VARCHAR/CHAR, DECIMAL, and FLOAT type parameters. */
final class SqlTypeParameters {
  private final SqlParserInput input;
  private final SqlLiteralReader literals;

  SqlTypeParameters(SqlParserInput parserInput, SqlLiteralReader literalReader) {
    input = parserInput;
    literals = literalReader;
  }

  StatusCode parameterized(
      CharSequence sql, SqlParser.LongResult result, boolean varchar) {
    StatusCode status = varchar ? input.requireCharacter(sql, '(') : StatusCode.OK;
    if (status.isOk()) status = literals.number(sql, result);
    int first = integer(status, result.value);
    int second = 0;
    if (status.isOk() && !varchar && input.consumeCharacter(sql, ',')) {
      status = literals.number(sql, result);
      second = integer(status, result.value);
    }
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    int descriptor = varchar
        ? SqlTypeDescriptor.varchar(first)
        : SqlTypeDescriptor.decimal(first, second);
    if (status.isOk() && descriptor == 0) status = StatusCode.INVALID_EXTERNAL_INPUT;
    return status.isOk() ? set(result, descriptor) : status;
  }

  StatusCode floating(CharSequence sql, SqlParser.LongResult result) {
    if (!input.consumeCharacter(sql, '(')) {
      return set(result, SqlTypeDescriptor.DOUBLE);
    }
    StatusCode status = literals.number(sql, result);
    int precision = integer(status, result.value);
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    int descriptor = precision >= 1 && precision <= 24 ? SqlTypeDescriptor.REAL
        : precision >= 25 && precision <= 53 ? SqlTypeDescriptor.DOUBLE : 0;
    return status.isOk() && descriptor == 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : status.isOk() ? set(result, descriptor) : status;
  }

  private static int integer(StatusCode status, long value) {
    return status.isOk() && value <= Integer.MAX_VALUE ? (int) value : -1;
  }

  static StatusCode set(SqlParser.LongResult result, int descriptor) {
    result.value = descriptor;
    result.typeDescriptor = descriptor;
    return StatusCode.OK;
  }
}
