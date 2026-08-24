package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlScalarExpression;

/** Descriptor rules for the bounded temporal row-expression subset. */
final class SqlRowExpressionTypes {
  static final int UNSUPPORTED_NUMERIC = Integer.MIN_VALUE;

  private SqlRowExpressionTypes() {
  }

  static boolean leaf(int operator) {
    return operator == SqlScalarExpression.LITERAL
        || operator >= SqlScalarExpression.CURRENT_DATE
            && operator <= SqlScalarExpression.LOCALTIMESTAMP;
  }

  static boolean unary(int operator) {
    return operator == SqlScalarExpression.CAST
        || operator == SqlScalarExpression.AT_TIME_ZONE
        || operator == SqlScalarExpression.EXTRACT;
  }

  static int unaryDescriptor(
      int operator, int source, int target, long operand) {
    return SqlRowExpressionDescriptors.unaryDescriptor(operator, source, target, operand);
  }

  static int dateArithmeticDescriptor(
      int operator, int left, int right) {
    return SqlRowExpressionDescriptors.dateArithmeticDescriptor(operator, left, right);
  }

  static boolean temporal(int type) {
    return type >= SqlTypeDescriptor.TYPE_ID_DATE
        && type <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }
}
