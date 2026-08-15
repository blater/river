package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Parses and compares bounded aggregate operand programs. */
final class SqlAggregateExpressionParser {
  private final SqlScalarExpressionParser expressions;
  private final SqlScalarExpression scratch = new SqlScalarExpression();

  SqlAggregateExpressionParser(SqlScalarExpressionParser expressionParser) {
    expressions = expressionParser;
  }

  StatusCode parse(CharSequence sql, SqlCommand result) {
    int projection = result.columnCount();
    if (result.writableNextColumnName() == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = expressions.parseProjection(sql, result, projection);
    SqlScalarExpression expression = result.projectionExpression(projection);
    if (status.isOk() && expression.isNullLiteral()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk() && expression.isDirectColumnReference()) {
      result.adoptDirectProjectionName(projection);
    }
    return status;
  }

  boolean startsComputed(CharSequence sql) {
    return expressions.starts(sql);
  }

  StatusCode match(
      CharSequence sql, SqlCommand command, int projection) {
    StatusCode status = expressions.parseProjectionScratch(sql, command, scratch);
    if (!status.isOk()) return status;
    SqlScalarExpression selected = command.projectionExpression(projection);
    return same(command, selected, scratch)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode parseScratch(
      CharSequence sql, SqlCommand command, SqlScalarExpression result) {
    return expressions.parseProjectionScratch(sql, command, result);
  }

  static boolean same(
      SqlCommand command,
      SqlScalarExpression selected,
      SqlScalarExpression repeated) {
    if (selected == null || !selected.isAvailable()
        || selected.nodeCount() != repeated.nodeCount()) {
      return false;
    }
    for (int node = 0; node < selected.nodeCount(); node++) {
      int operator = selected.operator(node);
      int descriptor = selected.typeDescriptor(node);
      if (operator != repeated.operator(node)
          || descriptor != repeated.typeDescriptor(node)
          || !sameOperand(
              command,
              operator,
              descriptor,
              selected.operand(node),
              repeated.operand(node))) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameOperand(
      SqlCommand command,
      int operator,
      int descriptor,
      long selected,
      long repeated) {
    if (operator != SqlScalarExpression.AT_TIME_ZONE
        && (operator != SqlScalarExpression.LITERAL
            || SqlTypeDescriptor.typeId(descriptor)
                != SqlTypeDescriptor.TYPE_ID_VARCHAR)) {
      return selected == repeated;
    }
    int length = command.textByteLength(selected);
    if (length < 0 || length != command.textByteLength(repeated)) return false;
    for (int index = 0; index < length; index++) {
      if (command.textByteAt(selected, index) != command.textByteAt(repeated, index)) {
        return false;
      }
    }
    return true;
  }
}
