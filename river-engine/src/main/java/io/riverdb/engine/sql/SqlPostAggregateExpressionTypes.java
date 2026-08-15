package io.riverdb.engine.sql;

import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlScalarExpression;

/** Descriptor rules for fixed-width expressions over one aggregate result. */
final class SqlPostAggregateExpressionTypes {
  private SqlPostAggregateExpressionTypes() {
  }

  static int unary(int operator, int source, int target, long operand) {
    int temporal = SqlRowExpressionTypes.unaryDescriptor(
        operator, source, target, operand);
    if (temporal != 0) return temporal;
    if (!exact(source)) return 0;
    return switch (operator) {
      case SqlScalarExpression.NEGATE, SqlScalarExpression.ABSOLUTE -> source;
      case SqlScalarExpression.CEILING, SqlScalarExpression.FLOOR ->
          source == SqlTypeDescriptor.BIGINT
              ? SqlTypeDescriptor.BIGINT
              : SqlTypeDescriptor.decimal(
                  SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 0);
      case SqlScalarExpression.ROUND, SqlScalarExpression.TRUNCATE ->
          operand >= 0 && operand <= SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION
              ? ExactDecimal.quantizedDescriptor(source, (int) operand) : 0;
      case SqlScalarExpression.CAST -> exact(target)
              && SqlTypeDescriptor.canExplicitlyCast(source, target)
          ? target : 0;
      default -> 0;
    };
  }

  static int binary(int operator, int left, int right) {
    int temporal = SqlRowExpressionTypes.dateArithmeticDescriptor(
        operator, left, right);
    if (temporal != SqlRowExpressionTypes.UNSUPPORTED_NUMERIC) return temporal;
    return switch (operator) {
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
  }

  static boolean fixedWidth(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_BIGINT
        || type == SqlTypeDescriptor.TYPE_ID_DECIMAL
        || type >= SqlTypeDescriptor.TYPE_ID_DATE
            && type <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }

  private static boolean exact(int descriptor) {
    return SqlTypeDescriptor.comparisonFamily(descriptor)
        == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC;
  }
}
