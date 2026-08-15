package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses the bounded temporal scalar-function grammar into postfix form. */
final class SqlTemporalScalarParser {
  private final SqlScalarExpressionParser expressions;
  private final SqlParserInput input;
  private final SqlParser.LongResult literal = new SqlParser.LongResult();

  SqlTemporalScalarParser(
      SqlScalarExpressionParser expressionParser, SqlParserInput parserInput) {
    expressions = expressionParser;
    input = parserInput;
  }

  StatusCode parseExtract(CharSequence sql, SqlScalarExpression target) {
    StatusCode status = input.requireCharacter(sql, '(');
    int field = status.isOk()
        ? SqlTemporalExpressionTypes.extractField(input, sql) : 0;
    if (status.isOk() && field == 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) status = input.requireKeyword(sql, "FROM");
    if (status.isOk()) status = expressions.parseNestedAdditive(sql);
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    if (!status.isOk()) return status;
    int descriptor = SqlTemporalExpressionTypes.extractDescriptor(
        expressions.topDescriptor(), field);
    if (descriptor == 0
        && !(expressions.allowsUnresolved() && expressions.topDescriptor() == 0)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (!target.append(SqlScalarExpression.EXTRACT, field, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expressions.replaceTopDescriptor(descriptor);
    return StatusCode.OK;
  }

  int currentOperator(CharSequence sql) {
    if (input.consumeKeyword(sql, "CURRENT_DATE")) {
      return SqlScalarExpression.CURRENT_DATE;
    }
    if (input.consumeKeyword(sql, "CURRENT_TIMESTAMP")) {
      return SqlScalarExpression.CURRENT_TIMESTAMP;
    }
    if (input.consumeKeyword(sql, "LOCALTIME")) {
      return SqlScalarExpression.LOCALTIME;
    }
    return input.consumeKeyword(sql, "LOCALTIMESTAMP")
        ? SqlScalarExpression.LOCALTIMESTAMP : 0;
  }

  StatusCode appendCurrent(int operator, SqlScalarExpression target) {
    int descriptor = switch (operator) {
      case SqlScalarExpression.CURRENT_DATE -> SqlTypeDescriptor.DATE;
      case SqlScalarExpression.CURRENT_TIMESTAMP ->
          SqlTypeDescriptor.timestampWithTimeZone(6);
      case SqlScalarExpression.LOCALTIME -> SqlTypeDescriptor.time(6);
      case SqlScalarExpression.LOCALTIMESTAMP -> SqlTypeDescriptor.timestamp(6);
      default -> 0;
    };
    if (descriptor == 0
        || !expressions.hasStackCapacity()
        || !target.append(operator, 0, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expressions.pushDescriptor(descriptor);
    return StatusCode.OK;
  }

  StatusCode optionalAtTimeZone(CharSequence sql, SqlScalarExpression target) {
    if (!input.consumeKeyword(sql, "AT")) return StatusCode.OK;
    StatusCode status = input.requireKeyword(sql, "TIME");
    if (status.isOk()) status = input.requireKeyword(sql, "ZONE");
    if (status.isOk()) status = input.packedText(sql, literal);
    if (!status.isOk()) return status;
    int source = expressions.topDescriptor();
    int sourceType = SqlTypeDescriptor.typeId(source);
    int descriptor = sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        ? SqlTypeDescriptor.timestampWithTimeZone(SqlTypeDescriptor.parameterOne(source))
        : sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
            ? SqlTypeDescriptor.timestamp(SqlTypeDescriptor.parameterOne(source)) : 0;
    if (descriptor == 0 && !(expressions.allowsUnresolved() && source == 0)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (!target.append(SqlScalarExpression.AT_TIME_ZONE, literal.value, descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    expressions.replaceTopDescriptor(descriptor);
    return StatusCode.OK;
  }
}
