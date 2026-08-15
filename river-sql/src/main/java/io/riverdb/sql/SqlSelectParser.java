package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses the complete bounded SELECT, join, grouping, ordering, and aggregate grammar. */
final class SqlSelectParser {
  private final SqlParser parser;
  private final SqlParserInput input;
  private final SqlScalarExpressionParser scalarExpressions;
  private final SqlSelectProjectionParser projections;
  private final SqlSelectAggregateParser aggregates;
  private final SqlJoinParser joins;
  private final SqlSelectTailParser tail;
  private final SqlParser.LongResult numberResult = new SqlParser.LongResult();
  private final SqlIdentifier identifierScratch = new SqlIdentifier();

  SqlSelectParser(
      SqlParser parent,
      SqlParserInput parserInput,
      SqlScalarExpressionParser expressionParser) {
    parser = parent;
    input = parserInput;
    scalarExpressions = expressionParser;
    aggregates = new SqlSelectAggregateParser(
        parent, this, parserInput, expressionParser);
    projections = new SqlSelectProjectionParser(
        this, parserInput, expressionParser, aggregates);
    joins = new SqlJoinParser(parent, parserInput);
    tail = new SqlSelectTailParser(parent, parserInput);
  }

  StatusCode parse(CharSequence sql, SqlCommand result) {
    if (consumeKeyword(sql, "NEXT")) {
      result.set(SqlCommandType.NEXT_SEQUENCE_VALUE, 0, 0);
      StatusCode status = requireKeyword(sql, "VALUE");
      if (status.isOk()) {
        status = requireKeyword(sql, "FOR");
      }
      return status.isOk()
          ? identifier(sql, result.writableSequenceName()) : status;
    }
    if (consumeKeyword(sql, "COUNT")) {
      return aggregates.scalarList(sql, result, SqlAggregateKind.COUNT);
    }
    if (consumeKeyword(sql, "SUM")) {
      return aggregates.scalarList(sql, result, SqlAggregateKind.SUM);
    }
    if (consumeKeyword(sql, "AVG")) {
      return aggregates.scalarList(sql, result, SqlAggregateKind.AVG);
    }
    if (consumeKeyword(sql, "MIN")) {
      return aggregates.scalarList(sql, result, SqlAggregateKind.MIN);
    }
    if (consumeKeyword(sql, "MAX")) {
      return aggregates.scalarList(sql, result, SqlAggregateKind.MAX);
    }
    if (!SqlSelectSourceDetector.hasRowSource(sql, input.position())
        && scalarExpressions.starts(sql)) {
      result.set(SqlCommandType.SCALAR_EXPRESSION, 0, 0);
      return scalarExpressions.parse(sql, result.scalarExpression());
    }
    return parseRowSelect(sql, result);
  }

  private StatusCode parseRowSelect(
      CharSequence sql, SqlCommand result) {
    boolean distinct = consumeKeyword(sql, "DISTINCT");
    result.set(
        distinct ? SqlCommandType.DISTINCT_SCAN : SqlCommandType.SCAN,
        0,
        0);
    StatusCode status = projections.parse(sql, result, distinct);
    if (!status.isOk()) {
      return status;
    }
    status = requireKeyword(sql, "FROM");
    if (!status.isOk()) {
      return status;
    }
    status = identifier(sql, result.writableTableName());
    if (!status.isOk()) {
      return status;
    }
    status = optionalTableAlias(sql, result);
    if (!status.isOk()) {
      return status;
    }
    if (!isGroupAggregate(result.type())) {
      status = joins.parseOptional(sql, result);
    }
    if (!status.isOk()) {
      return status;
    }
    if (consumeKeyword(sql, "WHERE")) {
      status = predicates(
          sql, result, result.type() == SqlCommandType.JOIN_SCAN);
    }
    if (!status.isOk()) {
      return status;
    }
    status = aggregates.finishGroupOrPoint(sql, result);
    if (!status.isOk()) {
      return status;
    }
    return tail.parse(sql, result);
  }

  StatusCode selectColumnIdentifier(CharSequence sql, SqlCommand result) {
    int columnIndex = result.columnCount();
    if (consumeKeyword(sql, "NULL")) {
      SqlIdentifier column = result.writableNextColumnName();
      if (column == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      setIdentifier(column, "null");
      result.markLastProjectionNull();
      return optionalColumnAlias(sql, result, columnIndex);
    }
    identifierScratch.reset();
    StatusCode status = identifier(sql, identifierScratch);
    SqlIdentifier column = status.isOk() ? result.writableNextColumnName() : null;
    if (!status.isOk()) {
      return status;
    }
    if (column == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (consumeCharacter(sql, '.')) {
      result.writableColumnTableName(result.columnCount() - 1).copyFrom(identifierScratch);
      status = identifier(sql, column);
    } else {
      column.copyFrom(identifierScratch);
    }
    return status.isOk()
        ? optionalColumnAlias(sql, result, columnIndex) : status;
  }


  private static boolean isGroupAggregate(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

  StatusCode optionalColumnAlias(
      CharSequence sql,
      SqlCommand result,
      int columnIndex) {
    if (consumeKeyword(sql, "AS")) {
      return identifier(sql, result.writableColumnAlias(columnIndex));
    }
    skipSpaces(sql);
    int position = input.position();
    if (position >= sql.length()
        || sql.charAt(position) == ','
        || sql.charAt(position) == ';'
        || sql.charAt(position) == ')'
        || nextKeyword(sql, "FROM")
        || nextKeyword(sql, "JOIN")
        || nextKeyword(sql, "WHERE")
        || nextKeyword(sql, "GROUP")
        || nextKeyword(sql, "ORDER")
        || nextKeyword(sql, "LIMIT")) {
      return StatusCode.OK;
    }
    return identifierStart(sql.charAt(input.position()))
        ? identifier(sql, result.writableColumnAlias(columnIndex))
        : StatusCode.OK;
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

  private StatusCode matchingEitherIdentifier(
      CharSequence sql, CharSequence first, CharSequence second) {
    return parser.matchingEitherIdentifier(sql, first, second);
  }

  private StatusCode optionalTableAlias(CharSequence sql, SqlCommand result) {
    return parser.optionalTableAlias(sql, result);
  }

  private StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    return input.identifier(sql, result);
  }

  private StatusCode number(CharSequence sql, SqlParser.LongResult result) {
    return input.number(sql, result);
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

  private boolean consumeCharacter(CharSequence sql, char expected) {
    return input.consumeCharacter(sql, expected);
  }

  private void skipSpaces(CharSequence sql) {
    input.skipSpaces(sql);
  }

  private boolean nextKeyword(CharSequence sql, String keyword) {
    int start = input.position();
    boolean matches = input.consumeKeyword(sql, keyword);
    input.position(start);
    return matches;
  }

  private static boolean identifierStart(char character) {
    return SqlParserInput.identifierStart(character);
  }

  private static void setIdentifier(SqlIdentifier target, String value) {
    for (int index = 0; index < value.length(); index++) {
      target.append(value.charAt(index));
    }
  }
}
