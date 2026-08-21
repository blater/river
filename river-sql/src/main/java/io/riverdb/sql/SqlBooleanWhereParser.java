package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses one bounded row predicate without speculative literal consumption. */
final class SqlBooleanWhereParser {
  private final SqlParserInput input;
  private final SqlScalarExpressionParser expressions;
  private final SqlPostAggregateExpressionParser postAggregate;
  private final SqlComparisonParser comparisons;
  private final SqlScalarExpression left = new SqlScalarExpression();
  private final SqlScalarExpression right = new SqlScalarExpression();
  private final SqlScalarExpression lower = new SqlScalarExpression();
  private final SqlScalarExpression upper = new SqlScalarExpression();
  private final SqlParser.LongResult literal = new SqlParser.LongResult();
  private final long[] memberValues =
      new long[SqlBooleanPredicateProgram.MAXIMUM_MEMBERS];
  private final int[] memberDescriptors =
      new int[SqlBooleanPredicateProgram.MAXIMUM_MEMBERS];
  private final boolean[] memberNulls =
      new boolean[SqlBooleanPredicateProgram.MAXIMUM_MEMBERS];
  private SqlCommand command;
  private SqlBooleanPredicateProgram target;
  private int depth;
  private int parsedNode = -1;
  private final SqlSubqueryLeafRegistry subqueries = new SqlSubqueryLeafRegistry();
  private boolean grouped;

  SqlBooleanWhereParser(
      SqlParserInput parserInput, SqlScalarExpressionParser scalarExpressions) {
    input = parserInput;
    expressions = scalarExpressions;
    postAggregate = null;
    comparisons = new SqlComparisonParser(parserInput);
  }

  SqlBooleanWhereParser(
      SqlParserInput parserInput, SqlAggregateExpressionParser selected) {
    input = parserInput;
    expressions = null;
    postAggregate = new SqlPostAggregateExpressionParser(parserInput, selected);
    comparisons = new SqlComparisonParser(parserInput);
  }

  StatusCode parse(CharSequence sql, SqlCommand result) {
    return parse(sql, result, result == null ? null : result.writableWherePredicates());
  }

  StatusCode parseOn(
      CharSequence sql,
      SqlCommand result,
      SqlBooleanPredicateProgram destination) {
    return parse(sql, result, destination);
  }

