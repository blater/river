package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses exact and approximate numeric type names. */
final class SqlNumericTypeReader {
  private final SqlParserInput input;
  private final SqlTypeParameters parameters;

  SqlNumericTypeReader(SqlParserInput parserInput, SqlLiteralReader literals) {
    input = parserInput;
    parameters = new SqlTypeParameters(parserInput, literals);
  }

  StatusCode read(CharSequence sql, SqlParser.LongResult result) {
    if (input.consumeKeyword(sql, "BIGINT")) {
      return SqlTypeParameters.set(result, SqlTypeDescriptor.BIGINT);
    }
    if (input.consumeKeyword(sql, "SMALLINT")) {
      return SqlTypeParameters.set(result, SqlTypeDescriptor.SMALLINT);
    }
    if (input.consumeKeyword(sql, "INTEGER") || input.consumeKeyword(sql, "INT")) {
      return SqlTypeParameters.set(result, SqlTypeDescriptor.INTEGER);
    }
    if (input.consumeKeyword(sql, "REAL")) {
      return SqlTypeParameters.set(result, SqlTypeDescriptor.REAL);
    }
    if (input.consumeKeyword(sql, "DOUBLE")) {
      input.consumeKeyword(sql, "PRECISION");
      return SqlTypeParameters.set(result, SqlTypeDescriptor.DOUBLE);
    }
    if (input.consumeKeyword(sql, "FLOAT")) return parameters.floating(sql, result);
    boolean decimal = input.consumeKeyword(sql, "DECIMAL")
        || input.consumeKeyword(sql, "NUMERIC") || input.consumeKeyword(sql, "DEC");
    if (!decimal) return StatusCode.CONFLICT;
    return input.consumeCharacter(sql, '(')
        ? parameters.parameterized(sql, result, false)
        : SqlTypeParameters.set(result, SqlTypeDescriptor.decimal(10, 0));
  }
}
