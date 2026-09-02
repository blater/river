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
    int groupOutputs = command.columnCount() - command.aggregateOutputCount();
    int groupKey = kind == 0
        ? SqlGroupExpressions.groupKey(command, grouped, slot, identifier) : -1;
    boolean groupValue = groupKey >= 0;
    int operand = kind == 0
        ? selectedInvocation(slot, groupOutputs)
        : repeatedInvocation(sql, kind);
    if (!status.isOk()) return status;
    if (groupValue) operand = groupKey;
    int operator = groupValue
        ? SqlScalarExpression.GROUP_VALUE : SqlScalarExpression.AGGREGATE_VALUE;
    if (operand < 0 || !expressions.hasStackCapacity()
        || !expressions.appendNode(operator, operand, 0)) {
      return operand < 0
          ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.RESOURCE_EXHAUSTED;
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
    return output < command.aggregateOutputCount()
        ? command.aggregateOutputInvocation(output) : -1;
  }

  private int repeatedInvocation(CharSequence sql, int requestedKind) {
    if (!input.consumeCharacter(sql, '(')) return invalid();
    boolean countStar = requestedKind == SqlAggregateKind.COUNT
        && input.consumeCharacter(sql, '*');
    boolean countDistinct = requestedKind == SqlAggregateKind.COUNT
        && !countStar && input.consumeKeyword(sql, "DISTINCT");
    int kind = countStar ? SqlAggregateKind.COUNT
        : countDistinct ? SqlAggregateKind.COUNT_DISTINCT
        : requestedKind == SqlAggregateKind.COUNT
            ? SqlAggregateKind.COUNT_VALUE : requestedKind;
    if (!countStar) {
      status = matcher.parseScratch(sql, command, repeated);
      if (!status.isOk()) return -1;
    }
    if (!input.consumeCharacter(sql, ')')) return invalid();
    for (int invocation = 0;
        invocation < command.aggregateInvocationCount(); invocation++) {
      if (command.aggregateKind(invocation) != kind) continue;
      int projection = command.aggregateOperandProjection(invocation);
      if (countStar && projection < 0
          || !countStar && projection >= 0
              && SqlAggregateExpressionParser.same(
          command, command.aggregateOperandExpression(projection), repeated)) {
        return invocation;
      }
    }
    int projection = countStar ? -1 : freeOperandProjection();
    if (!countStar && projection < 0) {
      status = StatusCode.RESOURCE_EXHAUSTED;
      return -1;
    }
    if (!countStar) command.aggregateOperandExpression(projection).copyFrom(repeated);
    int invocation = command.appendAggregateInvocation(kind, projection);
    if (invocation < 0) status = StatusCode.RESOURCE_EXHAUSTED;
    return invocation;
  }

  private int freeOperandProjection() {
    int first = grouped ? 1 : 0;
    for (int candidate = first; candidate < SqlCommand.MAXIMUM_PROJECTIONS; candidate++) {
      boolean occupied = false;
      for (int invocation = 0;
          invocation < command.aggregateInvocationCount(); invocation++) {
        if (command.aggregateOperandProjection(invocation) == candidate) {
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
