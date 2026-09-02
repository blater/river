package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;

/** Parses expression leaves, casts, and bounded unary functions. */
final class SqlExpressionPrimaryParser {
  private final SqlScalarExpressionParser expressions;
  private final SqlParserInput input;
  private final SqlTemporalScalarParser temporal;
  private SqlPostAggregatePrimary postAggregate;
  private final SqlParser.LongResult literal = new SqlParser.LongResult();
  private final SqlIdentifier firstIdentifier = new SqlIdentifier();
  private final SqlIdentifier secondIdentifier = new SqlIdentifier();

  SqlExpressionPrimaryParser(
      SqlScalarExpressionParser expressionParser, SqlParserInput parserInput) {
    expressions = expressionParser;
    input = parserInput;
    temporal = new SqlTemporalScalarParser(expressionParser, parserInput);
  }

  StatusCode parse(CharSequence sql) {
    if (postAggregate != null && postAggregate.starts(sql)) {
      return optionalAtTimeZone(sql, postAggregate.append(sql));
    }
    if (postAggregate != null && postAggregate.startsOther(sql)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (input.consumeCharacter(sql, '(')) {
      StatusCode status = expressions.parseNestedAdditive(sql);
      if (status.isOk()) status = input.requireCharacter(sql, ')');
      return optionalAtTimeZone(sql, status);
    }
    if (input.consumeKeyword(sql, "CAST")) {
      return optionalAtTimeZone(sql, cast(sql));
    }
    if (input.consumeKeyword(sql, "EXTRACT")) {
      return temporal.parseExtract(sql, expressions.program());
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
    if (input.consumeKeyword(sql, "ROUND")) return scaleFunction(sql, true);
    if (input.consumeKeyword(sql, "TRUNCATE")) return scaleFunction(sql, false);
    int current = temporal.currentOperator(sql);
    if (current != 0) {
      StatusCode status = temporal.appendCurrent(current, expressions.program());
      return optionalAtTimeZone(sql, status);
    }
    if (expressions.rowExpression() && input.consumeKeyword(sql, "NULL")) {
      return appendNull(0);
    }
    return optionalAtTimeZone(sql, literalOrColumn(sql));
  }

  void installPostAggregate(SqlPostAggregatePrimary primary) {
    postAggregate = primary;
  }

  private StatusCode literalOrColumn(CharSequence sql) {
    input.skipSpaces(sql);
    int literalStart = input.position();
    StatusCode status = input.literal(sql, literal);
    if (!status.isOk()) {
      if (!expressions.rowExpression() || input.position() != literalStart) {
        return status;
      }
      return appendColumn(sql);
    }
    if (literal.nullValue) {
      return expressions.rowExpression()
          ? appendNull(literal.typeDescriptor)
          : StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (literal.parameter) return appendParameter((int) literal.value);
    if (!admittedLiteral(literal.typeDescriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return appendLiteral(literal.high, literal.value, literal.typeDescriptor);
  }

  private StatusCode optionalAtTimeZone(CharSequence sql, StatusCode status) {
    return status.isOk()
        ? temporal.optionalAtTimeZone(sql, expressions.program()) : status;
  }

  private boolean admittedLiteral(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return SqlNumericTypeRules.isNumeric(descriptor)
        || type == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        || expressions.rowExpression() && type == SqlTypeDescriptor.TYPE_ID_VARCHAR
        || type == SqlTypeDescriptor.TYPE_ID_DATE
        || type == SqlTypeDescriptor.TYPE_ID_TIME
        || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }

  private StatusCode cast(CharSequence sql) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) status = expressions.parseNestedAdditive(sql);
    if (status.isOk()) status = input.requireKeyword(sql, "AS");
    if (status.isOk()) status = input.typeDescriptor(sql, literal);
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    if (!status.isOk() || !expressions.hasValue()) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    int source = expressions.topDescriptor();
    int target = literal.typeDescriptor;
    if (!admittedCast(source, target)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (!expressions.appendNode(SqlScalarExpression.CAST, 0, target)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expressions.replaceTopDescriptor(target);
    return StatusCode.OK;
  }

  private boolean admittedCast(int source, int target) {
    if (expressions.rowExpression()) {
      return source == 0 || SqlTypeDescriptor.canExplicitlyCast(source, target);
    }
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    boolean numeric = SqlNumericTypeRules.isNumeric(source)
        && SqlNumericTypeRules.isNumeric(target);
    boolean bool = sourceType == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        && targetType == SqlTypeDescriptor.TYPE_ID_BOOLEAN;
    return (numeric || bool) && SqlTypeDescriptor.canExplicitlyCast(source, target);
  }

  private StatusCode unaryFunction(CharSequence sql, int operator) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) status = expressions.parseNestedAdditive(sql);
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    return status.isOk() ? expressions.unaryOperator(operator) : status;
  }

  private StatusCode scaleFunction(CharSequence sql, boolean round) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) status = expressions.parseNestedAdditive(sql);
    if (status.isOk()) status = input.requireCharacter(sql, ',');
    if (status.isOk()) status = input.number(sql, literal);
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    if (!status.isOk() || literal.value < 0
        || literal.value > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION
        || !expressions.hasValue()) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    int descriptor = expressions.topDescriptor() == 0 ? 0
        : SqlNumericExpressionTypes.quantized(
            expressions.topDescriptor(), literal.value);
    if (descriptor == 0 && expressions.topDescriptor() != 0) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int operator = round ? SqlScalarExpression.ROUND : SqlScalarExpression.TRUNCATE;
    if (!expressions.appendNode(operator, literal.value, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expressions.replaceTopDescriptor(descriptor);
    return StatusCode.OK;
  }

  private StatusCode appendLiteral(long value, int descriptor) {
    return appendLiteral(value >> 63, value, descriptor);
  }

  private StatusCode appendLiteral(
      long high, long value, int descriptor) {
    if (!expressions.hasStackCapacity()
        || !expressions.appendNode(
            SqlScalarExpression.LITERAL, high, value, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expressions.pushDescriptor(descriptor);
    return StatusCode.OK;
  }

  private StatusCode appendNull(int descriptor) {
    if (!expressions.hasStackCapacity()
        || !expressions.appendNode(SqlScalarExpression.NULL, 0, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expressions.pushDescriptor(descriptor);
    return StatusCode.OK;
  }

  private StatusCode appendParameter(int ordinal) {
    if (ordinal < 0 || !expressions.hasStackCapacity()
        || !expressions.appendNode(SqlScalarExpression.PARAMETER, ordinal, 0)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expressions.pushDescriptor(0);
    return StatusCode.OK;
  }

  private StatusCode appendColumn(CharSequence sql) {
    firstIdentifier.reset();
    secondIdentifier.reset();
    StatusCode status = input.identifier(sql, firstIdentifier);
    if (!status.isOk()) return status;
    SqlIdentifier table = secondIdentifier;
    SqlIdentifier name = firstIdentifier;
    if (input.consumeCharacter(sql, '.')) {
      table = firstIdentifier;
      name = secondIdentifier;
      status = input.identifier(sql, name);
    }
    if (!status.isOk()) return status;
    int symbol = expressions.registerSymbol(table, name);
    if (symbol < 0 || !expressions.hasStackCapacity()
        || !expressions.appendNode(SqlScalarExpression.COLUMN, symbol, 0)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expressions.pushDescriptor(0);
    return StatusCode.OK;
  }
}
