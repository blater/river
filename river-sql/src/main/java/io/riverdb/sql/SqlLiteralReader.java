package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Owns fixed literal, descriptor, text, temporal, and parameter parsing. */
final class SqlLiteralReader {
  /* Scratch is bounded by row/value admission, not by a VARCHAR declaration. */
  private final char[] textCharacters = new char[Utf8Text.MAXIMUM_BUFFER_CHARACTERS];
  private final SqlParserInput input;
  private final SqlTemporalParser temporal;
  private final SqlTypeDescriptorReader types;
  private final SqlParameterReader parameters = new SqlParameterReader();
  private final SqlIntegralLiteralReader integers;
  private final SqlDecimalLiteralReader decimals;
  private final SqlApproximateLiteralReader approximates;
  private SqlCommand command;
  private SqlParameterMarkers parameterMarkers;

  SqlLiteralReader(SqlParserInput parserInput) {
    input = parserInput;
    temporal = new SqlTemporalParser(input);
    types = new SqlTypeDescriptorReader(input, temporal, this);
    integers = new SqlIntegralLiteralReader(input);
    decimals = new SqlDecimalLiteralReader(input);
    approximates = new SqlApproximateLiteralReader(input);
  }

  void command(SqlCommand activeCommand) {
    command = activeCommand;
  }

  void beginParameters(SqlParameterSource source) {
    parameters.begin(source);
  }

  void beginParameterMarkers(SqlParameterMarkers markers) {
    parameterMarkers = markers;
  }

  StatusCode finishParameters() {
    return parameters.finish();
  }

  void clearParameters() {
    parameters.reset();
    parameterMarkers = null;
  }

  StatusCode number(CharSequence sql, SqlParser.LongResult result) {
    StatusCode status = integers.read(sql, result);
    if (status.isOk()) result.high = result.value >> 63;
    return status;
  }

  StatusCode literal(CharSequence sql, SqlParser.LongResult result) {
    result.high = 0;
    result.nullValue = false;
    result.parameter = false;
    if (input.consumeCharacter(sql, '?')) {
      int marker = input.position() - 1;
      int ordinal = SqlParameterOrdinalSource.originalOrdinal(
          sql, marker, parameterMarkers);
      if (parameterMarkers != null) {
        if (ordinal < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
        result.value = ordinal;
        result.parameter = true;
        result.varchar = false;
        result.textScalars = 0;
        result.typeDescriptor = 0;
        return StatusCode.OK;
      }
      return parameters.read(command, textCharacters, result, ordinal);
    }
    if (temporal.starts(sql)) return temporal.literal(sql, result);
    if (startsText(sql)) return packedText(sql, result);
    if (input.consumeKeyword(sql, "TRUE")) return setBoolean(result, true);
    if (input.consumeKeyword(sql, "FALSE")) return setBoolean(result, false);
    if (approximates.starts(sql)) return approximates.read(sql, result);
    if (decimals.starts(sql)) return decimals.read(sql, result);
    int start = input.position();
    StatusCode status = number(sql, result);
    if (status.isOk()) return status;
    input.position(start);
    return decimals.readIntegral(sql, result);
  }

  StatusCode packedText(CharSequence sql, SqlParser.LongResult result) {
    input.skipSpaces(sql);
    result.nullValue = false;
    result.varchar = true;
    result.typeDescriptor = 0;
    int position = input.position();
    if (position >= sql.length() || sql.charAt(position) != '\'') {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    position++;
    int length = 0;
    while (position < sql.length()) {
      char character = sql.charAt(position++);
      if (character == '\'') {
        if (position < sql.length() && sql.charAt(position) == '\'') {
          position++;
        } else {
          input.position(position);
          return storeText(result, length);
        }
      }
      if (length >= textCharacters.length) {
        input.position(position);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      textCharacters[length++] = character;
    }
    input.position(position);
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  boolean startsText(CharSequence sql) {
    int start = input.position();
    input.skipSpaces(sql);
    boolean text = input.position() < sql.length() && sql.charAt(input.position()) == '\'';
    input.position(start);
    return text;
  }

  long textSuccessor(long handle) {
    return command.textSuccessor(handle);
  }

  int compareText(long left, long right) {
    return command.compareText(left, right);
  }

  boolean startsNumber(CharSequence sql) {
    input.skipSpaces(sql);
    int position = input.position();
    if (position >= sql.length()) return false;
    char first = sql.charAt(position);
    return SqlParserInput.digit(first)
        || first == '-' && position + 1 < sql.length()
            && SqlParserInput.digit(sql.charAt(position + 1));
  }

  boolean startsLiteral(CharSequence sql) {
    int start = input.position();
    boolean literal = startsText(sql)
        || startsNumber(sql)
        || input.consumeCharacter(sql, '?')
        || input.consumeKeyword(sql, "TRUE")
        || input.consumeKeyword(sql, "FALSE")
        || temporal.starts(sql);
    input.position(start);
    return literal;
  }

  StatusCode typeDescriptor(CharSequence sql, SqlParser.LongResult result) {
    return types.read(sql, result);
  }

  private StatusCode storeText(SqlParser.LongResult result, int length) {
    int scalars = Utf8Text.scalarCount(textCharacters, 0, length);
    if (scalars < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (scalars > Utf8Text.MAXIMUM_SCALARS) return StatusCode.RESOURCE_EXHAUSTED;
    result.value = command.storeText(textCharacters, 0, length);
    result.high = 0;
    result.textScalars = scalars;
    result.typeDescriptor = SqlTypeDescriptor.varchar(Math.max(1, scalars));
    return result.value == SqlCommand.INVALID_TEXT_HANDLE
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  private static StatusCode setBoolean(
      SqlParser.LongResult result, boolean value) {
    result.value = value ? 1 : 0;
    result.high = 0;
    result.varchar = false;
    result.textScalars = 0;
    result.typeDescriptor = SqlTypeDescriptor.BOOLEAN;
    return StatusCode.OK;
  }

}
