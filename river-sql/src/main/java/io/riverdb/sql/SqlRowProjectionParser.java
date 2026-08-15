package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses one row-valued SELECT output into a bounded postfix program. */
final class SqlRowProjectionParser {
  private final SqlSelectParser selects;
  private final SqlScalarExpressionParser expressions;

  SqlRowProjectionParser(
      SqlSelectParser selectParser, SqlScalarExpressionParser expressionParser) {
    selects = selectParser;
    expressions = expressionParser;
  }

  StatusCode parse(CharSequence sql, SqlCommand result) {
    int projection = result.columnCount();
    SqlIdentifier output = result.writableNextColumnName();
    if (output == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = expressions.parseProjection(sql, result, projection);
    if (!status.isOk()) {
      return status;
    }
    SqlScalarExpression expression = result.projectionExpression(projection);
    if (expression.isDirectColumnReference()) {
      result.adoptDirectProjectionName(projection);
    } else if (expression.isNullLiteral()) {
      setIdentifier(output, "null");
      result.markLastProjectionNull();
    }
    return selects.optionalColumnAlias(sql, result, projection);
  }

  private static void setIdentifier(SqlIdentifier target, String value) {
    target.reset();
    for (int index = 0; index < value.length(); index++) {
      target.append(value.charAt(index));
    }
  }
}
