package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.sql.SqlScalarExpression;

/** Descriptor calculations shared by the row-expression binder and evaluator. */
final class SqlRowExpressionDescriptors {
  private SqlRowExpressionDescriptors() {
  }

  static int unaryDescriptor(int operator, int source, int target, long operand) {
    if (operator == SqlScalarExpression.CAST) {
      return SqlRowExpressionCasts.admitted(source, target) ? target : 0;
    }
    if (operator == SqlScalarExpression.AT_TIME_ZONE) {
      return SqlRowExpressionCasts.atTimeZone(source);
    }
    return SqlRowExpressionTemporalDescriptors.extract(source, (int) operand);
  }

  static int dateArithmeticDescriptor(int operator, int left, int right) {
    if (SqlTypeDescriptor.typeId(left) != SqlTypeDescriptor.TYPE_ID_DATE) {
      return SqlNumericTypeRules.isNumeric(left)
              && SqlNumericTypeRules.isNumeric(right)
          ? SqlRowExpressionTypes.UNSUPPORTED_NUMERIC : 0;
    }
    int rightType = SqlTypeDescriptor.typeId(right);
    if (SqlNumericTypeRules.isIntegral(right)) return SqlTypeDescriptor.DATE;
    return operator == SqlScalarExpression.SUBTRACT
            && rightType == SqlTypeDescriptor.TYPE_ID_DATE
        ? SqlTypeDescriptor.BIGINT : 0;
  }
}
