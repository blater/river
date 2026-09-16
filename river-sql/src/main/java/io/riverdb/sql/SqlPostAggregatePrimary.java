package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Resolves selected aggregate and group-key leaves in HAVING postfix programs. */
final class SqlPostAggregatePrimary {
  private final SqlScalarExpressionParser expressions;
  private final SqlParserInput input;
  private final SqlIdentifier identifier = new SqlIdentifier();
  private final SqlScalarExpression repeated = new SqlScalarExpression();
  private SqlCommand command;
  private SqlAggregateExpressionParser matcher;
  private boolean grouped;
  private int leaves;
  private StatusCode status = StatusCode.OK;

  SqlPostAggregatePrimary(
      SqlScalarExpressionParser expressionParser, SqlParserInput parserInput) {
    expressions = expressionParser;
    input = parserInput;
  }

  void begin(
      SqlCommand target,
      boolean groupedAggregate,
      SqlAggregateExpressionParser aggregateMatcher) {
    command = target;
    grouped = groupedAggregate;
    matcher = aggregateMatcher;
    leaves = 0;
    status = StatusCode.OK;
  }

  void reset() {
    command = null;
    matcher = null;
    grouped = false;
    leaves = 0;
    status = StatusCode.OK;
  }

  boolean starts(CharSequence sql) {
    if (command == null) return false;
    int start = input.position();
    int kind = aggregateKind(sql);
    int slot = kind == 0 ? selectedAlias(sql) : -1;
    boolean result = kind != 0
        || SqlGroupExpressions.resolves(command, grouped, slot, identifier);
    input.position(start);
    return result;
  }

  boolean startsOther(CharSequence sql) {
    return false;
  }

  StatusCode append(CharSequence sql) {
    if (command == null) return StatusCode.FEATURE_NOT_SUPPORTED;
    int kind = aggregateKind(sql);
    int slot = kind == 0 ? aliasSlot(sql) : -1;
    int groupOutputs = command.columnCount() - command.aggregates().outputCount();
    int groupKey = kind == 0
        ? SqlGroupExpressions.groupKey(command, grouped, slot, identifier) : -1;
    boolean groupValue = groupKey >= 0;
    int operand = groupValue ? groupKey
        : kind == 0 ? selectedInvocation(slot, groupOutputs) : repeatedInvocation(sql, kind);
    if (!status.isOk()) return status;
    return appendValue(operand, groupValue);
  }

  private StatusCode appendValue(int operand, boolean groupValue) {
    int operator = groupValue
        ? SqlScalarExpression.GROUP_VALUE : SqlScalarExpression.AGGREGATE_VALUE;
    if (operand < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!expressions.hasStackCapacity() || !expressions.appendNode(operator, operand, 0)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    leaves++;
    expressions.pushDescriptor(0);
    return StatusCode.OK;
  }

  boolean valid(SqlScalarExpression expression) {
    return !expression.hasColumnReference();
  }

  private int selectedInvocation(int slot, int groupOutputs) {
    if (slot < 0 || grouped && slot < groupOutputs) return slot;
    int output = grouped ? slot - groupOutputs : slot;
    return output < command.aggregates().outputCount()
        ? command.aggregates().outputInvocation(output) : -1;
  }

  private int repeatedInvocation(CharSequence sql, int requestedKind) {
    if (!input.consumeCharacter(sql, '(')) return invalid();
    int kind = repeatedKind(sql, requestedKind);
    boolean countStar = kind == SqlAggregateKind.COUNT;
    if (!countStar) {
      status = matcher.parseScratch(sql, command, repeated);
      if (!status.isOk()) return -1;
    }
    if (!input.consumeCharacter(sql, ')')) return invalid();
    int existing = findInvocation(kind, countStar);
    if (existing >= 0) return existing;
    return appendInvocation(kind, countStar);
  }

  private int repeatedKind(CharSequence sql, int requestedKind) {
    if (requestedKind != SqlAggregateKind.COUNT) return requestedKind;
    if (input.consumeCharacter(sql, '*')) return SqlAggregateKind.COUNT;
    return input.consumeKeyword(sql, "DISTINCT")
        ? SqlAggregateKind.COUNT_DISTINCT : SqlAggregateKind.COUNT_VALUE;
  }

  private int findInvocation(int kind, boolean countStar) {
    for (int invocation = 0;
        invocation < command.aggregates().invocationCount(); invocation++) {
      if (command.aggregates().kind(invocation) != kind) continue;
      int projection = command.aggregates().operandProjection(invocation);
      if (sameInvocation(command, projection, countStar)) {
        return invocation;
      }
    }
    return -1;
  }

  private boolean sameInvocation(SqlCommand target, int projection, boolean countStar) {
    return countStar && projection < 0
        || !countStar && projection >= 0
            && SqlAggregateExpressionParser.same(
        target, target.aggregateOperandExpression(projection), repeated);
  }

  private int appendInvocation(int kind, boolean countStar) {
    int projection = countStar ? -1 : freeOperandProjection();
    if (!countStar && projection < 0) {
      status = StatusCode.RESOURCE_EXHAUSTED;
      return -1;
    }
    if (!countStar) command.aggregateOperandExpression(projection).copyFrom(repeated);
    int invocation = command.aggregates.appendInvocation(kind, projection);
    if (invocation < 0) status = StatusCode.RESOURCE_EXHAUSTED;
    return invocation;
  }

  private int freeOperandProjection() {
    int first = grouped ? 1 : 0;
    for (int candidate = first; candidate < SqlCommand.MAXIMUM_PROJECTIONS; candidate++) {
      boolean occupied = false;
      for (int invocation = 0;
          invocation < command.aggregates().invocationCount(); invocation++) {
        if (command.aggregates().operandProjection(invocation) == candidate) {
          occupied = true;
          break;
        }
      }
      if (!occupied) return candidate;
    }
    return -1;
  }

  private int invalid() {
    status = StatusCode.INVALID_EXTERNAL_INPUT;
    return -1;
  }

  private int aliasSlot(CharSequence sql) {
    int slot = selectedAlias(sql);
    return slot >= 0 ? slot : -1;
  }

  private int selectedAlias(CharSequence sql) {
    identifier.reset();
    if (!input.identifier(sql, identifier).isOk()) return -1;
    int match = -1;
    for (int column = 0; column < command.columnCount(); column++) {
      if (!same(identifier, command.columnOutputName(column))) continue;
      if (match >= 0) return -1;
      match = column;
    }
    return match;
  }

  private int aggregateKind(CharSequence sql) {
    if (input.consumeKeyword(sql, "COUNT")) return SqlAggregateKind.COUNT;
    if (input.consumeKeyword(sql, "SUM")) return SqlAggregateKind.SUM;
    if (input.consumeKeyword(sql, "AVG")) return SqlAggregateKind.AVG;
    if (input.consumeKeyword(sql, "MIN")) return SqlAggregateKind.MIN;
    if (input.consumeKeyword(sql, "MAX")) return SqlAggregateKind.MAX;
    return 0;
  }

  private static boolean same(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) return false;
    }
    return true;
  }
}
