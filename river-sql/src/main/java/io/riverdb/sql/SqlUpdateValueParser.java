package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses one bounded UPDATE assignment value into the command-owned primitive carrier. */
final class SqlUpdateValueParser {
  private final SqlParserInput input;
  private final SqlParser.LongResult literal = new SqlParser.LongResult();
  private int operator;
  private int expressionDescriptor;

  SqlUpdateValueParser(SqlParserInput parserInput) {
    input = parserInput;
  }

  StatusCode parse(CharSequence sql, SqlCommand command) {
    boolean nullValue = input.consumeKeyword(sql, "NULL");
    boolean defaultValue = !nullValue && input.consumeKeyword(sql, "DEFAULT");
    operator = SqlCommand.UPDATE_LITERAL;
    expressionDescriptor = 0;
    StatusCode status = nullValue || defaultValue
        ? StatusCode.OK : expression(sql, command);
    if (!status.isOk()) {
      return status;
    }
    long value = nullValue || defaultValue ? 0 : literal.value;
    int descriptor = nullValue || defaultValue ? 0 : literal.typeDescriptor;
    command.appendUpdate(
        value,
        nullValue,
        defaultValue,
        descriptor,
        operator,
        expressionDescriptor);
    return StatusCode.OK;
  }

  private StatusCode expression(CharSequence sql, SqlCommand command) {
    if (input.startsLiteral(sql)) {
      return input.literal(sql, literal);
    }
    if (input.consumeKeyword(sql, "CAST")) {
      return cast(sql, command);
    }
    int unary = unaryFunction(sql);
    if (unary != 0) {
      return unary(sql, command, unary);
    }
    int scaled = scaleFunction(sql);
    if (scaled != 0) {
      return scaled(sql, command, scaled);
    }
    if (input.consumeCharacter(sql, '-')) {
      operator = SqlCommand.UPDATE_NEGATE;
      clearLiteral();
      return source(sql, command);
    }
    input.consumeCharacter(sql, '+');
    StatusCode status = source(sql, command);
    if (!status.isOk()) {
      return status;
    }
    operator = binaryOperator(sql);
    return operator == 0
        ? StatusCode.INVALID_EXTERNAL_INPUT : input.literal(sql, literal);
  }

  private StatusCode cast(CharSequence sql, SqlCommand command) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) {
      status = source(sql, command);
    }
    if (status.isOk()) {
      status = input.requireKeyword(sql, "AS");
    }
    if (status.isOk()) {
      status = input.typeDescriptor(sql, literal);
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, ')');
    }
    if (status.isOk()) {
      operator = SqlCommand.UPDATE_CAST;
      expressionDescriptor = literal.typeDescriptor;
      clearLiteral();
    }
    return status;
  }

  private StatusCode unary(
      CharSequence sql, SqlCommand command, int unaryOperator) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) {
      status = source(sql, command);
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, ')');
    }
    if (status.isOk()) {
      operator = unaryOperator;
      clearLiteral();
    }
    return status;
  }

  private StatusCode scaled(
      CharSequence sql, SqlCommand command, int scaledOperator) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) {
      status = source(sql, command);
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, ',');
    }
    if (status.isOk()) {
      status = input.number(sql, literal);
    }
    if (status.isOk()
        && (literal.value < 0
            || literal.value > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, ')');
    }
    if (status.isOk()) {
      operator = scaledOperator;
    }
    return status;
  }

  private StatusCode source(CharSequence sql, SqlCommand command) {
    SqlIdentifier source = command.writableNextUpdateSourceColumnName();
    return source == null
        ? StatusCode.RESOURCE_EXHAUSTED : input.identifier(sql, source);
  }

  private int unaryFunction(CharSequence sql) {
    if (input.consumeKeyword(sql, "ABS")) {
      return SqlCommand.UPDATE_ABSOLUTE;
    }
    if (input.consumeKeyword(sql, "CEIL")) {
      return SqlCommand.UPDATE_CEILING;
    }
    return input.consumeKeyword(sql, "FLOOR") ? SqlCommand.UPDATE_FLOOR : 0;
  }

  private int scaleFunction(CharSequence sql) {
    if (input.consumeKeyword(sql, "ROUND")) {
      return SqlCommand.UPDATE_ROUND;
    }
    return input.consumeKeyword(sql, "TRUNCATE") ? SqlCommand.UPDATE_TRUNCATE : 0;
  }

  private int binaryOperator(CharSequence sql) {
    if (input.consumeCharacter(sql, '+')) {
      return SqlCommand.UPDATE_ADD;
    }
    if (input.consumeCharacter(sql, '-')) {
      return SqlCommand.UPDATE_SUBTRACT;
    }
    if (input.consumeCharacter(sql, '*')) {
      return SqlCommand.UPDATE_MULTIPLY;
    }
    if (input.consumeCharacter(sql, '/')) {
      return SqlCommand.UPDATE_DIVIDE;
    }
    return input.consumeCharacter(sql, '%') ? SqlCommand.UPDATE_REMAINDER : 0;
  }

  private void clearLiteral() {
    literal.value = 0;
    literal.typeDescriptor = 0;
  }
}
