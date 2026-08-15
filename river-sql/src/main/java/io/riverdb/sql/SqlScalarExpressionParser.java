package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses a bounded constant exact-value expression into postfix form. */
final class SqlScalarExpressionParser {
  private final SqlParserInput input;
  private final int[] descriptorStack = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final SqlExpressionPrimaryParser primaries;
  private SqlScalarExpression target;
  private SqlCommand projectionCommand;
  private boolean predicateExpression;
  private int stackSize;
  private int depth;

  SqlScalarExpressionParser(SqlParserInput parserInput) {
    input = parserInput;
    primaries = new SqlExpressionPrimaryParser(this, parserInput);
  }

  boolean starts(CharSequence sql) {
    int start = input.position();
    input.skipSpaces(sql);
    boolean starts = input.position() < sql.length()
        && (sql.charAt(input.position()) == '('
            || input.consumeCharacter(sql, '?')
            || input.startsNumber(sql)
            || input.consumeKeyword(sql, "TRUE")
            || input.consumeKeyword(sql, "FALSE")
            || input.consumeKeyword(sql, "DATE")
            || input.consumeKeyword(sql, "TIME")
            || input.consumeKeyword(sql, "TIMESTAMP")
            || input.consumeKeyword(sql, "CURRENT_DATE")
            || input.consumeKeyword(sql, "CURRENT_TIMESTAMP")
            || input.consumeKeyword(sql, "LOCALTIME")
            || input.consumeKeyword(sql, "LOCALTIMESTAMP")
            || input.consumeKeyword(sql, "EXTRACT")
            || input.consumeKeyword(sql, "CAST")
            || input.consumeKeyword(sql, "ABS")
            || input.consumeKeyword(sql, "CEIL")
            || input.consumeKeyword(sql, "FLOOR")
            || input.consumeKeyword(sql, "ROUND")
            || input.consumeKeyword(sql, "TRUNCATE"));
    input.position(start);
    return starts;
  }

  StatusCode parse(CharSequence sql, SqlScalarExpression expression) {
    projectionCommand = null;
    predicateExpression = false;
    return parseProgram(sql, expression);
  }

