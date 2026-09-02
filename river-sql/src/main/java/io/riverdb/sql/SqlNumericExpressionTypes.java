package io.riverdb.sql;

import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Shared SQL result descriptors for exact and approximate numeric expressions. */
public final class SqlNumericExpressionTypes {
  private SqlNumericExpressionTypes() { }

  public static int binary(int operator, int left, int right) {
    if (!SqlNumericTypeRules.isNumeric(left) || !SqlNumericTypeRules.isNumeric(right)) {
      return 0;
    }
    if (SqlNumericTypeRules.isApproximate(left)
        || SqlNumericTypeRules.isApproximate(right)) {
      return SqlTypeDescriptor.typeId(left) == SqlTypeDescriptor.TYPE_ID_DOUBLE
              || SqlTypeDescriptor.typeId(right) == SqlTypeDescriptor.TYPE_ID_DOUBLE
          ? SqlTypeDescriptor.DOUBLE : SqlTypeDescriptor.REAL;
    }
    return switch (operator) {
      case SqlScalarExpression.ADD, SqlScalarExpression.SUBTRACT ->
          ExactDecimal.addResultDescriptor(left, right);
      case SqlScalarExpression.MULTIPLY -> ExactDecimal.multiplyResultDescriptor(left, right);
      case SqlScalarExpression.DIVIDE -> ExactDecimal.divideResultDescriptor(left, right);
      case SqlScalarExpression.REMAINDER -> ExactDecimal.remainderResultDescriptor(left, right);
      default -> 0;
    };
  }

  public static int quantized(int source, long scale) {
    if (!SqlNumericTypeRules.isNumeric(source)
        || scale < 0 || scale > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION) return 0;
    return SqlNumericTypeRules.isApproximate(source)
        ? source : ExactDecimal.quantizedDescriptor(source, (int) scale);
  }
}
