package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses root ORDER BY and LIMIT against the first set operand's output names. */
final class SqlSetTailParser {
  private final SqlSetQuerySource source = new SqlSetQuerySource();
  private final SqlParserInput input = new SqlParserInput();
  private final SqlParser.LongResult number = new SqlParser.LongResult();

  StatusCode parse(CharSequence sql, int start, int end, SqlQuery query) {
    source.set(sql, start, end);
    SqlCommand first = query.firstSetBlock();
    input.reset(first);
    StatusCode status = order(query, first);
    if (status.isOk() && input.consumeKeyword(source, "LIMIT")) {
      status = input.number(source, number);
      if (status.isOk() && number.value < 0) status = StatusCode.INVALID_EXTERNAL_INPUT;
      if (status.isOk()) query.setSetRowLimit(number.value);
    }
    if (!status.isOk()) return status;
    return input.finish(source) ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode order(SqlQuery query, SqlCommand first) {
    if (!input.consumeKeyword(source, "ORDER")) return StatusCode.OK;
    StatusCode status = input.requireKeyword(source, "BY");
    int expression = 0;
    do {
      SqlIdentifier name = status.isOk() ? query.appendSetOrder() : null;
      status = name == null ? StatusCode.RESOURCE_EXHAUSTED : input.identifier(source, name);
      if (status.isOk() && input.consumeCharacter(source, '.')) {
        status = StatusCode.FEATURE_NOT_SUPPORTED;
      }
      if (status.isOk() && !SqlSetExpressionValidation.selected(first, name)) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      boolean descending = status.isOk() && input.consumeKeyword(source, "DESC");
      if (status.isOk() && !descending) input.consumeKeyword(source, "ASC");
      if (status.isOk()) query.setSetOrderDescending(expression++, descending);
    } while (status.isOk() && input.consumeCharacter(source, ','));
    return status;
  }
}