  StatusCode parseProjection(
      CharSequence sql, SqlCommand command, int projection) {
    if (command == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    projectionCommand = command;
    predicateExpression = false;
    SqlScalarExpression expression = command.writableProjectionExpression(projection);
    StatusCode status = parseProgram(sql, expression);
    projectionCommand = null;
    return status;
  }

  StatusCode parseProjectionScratch(
      CharSequence sql, SqlCommand command, SqlScalarExpression expression) {
    if (command == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    projectionCommand = command;
    predicateExpression = false;
    StatusCode status = parseProgram(sql, expression);
    projectionCommand = null;
    return status;
  }

  StatusCode parseMutation(
      CharSequence sql, SqlCommand command, SqlScalarExpression expression) {
    if (command == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    projectionCommand = command;
    predicateExpression = false;
    StatusCode status = parseProgram(sql, expression);
    projectionCommand = null;
    return status;
  }

  StatusCode parsePredicate(CharSequence sql, SqlCommand command) {
    if (command == null || command.hasComputedPredicate()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    projectionCommand = command;
    predicateExpression = true;
    StatusCode status = parseProgram(sql, command.writablePredicateExpression());
    projectionCommand = null;
    predicateExpression = false;
    return status;
  }

  void installPostAggregate(SqlPostAggregatePrimary primary) {
    primaries.installPostAggregate(primary);
  }

  private StatusCode parseProgram(
      CharSequence sql, SqlScalarExpression expression) {
    if (expression == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    expression.reset();
    target = expression;
    stackSize = 0;
    depth = 0;
    StatusCode status = additive(sql);
    if (!status.isOk() || stackSize != 1) {
      expression.reset();
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    if (projectionCommand == null && !executableAtTimeZoneShape(expression)) {
      expression.reset();
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (descriptorStack[0] == 0) {
      expression.finishUnresolved();
    } else {
      expression.finish(descriptorStack[0]);
    }
    return StatusCode.OK;
  }

  private static boolean executableAtTimeZoneShape(
      SqlScalarExpression expression) {
    boolean containsAtTimeZone = false;
    for (int index = 0; index < expression.nodeCount(); index++) {
      containsAtTimeZone |= expression.operator(index)
          == SqlScalarExpression.AT_TIME_ZONE;
    }
    if (!containsAtTimeZone) {
      return true;
    }
    if (expression.nodeCount() != 2
        || expression.operator(1) != SqlScalarExpression.AT_TIME_ZONE) {
      return false;
    }
    int source = expression.operator(0);
    return source == SqlScalarExpression.LITERAL
        || source == SqlScalarExpression.CURRENT_TIMESTAMP
        || source == SqlScalarExpression.LOCALTIMESTAMP;
  }

  private StatusCode additive(CharSequence sql) {
    StatusCode status = multiplicative(sql);
    while (status.isOk()) {
      int operator;
      if (input.consumeCharacter(sql, '+')) {
        operator = SqlScalarExpression.ADD;
      } else if (input.consumeCharacter(sql, '-')) {
        operator = SqlScalarExpression.SUBTRACT;
      } else {
        break;
      }
      status = multiplicative(sql);
      if (status.isOk()) {
        status = binary(operator);
      }
    }
    return status;
  }

  private StatusCode multiplicative(CharSequence sql) {
    StatusCode status = unary(sql);
    while (status.isOk()) {
      int operator;
      if (input.consumeCharacter(sql, '*')) {
        operator = SqlScalarExpression.MULTIPLY;
      } else if (input.consumeCharacter(sql, '/')) {
        operator = SqlScalarExpression.DIVIDE;
      } else if (input.consumeCharacter(sql, '%')) {
        operator = SqlScalarExpression.REMAINDER;
      } else {
        break;
      }
      status = unary(sql);
      if (status.isOk()) {
        status = binary(operator);
      }
    }
    return status;
  }

  private StatusCode unary(CharSequence sql) {
    if (++depth > SqlScalarExpression.MAXIMUM_NODES) {
      depth--;
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status;
    if (startsNegativeNumber(sql)) {
      status = primaries.parse(sql);
    } else if (input.consumeCharacter(sql, '+')) {
      status = unary(sql);
    } else if (input.consumeCharacter(sql, '-')) {
      status = unary(sql);
      if (status.isOk()) {
        status = unaryOperator(SqlScalarExpression.NEGATE);
      }
    } else {
      status = primaries.parse(sql);
    }
    depth--;
    return status;
  }

  private boolean startsNegativeNumber(CharSequence sql) {
    int start = input.position();
    input.skipSpaces(sql);
    int sign = input.position();
    boolean negative = sign + 1 < sql.length()
        && sql.charAt(sign) == '-'
        && SqlParserInput.digit(sql.charAt(sign + 1));
    input.position(start);
    return negative;
  }

  StatusCode unaryOperator(int operator) {
    if (stackSize < 1) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int source = descriptorStack[stackSize - 1];
    int sourceType = SqlTypeDescriptor.typeId(source);
    if (source == 0 && projectionCommand != null) {
      if (!target.append(operator, 0, 0)) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      return StatusCode.OK;
    }
    if (sourceType != SqlTypeDescriptor.TYPE_ID_BIGINT
        && sourceType != SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int descriptor = source;
    if (operator == SqlScalarExpression.CEILING
        || operator == SqlScalarExpression.FLOOR) {
      descriptor = sourceType == SqlTypeDescriptor.TYPE_ID_BIGINT
          ? SqlTypeDescriptor.BIGINT
          : SqlTypeDescriptor.decimal(
              SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 0);
    }
    if (!target.append(operator, 0, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    descriptorStack[stackSize - 1] = descriptor;
    return StatusCode.OK;
  }

  private StatusCode binary(int operator) {
    if (stackSize < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int right = descriptorStack[--stackSize];
    int left = descriptorStack[stackSize - 1];
    int descriptor = left == 0 || right == 0 ? 0 : switch (operator) {
      case SqlScalarExpression.ADD, SqlScalarExpression.SUBTRACT ->
          SqlTemporalExpressionTypes.additiveDescriptor(operator, left, right);
      case SqlScalarExpression.MULTIPLY ->
          ExactDecimal.multiplyResultDescriptor(left, right);
      case SqlScalarExpression.DIVIDE ->
          ExactDecimal.divideResultDescriptor(left, right);
      case SqlScalarExpression.REMAINDER ->
          ExactDecimal.remainderResultDescriptor(left, right);
      default -> 0;
    };
    if (descriptor == 0 && left != 0 && right != 0) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (!target.append(operator, 0, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    descriptorStack[stackSize - 1] = descriptor;
    return StatusCode.OK;
  }

  StatusCode parseNestedAdditive(CharSequence sql) {
    return additive(sql);
  }

  int topDescriptor() {
    return stackSize > 0 ? descriptorStack[stackSize - 1] : 0;
  }

  void replaceTopDescriptor(int descriptor) {
    if (stackSize > 0) {
      descriptorStack[stackSize - 1] = descriptor;
    }
  }

  boolean pushDescriptor(int descriptor) {
    if (stackSize >= descriptorStack.length) return false;
    descriptorStack[stackSize++] = descriptor;
    return true;
  }

  boolean hasStackCapacity() {
    return stackSize < descriptorStack.length;
  }

  boolean allowsUnresolved() {
    return projectionCommand != null;
  }

  boolean rowExpression() {
    return projectionCommand != null;
  }

  boolean hasValue() {
    return stackSize > 0;
  }

  SqlScalarExpression program() {
    return target;
  }

  SqlCommand command() {
    return projectionCommand;
  }

  int registerSymbol(CharSequence table, CharSequence name) {
    return predicateExpression
        ? projectionCommand.registerPredicateSymbol(table, name)
        : projectionCommand.registerProjectionSymbol(table, name);
  }

  boolean appendNode(int operator, long operand, int descriptor) {
    return target.append(operator, operand, descriptor);
  }
}
