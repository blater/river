package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses one exact or temporal type descriptor through the shared SQL cursor. */
final class SqlTypeDescriptorReader {
  private final SqlParserInput input;
  private final SqlTemporalParser temporal;
  private final SqlLiteralReader literals;

  SqlTypeDescriptorReader(
      SqlParserInput parserInput,
      SqlTemporalParser temporalParser,
      SqlLiteralReader literalReader) {
    input = parserInput;
    temporal = temporalParser;
    literals = literalReader;
  }

  StatusCode read(CharSequence sql, SqlParser.LongResult result) {
    result.nullValue = false;
    if (input.consumeKeyword(sql, "BIGINT")) {
      return set(result, SqlTypeDescriptor.BIGINT);
    }
    if (input.consumeKeyword(sql, "BOOLEAN")) {
      return set(result, SqlTypeDescriptor.BOOLEAN);
    }
    if (temporal.starts(sql)) return temporal.typeDescriptor(sql, result);
    boolean varchar = input.consumeKeyword(sql, "VARCHAR");
    if (!varchar && !input.consumeKeyword(sql, "DECIMAL")) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return parameterized(sql, result, varchar);
  }

  private StatusCode parameterized(
      CharSequence sql, SqlParser.LongResult result, boolean varchar) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) status = literals.number(sql, result);
    int first = status.isOk() && result.value <= Integer.MAX_VALUE
        ? (int) result.value : -1;
    int second = 0;
    if (status.isOk() && !varchar && input.consumeCharacter(sql, ',')) {
      status = literals.number(sql, result);
      second = status.isOk() && result.value <= Integer.MAX_VALUE
          ? (int) result.value : -1;
    }
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    int descriptor = varchar
        ? SqlTypeDescriptor.varchar(first)
        : SqlTypeDescriptor.decimal(first, second);
    if (status.isOk() && descriptor == 0) status = StatusCode.INVALID_EXTERNAL_INPUT;
    if (status.isOk()) set(result, descriptor);
    return status;
  }

  private static StatusCode set(
      SqlParser.LongResult result, int descriptor) {
    result.value = descriptor;
    result.typeDescriptor = descriptor;
    return StatusCode.OK;
  }
}
