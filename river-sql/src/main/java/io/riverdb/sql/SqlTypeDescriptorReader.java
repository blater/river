package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses one scalar type descriptor through the shared SQL cursor. */
final class SqlTypeDescriptorReader {
  private final SqlParserInput input;
  private final SqlTemporalParser temporal;
  private final SqlNumericTypeReader numerics;
  private final SqlTypeParameters parameters;

  SqlTypeDescriptorReader(
      SqlParserInput parserInput,
      SqlTemporalParser temporalParser,
      SqlLiteralReader literalReader) {
    input = parserInput;
    temporal = temporalParser;
    numerics = new SqlNumericTypeReader(parserInput, literalReader);
    parameters = new SqlTypeParameters(parserInput, literalReader);
  }

  StatusCode read(CharSequence sql, SqlParser.LongResult result) {
    result.nullValue = false;
    StatusCode numeric = numerics.read(sql, result);
    if (numeric != StatusCode.CONFLICT) return numeric;
    if (input.consumeKeyword(sql, "BOOLEAN")) {
      return SqlTypeParameters.set(result, SqlTypeDescriptor.BOOLEAN);
    }
    if (temporal.starts(sql)) return temporal.typeDescriptor(sql, result);
    if (input.consumeKeyword(sql, "VARCHAR")) {
      return parameters.parameterized(sql, result, true);
    }
    /*
     * River stores text in one canonical variable-width UTF-8 lane. CHAR is
     * therefore normalized to that lane at the type boundary; its declared
     * scalar width remains enforced by the same descriptor and no fixed-width
     * padding is introduced into keys or rows.
     */
    return input.consumeKeyword(sql, "CHAR")
        || input.consumeKeyword(sql, "CHARACTER")
        ? parameters.parameterized(sql, result, true)
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
