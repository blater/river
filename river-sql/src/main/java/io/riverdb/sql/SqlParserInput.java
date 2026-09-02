package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Owns the mutable cursor and fixed literal scratch for one parser invocation. */
final class SqlParserInput {
  private final SqlLiteralReader literals = new SqlLiteralReader(this);
  private int position;

  void reset(SqlCommand activeCommand) {
    literals.command(activeCommand);
    position = 0;
  }

  void beginParameters(SqlParameterSource source) {
    literals.beginParameters(source);
  }

  void beginParameterMarkers(SqlParameterMarkers markers) {
    literals.beginParameterMarkers(markers);
  }

  StatusCode finishParameters() {
    return literals.finishParameters();
  }

  void clearParameters() {
    literals.clearParameters();
  }

  int position() {
    return position;
  }

  void position(int next) {
    position = next;
  }

  StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    skipSpaces(sql);
    if (position >= sql.length() || !identifierStart(sql.charAt(position))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    while (position < sql.length() && identifierPart(sql.charAt(position))) {
      if (result.length() >= SqlIdentifier.MAXIMUM_LENGTH) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      result.append(lower(sql.charAt(position++)));
    }
    return StatusCode.OK;
  }

  StatusCode number(CharSequence sql, SqlParser.LongResult result) {
    return literals.number(sql, result);
  }

  StatusCode literal(CharSequence sql, SqlParser.LongResult result) {
    return literals.literal(sql, result);
  }

  StatusCode packedText(CharSequence sql, SqlParser.LongResult result) {
    return literals.packedText(sql, result);
  }

  boolean startsText(CharSequence sql) {
    return literals.startsText(sql);
  }

  long textSuccessor(long handle) {
    return literals.textSuccessor(handle);
  }

  int compareText(long left, long right) {
    return literals.compareText(left, right);
  }

  boolean startsNumber(CharSequence sql) {
    return literals.startsNumber(sql);
  }

  boolean startsLiteral(CharSequence sql) {
    return literals.startsLiteral(sql);
  }

  StatusCode typeDescriptor(CharSequence sql, SqlParser.LongResult result) {
    return literals.typeDescriptor(sql, result);
  }

  StatusCode requireKeyword(CharSequence sql, String keyword) {
    return consumeKeyword(sql, keyword)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  boolean consumeKeyword(CharSequence sql, String keyword) {
    skipSpaces(sql);
    if (sql.length() - position < keyword.length()) {
      return false;
    }
    for (int index = 0; index < keyword.length(); index++) {
      if (upper(sql.charAt(position + index)) != keyword.charAt(index)) {
        return false;
      }
    }
    int end = position + keyword.length();
    if (end < sql.length() && identifierPart(sql.charAt(end))) {
      return false;
    }
    position = end;
    return true;
  }

  StatusCode requireCharacter(CharSequence sql, char expected) {
    skipSpaces(sql);
    if (position >= sql.length() || sql.charAt(position) != expected) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    position++;
    return StatusCode.OK;
  }

  boolean consumeCharacter(CharSequence sql, char expected) {
    skipSpaces(sql);
    if (position >= sql.length() || sql.charAt(position) != expected) {
      return false;
    }
    position++;
    return true;
  }

  boolean finish(CharSequence sql) {
    skipSpaces(sql);
    if (position < sql.length() && sql.charAt(position) == ';') {
      position++;
      skipSpaces(sql);
    }
    return position == sql.length();
  }

  void skipSpaces(CharSequence sql) {
    while (position < sql.length() && Character.isWhitespace(sql.charAt(position))) {
      position++;
    }
  }

  static char upper(char character) {
    return character >= 'a' && character <= 'z' ? (char) (character - 32) : character;
  }

  static boolean identifierStart(char character) {
    return character >= 'A' && character <= 'Z'
        || character >= 'a' && character <= 'z'
        || character == '_';
  }

  private static char lower(char character) {
    return character >= 'A' && character <= 'Z' ? (char) (character + 32) : character;
  }

  private static boolean identifierPart(char character) {
    return identifierStart(character) || digit(character);
  }

  static boolean digit(char character) {
    return character >= '0' && character <= '9';
  }
}
