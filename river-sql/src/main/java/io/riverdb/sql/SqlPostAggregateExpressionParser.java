package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses one bounded expression over selected aggregate results and the group key. */
final class SqlPostAggregateExpressionParser {
  private final SqlScalarExpressionParser expressions;
  private final SqlAggregateExpressionParser selected;
  private final SqlPostAggregatePrimary primary;

  SqlPostAggregateExpressionParser(
      SqlParserInput input, SqlAggregateExpressionParser selectedAggregate) {
    expressions = new SqlScalarExpressionParser(input);
    selected = selectedAggregate;
    primary = new SqlPostAggregatePrimary(expressions, input);
    expressions.installPostAggregate(primary);
  }

  StatusCode parse(
      CharSequence sql,
      SqlCommand command,
      boolean grouped,
      SqlScalarExpression expression) {
    primary.begin(command, grouped, selected);
    expression.reset();
    StatusCode status = expressions.parseProjectionScratch(
        sql, command, expression);
    boolean valid = primary.valid(expression);
    primary.reset();
    if (status.isOk() && !valid) {
      status = StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (!status.isOk()) expression.reset();
    return status;
  }
}
