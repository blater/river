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
      SqlIdentifier name = status.isOk() ? command.writableNextOrderColumnName() : null;
      status = name == null ? StatusCode.RESOURCE_EXHAUSTED : input.identifier(sql, name);
      if (status.isOk() && input.consumeCharacter(sql, '.')) {
        SqlIdentifier qualifier = command.writableOrderColumnTableName(expression);
        qualifier.copyFrom(name);
        name.reset();
        status = input.identifier(sql, name);
      }
      if (status.isOk() && !names.valid(
          command, command.orderColumnTableName(expression), name)) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      boolean descending = false;
      if (status.isOk() && input.consumeKeyword(sql, "ASC")) descending = false;
      else if (status.isOk() && input.consumeKeyword(sql, "DESC")) descending = true;
      if (status.isOk()) command.setDescendingOrder(expression++, descending);
    } while (status.isOk() && input.consumeCharacter(sql, ','));
    return status;
  }
}
