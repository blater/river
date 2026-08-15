package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses the bounded flat predicate set evaluated after aggregate finalization. */
final class SqlHavingParser {
  private final SqlParser parser;
  private final SqlParserInput input;
  private final SqlPostAggregateExpressionParser expressions;
  private final SqlPredicateLiteralNormalizer literals =
      new SqlPredicateLiteralNormalizer();
  private final SqlParser.LongResult literal = new SqlParser.LongResult();
  private final SqlScalarExpression expression = new SqlScalarExpression();
  private final long[] members =
      new long[SqlCommand.MAXIMUM_LITERAL_MEMBERSHIP_VALUES];
  private int memberCount;
  private int memberDescriptor;
  private boolean membershipNull;

  SqlHavingParser(
      SqlParser parent,
      SqlParserInput parserInput,
      SqlAggregateExpressionParser selected) {
    parser = parent;
    input = parserInput;
    expressions = new SqlPostAggregateExpressionParser(parserInput, selected);
  }

  StatusCode parse(CharSequence sql, SqlCommand command, boolean grouped) {
    boolean more = true;
    while (more) {
      StatusCode status = expressions.parse(sql, command, grouped, expression);
      int predicate = status.isOk() ? command.appendHavingExpression(expression) : -2;
      if (status.isOk() && predicate < 0) status = StatusCode.RESOURCE_EXHAUSTED;
      if (status.isOk()) status = predicate(sql, command, predicate);
      if (!status.isOk()) return status;
      boolean disjunction = input.consumeKeyword(sql, "OR");
      more = disjunction || input.consumeKeyword(sql, "AND");
      if (disjunction) command.setHavingDisjunction(predicate);
    }
    return StatusCode.OK;
  }

  private StatusCode predicate(
      CharSequence sql, SqlCommand command, int predicate) {
    if (input.consumeKeyword(sql, "IS")) {
      boolean negated = input.consumeKeyword(sql, "NOT");
      if (!input.consumeKeyword(sql, "NULL")) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return command.setHavingNull(predicate, negated)
          ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
    }
    boolean negated = input.consumeKeyword(sql, "NOT");
    if (input.consumeKeyword(sql, "BETWEEN")) {
      return negated ? StatusCode.FEATURE_NOT_SUPPORTED
          : range(sql, command, predicate);
    }
    if (input.consumeKeyword(sql, "IN")) {
      return membership(sql, command, predicate, negated);
    }
    if (negated) return StatusCode.INVALID_EXTERNAL_INPUT;
    SqlComparison comparison = parser.comparisonOperator(sql);
    return comparison == null
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : comparison(sql, command, predicate, comparison);
  }

  private StatusCode comparison(
      CharSequence sql,
      SqlCommand command,
      int predicate,
      SqlComparison comparison) {
    StatusCode status = literal(sql);
    if (status.isOk() && !command.setHavingComparison(
        predicate,
        comparison,
        literal.value,
        literal.typeDescriptor,
        literal.nullValue)) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    return status;
  }

  private StatusCode range(
      CharSequence sql, SqlCommand command, int predicate) {
    StatusCode status = literal(sql);
    if (!status.isOk()) return status;
    long lower = literal.value;
    int lowerDescriptor = literal.typeDescriptor;
    boolean lowerNull = literal.nullValue;
    status = input.requireKeyword(sql, "AND");
    if (status.isOk()) status = literal(sql);
    if (!status.isOk()) return status;
    long upper = literal.value;
    int upperDescriptor = literal.typeDescriptor;
    boolean upperNull = literal.nullValue;
    if (!lowerNull && !upperNull) {
      status = literals.normalizeRange(
          lower, lowerDescriptor, upper, upperDescriptor);
      if (!status.isOk()) return status;
      lower = literals.lower();
      upper = literals.upper();
      lowerDescriptor = literals.descriptor();
      upperDescriptor = literals.descriptor();
    }
    return command.setHavingRange(
        predicate,
        lower,
        lowerDescriptor,
        lowerNull,
        upper,
        upperDescriptor,
        upperNull)
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private StatusCode membership(
      CharSequence sql, SqlCommand command, int predicate, boolean negated) {
    memberCount = 0;
    memberDescriptor = 0;
    membershipNull = false;
    StatusCode status = input.requireCharacter(sql, '(');
    boolean complete = false;
    while (status.isOk() && !complete) {
      status = membershipValue(sql);
      if (status.isOk()) {
        complete = input.consumeCharacter(sql, ')');
        if (!complete) status = input.requireCharacter(sql, ',');
      }
    }
    if (!status.isOk()) return status;
    return command.setHavingMembership(
        predicate,
        members,
        memberCount,
        membershipNull,
        negated,
        memberDescriptor)
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private StatusCode membershipValue(CharSequence sql) {
    if (input.consumeKeyword(sql, "NULL")) {
      membershipNull = true;
      return StatusCode.OK;
    }
    if (memberCount >= members.length) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = input.literal(sql, literal);
    if (!status.isOk()) return status;
    if (literal.nullValue) {
      membershipNull = true;
      if (literal.typeDescriptor == 0) return StatusCode.OK;
      status = literals.mergeMembershipDescriptor(
          members, memberCount, memberDescriptor, literal.typeDescriptor);
      if (status.isOk()) memberDescriptor = literals.descriptor();
      return status;
    }
    if (memberDescriptor == 0) {
      memberDescriptor = literal.typeDescriptor;
    } else if (memberDescriptor != literal.typeDescriptor) {
      status = literals.normalizeMembership(
          members,
          memberCount,
          memberDescriptor,
          literal.value,
          literal.typeDescriptor);
      if (!status.isOk()) return status;
      literal.value = literals.value();
      memberDescriptor = literals.descriptor();
    }
    members[memberCount++] = literal.value;
    return StatusCode.OK;
  }

  private StatusCode literal(CharSequence sql) {
    if (!input.consumeKeyword(sql, "NULL")) return input.literal(sql, literal);
    literal.value = 0;
    literal.varchar = false;
    literal.nullValue = true;
    literal.textScalars = 0;
    literal.typeDescriptor = 0;
    return StatusCode.OK;
  }
}
