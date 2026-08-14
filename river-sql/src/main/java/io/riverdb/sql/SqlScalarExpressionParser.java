package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses a bounded constant exact-value expression into postfix form. */
final class SqlScalarExpressionParser {
  private final SqlParserInput input;
  private final SqlParser.LongResult literal = new SqlParser.LongResult();
  private final int[] descriptorStack = new int[SqlScalarExpression.MAXIMUM_NODES];
  private SqlScalarExpression target;
  private int stackSize;
  private int depth;

  SqlScalarExpressionParser(SqlParserInput parserInput) {
    input = parserInput;
  }

  boolean starts(CharSequence sql) {
    int start = input.position();
    input.skipSpaces(sql);
    boolean starts = input.position() < sql.length()
        && (sql.charAt(input.position()) == '('
            || input.startsNumber(sql)
            || input.consumeKeyword(sql, "TRUE")
            || input.consumeKeyword(sql, "FALSE")
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
    expression.finish(descriptorStack[0]);
    return StatusCode.OK;
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
    if (input.consumeCharacter(sql, '+')) {
      status = unary(sql);
    } else if (input.consumeCharacter(sql, '-')) {
      status = unary(sql);
      if (status.isOk()) {
        status = unaryOperator(SqlScalarExpression.NEGATE);
      }
    } else {
      status = primary(sql);
    }
    depth--;
    return status;
  }

  private StatusCode primary(CharSequence sql) {
    if (input.consumeCharacter(sql, '(')) {
      StatusCode status = additive(sql);
      return status.isOk() ? input.requireCharacter(sql, ')') : status;
    }
    if (input.consumeKeyword(sql, "CAST")) {
      return cast(sql);
    }
    if (input.consumeKeyword(sql, "ABS")) {
      return unaryFunction(sql, SqlScalarExpression.ABSOLUTE);
    }
    if (input.consumeKeyword(sql, "CEIL")) {
      return unaryFunction(sql, SqlScalarExpression.CEILING);
    }
    if (input.consumeKeyword(sql, "FLOOR")) {
      return unaryFunction(sql, SqlScalarExpression.FLOOR);
    }
    if (input.consumeKeyword(sql, "ROUND")) {
      return scaleFunction(sql, true);
    }
    if (input.consumeKeyword(sql, "TRUNCATE")) {
      return scaleFunction(sql, false);
    }
    StatusCode status = input.literal(sql, literal);
    if (!status.isOk()) {
      return status;
    }
    int type = SqlTypeDescriptor.typeId(literal.typeDescriptor);
    if (type != SqlTypeDescriptor.TYPE_ID_BIGINT
        && type != SqlTypeDescriptor.TYPE_ID_DECIMAL
        && type != SqlTypeDescriptor.TYPE_ID_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return appendLiteral(literal.value, literal.typeDescriptor);
  }

  private StatusCode cast(CharSequence sql) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) {
      status = additive(sql);
    }
    if (status.isOk()) {
      status = input.requireKeyword(sql, "AS");
    }
    if (status.isOk()) {
      status = input.typeDescriptor(sql, literal);
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, ')');
    }
    if (!status.isOk() || stackSize < 1) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    int source = descriptorStack[stackSize - 1];
    int targetDescriptor = literal.typeDescriptor;
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(targetDescriptor);
    boolean numeric = (sourceType == SqlTypeDescriptor.TYPE_ID_BIGINT
            || sourceType == SqlTypeDescriptor.TYPE_ID_DECIMAL)
        && (targetType == SqlTypeDescriptor.TYPE_ID_BIGINT
            || targetType == SqlTypeDescriptor.TYPE_ID_DECIMAL);
    boolean bool = sourceType == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        && targetType == SqlTypeDescriptor.TYPE_ID_BOOLEAN;
    if ((!numeric && !bool)
        || !SqlTypeDescriptor.canExplicitlyCast(source, targetDescriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (!target.append(SqlScalarExpression.CAST, 0, targetDescriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    descriptorStack[stackSize - 1] = targetDescriptor;
    return StatusCode.OK;
  }

  private StatusCode unaryFunction(CharSequence sql, int operator) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) {
      status = additive(sql);
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, ')');
    }
    return status.isOk() ? unaryOperator(operator) : status;
  }

  private StatusCode scaleFunction(CharSequence sql, boolean round) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) {
      status = additive(sql);
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, ',');
    }
    if (status.isOk()) {
      status = input.number(sql, literal);
    }
    if (status.isOk()) {
      status = input.requireCharacter(sql, ')');
    }
    if (!status.isOk() || literal.value < 0
        || literal.value > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION
        || stackSize < 1) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    int descriptor = ExactDecimal.quantizedDescriptor(
        descriptorStack[stackSize - 1], (int) literal.value);
    if (descriptor == 0) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int operator = round ? SqlScalarExpression.ROUND : SqlScalarExpression.TRUNCATE;
    if (!target.append(operator, literal.value, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    descriptorStack[stackSize - 1] = descriptor;
    return StatusCode.OK;
  }

  private StatusCode unaryOperator(int operator) {
    if (stackSize < 1) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int source = descriptorStack[stackSize - 1];
    int sourceType = SqlTypeDescriptor.typeId(source);
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
    int descriptor = switch (operator) {
      case SqlScalarExpression.ADD, SqlScalarExpression.SUBTRACT ->
          ExactDecimal.addResultDescriptor(left, right);
      case SqlScalarExpression.MULTIPLY ->
          ExactDecimal.multiplyResultDescriptor(left, right);
      case SqlScalarExpression.DIVIDE ->
          ExactDecimal.divideResultDescriptor(left, right);
      case SqlScalarExpression.REMAINDER ->
          ExactDecimal.remainderResultDescriptor(left, right);
      default -> 0;
    };
    if (descriptor == 0) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (!target.append(operator, 0, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    descriptorStack[stackSize - 1] = descriptor;
    return StatusCode.OK;
  }

  private StatusCode appendLiteral(long value, int descriptor) {
    if (stackSize >= descriptorStack.length
        || !target.append(SqlScalarExpression.LITERAL, value, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    descriptorStack[stackSize++] = descriptor;
    return StatusCode.OK;
  }
}
