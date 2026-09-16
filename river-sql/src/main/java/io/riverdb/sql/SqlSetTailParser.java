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
      status = parseExpression(source, query, first, expression, status);
      if (status.isOk()) expression++;
    } while (status.isOk() && input.consumeCharacter(source, ','));
    return status;
  }

  private StatusCode parseExpression(
      CharSequence sql, SqlQuery query, SqlCommand first, int expression, StatusCode prior) {
    SqlIdentifier name = prior.isOk() ? query.appendSetOrder() : null;
    if (name == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = input.identifier(sql, name);
    if (status.isOk()) status = rejectQualifier(sql);
    if (status.isOk()) status = selected(first, name);
    if (status.isOk()) setDirection(sql, query, expression);
    return status;
  }

  private StatusCode rejectQualifier(CharSequence sql) {
    return input.consumeCharacter(sql, '.')
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
  }

  private static StatusCode selected(SqlCommand first, SqlIdentifier name) {
    return SqlSetExpressionValidation.selected(first, name)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private void setDirection(CharSequence sql, SqlQuery query, int expression) {
    boolean descending = input.consumeKeyword(sql, "DESC");
    if (!descending) input.consumeKeyword(sql, "ASC");
    query.setSetOrderDescending(expression, descending);
  }
}
