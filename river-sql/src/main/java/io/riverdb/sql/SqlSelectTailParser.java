package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses the bounded ORDER BY, LIMIT, and locking tail of a SELECT. */
final class SqlSelectTailParser {
  private final SqlParserInput input;
  private final SqlOrderByParser order;
  private final SqlParser.LongResult numberResult = new SqlParser.LongResult();

  SqlSelectTailParser(SqlParser parent, SqlParserInput parserInput) {
    input = parserInput;
    order = new SqlOrderByParser(parserInput);
  }

  StatusCode parse(CharSequence sql, SqlCommand result) {
    StatusCode status = order.parse(sql, result);
    if (status.isOk()) status = parseLimit(sql, result);
    return status.isOk() ? parseLocking(sql, result) : status;
  }

  private StatusCode parseLimit(CharSequence sql, SqlCommand result) {
    if (!input.consumeKeyword(sql, "LIMIT")) {
      return StatusCode.OK;
    }
    StatusCode status = input.number(sql, numberResult);
    if (status.isOk() && numberResult.value < 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      result.setRowLimit(numberResult.value);
    }
    return status;
  }

  private StatusCode parseLocking(CharSequence sql, SqlCommand result) {
    if (!input.consumeKeyword(sql, "FOR")) return StatusCode.OK;
    StatusCode status = input.requireKeyword(sql, "UPDATE");
    if (!status.isOk()) return status;
    if (result.type() != SqlCommandType.SCAN
        && result.type() != SqlCommandType.SELECT
        || result.joinChain() != null
        || result.aggregateInvocationCount() != 0
        || result.groupExpressionCount() != 0) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    result.setSelectForUpdate();
    return StatusCode.OK;
  }
}
