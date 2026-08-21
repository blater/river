package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses the bounded ORDER BY and LIMIT tail of a SELECT. */
final class SqlSelectTailParser {
  private final SqlParser parser;
  private final SqlParserInput input;
  private final SqlParser.LongResult numberResult = new SqlParser.LongResult();

  SqlSelectTailParser(SqlParser parent, SqlParserInput parserInput) {
    parser = parent;
    input = parserInput;
  }

  StatusCode parse(CharSequence sql, SqlCommand result) {
    StatusCode status = parseOrder(sql, result);
    return status.isOk() ? parseLimit(sql, result) : status;
  }

  private StatusCode parseOrder(CharSequence sql, SqlCommand result) {
    SqlCommandType type = result.type();
    if (!input.consumeKeyword(sql, "ORDER")) {
      return StatusCode.OK;
    }
    StatusCode status = input.requireKeyword(sql, "BY");
    if (status.isOk()) {
      status = isGroupAggregate(type) || type == SqlCommandType.DISTINCT_SCAN
          ? parser.matchingEitherIdentifier(
              sql, result.firstColumnName(), result.columnOutputName(0))
          : parseOrderName(sql, result);
    }
    if (status.isOk()
        && (isGroupAggregate(type) || type == SqlCommandType.DISTINCT_SCAN)) {
      CharSequence output = result.columnOutputName(0);
      result.writableOrderColumnName().copyFrom(
          output.length() > 0 ? output : result.firstColumnName());
    }
    if (status.isOk() && input.consumeKeyword(sql, "ASC")) {
      result.setDescendingOrder(false);
    } else if (status.isOk() && input.consumeKeyword(sql, "DESC")) {
      if (isGroupAggregate(type) || type == SqlCommandType.DISTINCT_SCAN) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      } else {
        result.setDescendingOrder(true);
      }
    }
    return status;
  }

  private StatusCode parseOrderName(CharSequence sql, SqlCommand result) {
    StatusCode status = input.identifier(sql, result.writableOrderColumnName());
    return status.isOk() && input.consumeCharacter(sql, '.')
        ? StatusCode.FEATURE_NOT_SUPPORTED : status;
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

  private static boolean isGroupAggregate(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }
}