  private StatusCode parse(
      CharSequence sql,
      SqlCommand result,
      SqlBooleanPredicateProgram destination) {
    command = result;
    target = destination;
    if (target == null) {
      clearScratch();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target.reset();
    depth = 0;
    StatusCode status = disjunction(sql);
    if (status.isOk() && !target.finish(parsedNode)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk()) target.reset();
    clearScratch();
    return status;
  }

  StatusCode parseHaving(
      CharSequence sql, SqlCommand result, boolean groupedAggregate) {
    command = result;
    grouped = groupedAggregate;
    target = result == null ? null : result.writableBooleanHavingPredicates();
    if (target == null) {
      clearScratch();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target.reset();
    depth = 0;
    StatusCode status = disjunction(sql);
    if (status.isOk() && !target.finish(parsedNode)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!status.isOk()) target.reset();
    clearScratch();
    return status;
  }

  void beginStandard() {
    clearSubqueries();
  }

  void beginSubqueries(
      int[] offsets, int[] kinds, int[] edges, int count) {
    beginStandard();
    subqueries.begin(offsets, kinds, edges, count);
  }

  int subqueryLeaf(int index) {
    return subqueries.leaf(index);
  }

  private StatusCode disjunction(CharSequence sql) {
    StatusCode status = conjunction(sql);
    while (status.isOk() && input.consumeKeyword(sql, "OR")) {
      int leftNode = parsedNode;
      status = conjunction(sql);
      if (status.isOk()) {
        parsedNode = target.appendBoolean(
            SqlBooleanPredicateProgram.BOOLEAN_OR, leftNode, parsedNode);
        if (parsedNode < 0) status = StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return status;
  }

  private StatusCode conjunction(CharSequence sql) {
    StatusCode status = negation(sql);
    while (status.isOk() && input.consumeKeyword(sql, "AND")) {
      int leftNode = parsedNode;
      status = negation(sql);
      if (status.isOk()) {
        parsedNode = target.appendBoolean(
            SqlBooleanPredicateProgram.BOOLEAN_AND, leftNode, parsedNode);
        if (parsedNode < 0) status = StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    return status;
  }

  private StatusCode negation(CharSequence sql) {
    if (++depth > SqlBooleanPredicateProgram.MAXIMUM_DEPTH) {
      depth--;
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status;
    if (input.consumeKeyword(sql, "NOT")) {
      status = negation(sql);
      if (status.isOk()) {
        parsedNode = target.appendBoolean(
            SqlBooleanPredicateProgram.BOOLEAN_NOT, parsedNode, 0);
        if (parsedNode < 0) status = StatusCode.RESOURCE_EXHAUSTED;
      }
    } else if (startsBooleanGroup(sql)) {
      status = input.requireCharacter(sql, '(');
      if (status.isOk()) status = disjunction(sql);
      if (status.isOk()) status = input.requireCharacter(sql, ')');
    } else {
      status = leaf(sql);
    }
    depth--;
    return status;
  }

  private StatusCode leaf(CharSequence sql) {
    input.skipSpaces(sql);
    int exists = subqueryAt(input.position(), SqlQuery.SUBQUERY_EXISTS);
    if (exists >= 0) return subqueryExists(sql, exists);
    StatusCode status = parseExpression(sql, left);
    int leaf = status.isOk() ? target.appendLeaf(left) : -2;
    if (status.isOk() && leaf < 0) status = StatusCode.RESOURCE_EXHAUSTED;
    if (status.isOk()) status = leafTest(sql, leaf);
    if (status.isOk()) {
      parsedNode = target.appendBoolean(
          SqlBooleanPredicateProgram.BOOLEAN_LEAF, leaf, 0);
      if (parsedNode < 0) status = StatusCode.RESOURCE_EXHAUSTED;
    }
    return status;
  }

  private StatusCode subqueryExists(CharSequence sql, int synthetic) {
    StatusCode status = input.requireKeyword(sql, "TRUE");
    int leaf = status.isOk()
        ? target.appendSubqueryExists(subqueries.edge(synthetic)) : -2;
    if (status.isOk() && leaf < 0) status = StatusCode.RESOURCE_EXHAUSTED;
    if (status.isOk()) {
      subqueries.setLeaf(synthetic, leaf);
      parsedNode = target.appendBoolean(
          SqlBooleanPredicateProgram.BOOLEAN_LEAF, leaf, 0);
      if (parsedNode < 0) status = StatusCode.RESOURCE_EXHAUSTED;
    }
    return status;
  }

  private StatusCode leafTest(CharSequence sql, int leaf) {
    if (input.consumeKeyword(sql, "IS")) return truth(sql, leaf);
    boolean negated = input.consumeKeyword(sql, "NOT");
    if (input.consumeKeyword(sql, "BETWEEN")) {
      return between(sql, leaf, negated);
    }
    if (input.consumeKeyword(sql, "IN")) return membership(sql, leaf, negated);
    if (negated) return StatusCode.INVALID_EXTERNAL_INPUT;
    int comparisonOffset = input.position();
    SqlComparison comparison = comparisons.parse(sql);
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      return membership(sql, leaf, comparison == SqlComparison.NOT_IN);
    }
    if (comparison == null && input.position() != comparisonOffset) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (comparison == null && atBooleanBoundary(sql)) {
      return target.setBoolean(leaf)
          ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
    }
    if (comparison == null || comparison == SqlComparison.HALF_OPEN_RANGE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    input.skipSpaces(sql);
    int scalar = subqueryAt(input.position(), SqlQuery.SUBQUERY_SCALAR);
    if (scalar >= 0) {
      StatusCode status = input.requireCharacter(sql, '0');
      if (status.isOk() && !target.setSubqueryComparison(
          leaf, comparison, subqueries.edge(scalar))) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      if (status.isOk()) subqueries.setLeaf(scalar, leaf);
      return status;
    }
    StatusCode status = parseExpression(sql, right);
    if (status.isOk() && !target.setComparison(leaf, comparison, right)) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    return status;
  }

  private StatusCode parseExpression(
      CharSequence sql, SqlScalarExpression result) {
    return expressions != null
        ? expressions.parsePredicateScratch(sql, command, result)
        : postAggregate.parse(sql, command, grouped, result);
  }

  private StatusCode truth(CharSequence sql, int leaf) {
    boolean negated = input.consumeKeyword(sql, "NOT");
    if (input.consumeKeyword(sql, "NULL")) {
      return target.setNull(leaf, negated)
          ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
    }
    int truth;
    if (input.consumeKeyword(sql, "TRUE")) {
      truth = SqlBooleanPredicateProgram.TRUTH_TRUE;
    } else if (input.consumeKeyword(sql, "FALSE")) {
      truth = SqlBooleanPredicateProgram.TRUTH_FALSE;
    } else if (input.consumeKeyword(sql, "UNKNOWN")) {
      truth = SqlBooleanPredicateProgram.TRUTH_UNKNOWN;
    } else {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return target.setTruth(leaf, truth, negated)
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private StatusCode between(CharSequence sql, int leaf, boolean negated) {
    StatusCode status = literalExpression(sql, lower);
    if (status.isOk()) status = input.requireKeyword(sql, "AND");
    if (status.isOk()) status = literalExpression(sql, upper);
    if (status.isOk() && !target.setBetween(leaf, lower, upper, negated)) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    return status;
  }

  private StatusCode membership(CharSequence sql, int leaf, boolean negated) {
    StatusCode status = input.requireCharacter(sql, '(');
    input.skipSpaces(sql);
    int subquery = subqueryAt(input.position(), SqlQuery.SUBQUERY_MEMBERSHIP);
    if (status.isOk() && subquery >= 0) {
      status = input.requireCharacter(sql, '0');
      if (status.isOk()) status = input.requireCharacter(sql, ')');
      if (status.isOk() && !target.setSubqueryMembership(
          leaf, subqueries.edge(subquery), negated)) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      if (status.isOk()) subqueries.setLeaf(subquery, leaf);
      return status;
    }
    int count = 0;
    boolean complete = false;
    while (status.isOk() && !complete) {
      if (count >= memberValues.length) {
        status = StatusCode.RESOURCE_EXHAUSTED;
        break;
      }
      status = predicateLiteral(sql);
      if (status.isOk()) {
        memberValues[count] = literal.value;
        memberDescriptors[count] = literal.typeDescriptor;
        memberNulls[count++] = literal.nullValue;
        complete = input.consumeCharacter(sql, ')');
        if (!complete) status = input.requireCharacter(sql, ',');
      }
    }
    if (status.isOk() && !target.setMembership(
        leaf, memberValues, memberDescriptors, memberNulls, count, negated)) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    clearMembers(count);
    return status;
  }

  private StatusCode literalExpression(
      CharSequence sql, SqlScalarExpression expression) {
    StatusCode status = predicateLiteral(sql);
    expression.reset();
    if (!status.isOk()) return status;
    int operator = literal.nullValue
        ? SqlScalarExpression.NULL : SqlScalarExpression.LITERAL;
    if (!expression.append(operator, literal.value, literal.typeDescriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (literal.typeDescriptor == 0) expression.finishUnresolved();
    else expression.finish(literal.typeDescriptor);
    return StatusCode.OK;
  }

  private StatusCode predicateLiteral(CharSequence sql) {
    if (!input.consumeKeyword(sql, "NULL")) return input.literal(sql, literal);
    literal.value = 0;
    literal.varchar = false;
    literal.nullValue = true;
    literal.textScalars = 0;
    literal.typeDescriptor = 0;
    return StatusCode.OK;
  }

  private void clearMembers(int count) {
    for (int index = 0; index < count; index++) {
      memberValues[index] = 0;
      memberDescriptors[index] = 0;
      memberNulls[index] = false;
    }
  }

  private void clearScratch() {
    left.reset();
    right.reset();
    lower.reset();
    upper.reset();
    literal.value = 0;
    literal.typeDescriptor = 0;
    literal.textScalars = 0;
    literal.nullValue = false;
    literal.varchar = false;
    command = null;
    target = null;
    parsedNode = -1;
    depth = 0;
    grouped = false;
  }

  private int subqueryAt(int offset, int kind) {
    return subqueries.find(offset, kind);
  }

  private void clearSubqueries() {
    subqueries.clear();
  }

  private boolean startsBooleanGroup(CharSequence sql) {
    int position = input.position();
    while (position < sql.length() && Character.isWhitespace(sql.charAt(position))) {
      position++;
    }
    if (position >= sql.length() || sql.charAt(position) != '(') return false;
    int closing = matchingParenthesis(sql, position);
    if (closing < 0) return true;
    int next = closing + 1;
    while (next < sql.length() && Character.isWhitespace(sql.charAt(next))) next++;
    if (next >= sql.length()) return true;
    char character = sql.charAt(next);
    if (character == '=' || character == '!' || character == '<' || character == '>'
        || character == '+' || character == '-' || character == '*'
        || character == '/' || character == '%') return false;
    return !startsKeyword(sql, next, "IS")
        && !startsKeyword(sql, next, "IN")
        && !startsKeyword(sql, next, "NOT")
        && !startsKeyword(sql, next, "BETWEEN")
        && !startsKeyword(sql, next, "AT");
  }

  private static int matchingParenthesis(CharSequence sql, int opening) {
    int depth = 0;
    boolean quoted = false;
    for (int index = opening; index < sql.length(); index++) {
      char character = sql.charAt(index);
      if (character == '\'' && quoted && index + 1 < sql.length()
          && sql.charAt(index + 1) == '\'') {
        index++;
      } else if (character == '\'') {
        quoted = !quoted;
      } else if (!quoted && character == '(') {
        depth++;
      } else if (!quoted && character == ')' && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  private static boolean startsKeyword(
      CharSequence sql, int position, String keyword) {
    if (sql.length() - position < keyword.length()) return false;
    for (int index = 0; index < keyword.length(); index++) {
      if (SqlParserInput.upper(sql.charAt(position + index)) != keyword.charAt(index)) {
        return false;
      }
    }
    int end = position + keyword.length();
    return end == sql.length() || !SqlParserInput.identifierStart(sql.charAt(end))
        && !SqlParserInput.digit(sql.charAt(end));
  }

  private boolean atBooleanBoundary(CharSequence sql) {
    int position = input.position();
    while (position < sql.length() && Character.isWhitespace(sql.charAt(position))) {
      position++;
    }
    if (position >= sql.length()) return true;
    char character = sql.charAt(position);
    return character == ')' || character == ';'
        || startsKeyword(sql, position, "AND")
        || startsKeyword(sql, position, "OR")
        || startsKeyword(sql, position, "WHERE")
        || startsKeyword(sql, position, "JOIN")
        || startsKeyword(sql, position, "INNER")
        || startsKeyword(sql, position, "LEFT")
        || startsKeyword(sql, position, "RIGHT")
        || startsKeyword(sql, position, "FULL")
        || startsKeyword(sql, position, "CROSS")
        || startsKeyword(sql, position, "NATURAL")
        || startsKeyword(sql, position, "GROUP")
        || startsKeyword(sql, position, "HAVING")
        || startsKeyword(sql, position, "ORDER")
        || startsKeyword(sql, position, "LIMIT");
  }

}
