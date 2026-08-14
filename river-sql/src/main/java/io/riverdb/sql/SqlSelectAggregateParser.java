package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses aggregate projections, sources, and bounded HAVING comparisons. */
final class SqlSelectAggregateParser {
  private final SqlParser parser;
  private final SqlSelectParser selects;
  private final SqlParserInput input;
  private final SqlParser.LongResult numberResult = new SqlParser.LongResult();

  SqlSelectAggregateParser(
      SqlParser parent, SqlSelectParser selectParser, SqlParserInput parserInput) {
    parser = parent;
    selects = selectParser;
    input = parserInput;
  }

  StatusCode valueAggregate(CharSequence sql, SqlCommand result) {
    StatusCode status = requireCharacter(sql, '(');
    if (status.isOk()) {
      status = aggregateColumn(sql, result);
    }
    return status.isOk() ? aggregateSource(sql, result) : status;
  }

  StatusCode aggregateColumn(CharSequence sql, SqlCommand result) {
    StatusCode status = selectColumnIdentifier(sql, result);
    if (status.isOk()
        && (result.isNullProjection(0)
            || result.columnAlias(0).length() > 0)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    return status.isOk() ? optionalColumnAlias(sql, result, 0) : status;
  }

  StatusCode groupAggregateColumn(CharSequence sql, SqlCommand result) {
    int columnIndex = result.columnCount();
    StatusCode status = selectColumnIdentifier(sql, result);
    if (status.isOk()
        && (result.isNullProjection(columnIndex)
            || result.columnAlias(columnIndex).length() > 0)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    return status.isOk()
        ? optionalColumnAlias(sql, result, columnIndex) : status;
  }

  StatusCode groupHaving(
      CharSequence sql,
      SqlCommand result,
      SqlCommandType type) {
    String function = type == SqlCommandType.GROUP_SUM
        ? "SUM" : type == SqlCommandType.GROUP_AVG
            ? "AVG" : type == SqlCommandType.GROUP_MIN
            ? "MIN" : type == SqlCommandType.GROUP_MAX ? "MAX" : "COUNT";
    StatusCode status = requireKeyword(sql, function);
    if (status.isOk()) {
      status = requireCharacter(sql, '(');
    }
    if (status.isOk()) status = groupHavingOperand(sql, result, type);
    if (status.isOk()) {
      status = requireCharacter(sql, ')');
    }
    return status.isOk() ? parseGroupHavingComparison(sql, result) : status;
  }

  private StatusCode parseGroupHavingComparison(
      CharSequence sql, SqlCommand result) {
    SqlComparison comparison = comparisonOperator(sql);
    if (comparison == null
        || comparison == SqlComparison.HALF_OPEN_RANGE
        || comparison == SqlComparison.IN
        || comparison == SqlComparison.NOT_IN) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = literal(sql, numberResult);
    if (status.isOk()) {
      result.setGroupHaving(
          comparison, numberResult.value, numberResult.typeDescriptor);
    }
    return status;
  }

  private StatusCode groupHavingOperand(
      CharSequence sql, SqlCommand result, SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        ? requireCharacter(sql, '*')
        : matchingIdentifier(sql, result.secondColumnName());
  }

  StatusCode aggregateSource(CharSequence sql, SqlCommand result) {
    StatusCode status = requireKeyword(sql, "FROM");
    if (status.isOk()) {
      status = identifier(sql, result.writableTableName());
    }
    if (status.isOk()) {
      status = optionalTableAlias(sql, result);
    }
    if (status.isOk() && consumeKeyword(sql, "WHERE")) {
      status = predicates(sql, result, false);
    }
    return status;
  }

  StatusCode finishGroupOrPoint(CharSequence sql, SqlCommand result) {
    SqlCommandType type = result.type();
    if (isGroupAggregate(type)) {
      StatusCode status = requireKeyword(sql, "GROUP");
      if (status.isOk()) {
        status = requireKeyword(sql, "BY");
      }
      if (status.isOk()) {
        status = matchingIdentifier(sql, result.firstColumnName());
      }
      if (status.isOk() && consumeKeyword(sql, "HAVING")) {
        status = groupHaving(sql, result, type);
      }
      return status;
    }
    if (type == SqlCommandType.SCAN
        && result.hasPredicate()
        && result.isEqualityPredicate()) {
      result.set(SqlCommandType.SELECT, 0, 0);
    }
    return StatusCode.OK;
  }

  private static boolean isGroupAggregate(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

  private StatusCode selectColumnIdentifier(CharSequence sql, SqlCommand result) {
    return selects.selectColumnIdentifier(sql, result);
  }

  private StatusCode optionalColumnAlias(
      CharSequence sql, SqlCommand result, int columnIndex) {
    return selects.optionalColumnAlias(sql, result, columnIndex);
  }

  private StatusCode predicates(
      CharSequence sql, SqlCommand result, boolean qualified) {
    return parser.predicates(sql, result, qualified);
  }

  private SqlComparison comparisonOperator(CharSequence sql) {
    return parser.comparisonOperator(sql);
  }

  private StatusCode matchingIdentifier(CharSequence sql, CharSequence expected) {
    return parser.matchingIdentifier(sql, expected);
  }

  private StatusCode optionalTableAlias(CharSequence sql, SqlCommand result) {
    return parser.optionalTableAlias(sql, result);
  }

  private StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    return input.identifier(sql, result);
  }

  private StatusCode literal(CharSequence sql, SqlParser.LongResult result) {
    return input.literal(sql, result);
  }

  private StatusCode requireKeyword(CharSequence sql, String keyword) {
    return input.requireKeyword(sql, keyword);
  }

  private boolean consumeKeyword(CharSequence sql, String keyword) {
    return input.consumeKeyword(sql, keyword);
  }

  private StatusCode requireCharacter(CharSequence sql, char expected) {
    return input.requireCharacter(sql, expected);
  }
}
