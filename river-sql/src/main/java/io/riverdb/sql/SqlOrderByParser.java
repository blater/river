package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses one bounded ORDER BY expression list. */
final class SqlOrderByParser {
  private final SqlParserInput input;
  private final SqlOrderByNames names = new SqlOrderByNames();

  SqlOrderByParser(SqlParserInput parserInput) {
    input = parserInput;
  }

  StatusCode parse(CharSequence sql, SqlCommand command) {
    if (!input.consumeKeyword(sql, "ORDER")) return StatusCode.OK;
    StatusCode status = input.requireKeyword(sql, "BY");
    int expression = 0;
    do {
      status = parseExpression(sql, command, expression, status);
      if (status.isOk()) expression++;
    } while (status.isOk() && input.consumeCharacter(sql, ','));
    return status;
  }

  private StatusCode parseExpression(
      CharSequence sql, SqlCommand command, int expression, StatusCode prior) {
    SqlIdentifier name = prior.isOk() ? command.orderBy.append() : null;
    if (name == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = parseName(sql, command, name, expression);
    if (status.isOk()) status = parseDirection(sql, command, expression);
    return status;
  }

  private StatusCode parseName(
      CharSequence sql, SqlCommand command, SqlIdentifier name, int expression) {
    StatusCode status = input.identifier(sql, name);
    if (status.isOk() && input.consumeCharacter(sql, '.')) {
      SqlIdentifier qualifier = command.orderBy.qualifier(expression);
      qualifier.copyFrom(name);
      name.reset();
      status = input.identifier(sql, name);
    }
    if (status.isOk() && !names.valid(
        command, command.orderBy().qualifier(expression), name)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status;
  }

  private StatusCode parseDirection(CharSequence sql, SqlCommand command, int expression) {
    boolean descending = false;
    if (!input.consumeKeyword(sql, "ASC")) descending = input.consumeKeyword(sql, "DESC");
    command.setDescendingOrder(expression, descending);
    return StatusCode.OK;
  }
}
