package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlNumericExpressionTypes;
import io.riverdb.sql.SqlScalarExpression;

/** Descriptor rules for fixed-width expressions over one aggregate result. */
final class SqlPostAggregateExpressionTypes {
  private SqlPostAggregateExpressionTypes() {
  }

  static int unary(int operator, int source, int target, long operand) {
    int temporal = SqlRowExpressionTypes.unaryDescriptor(
        operator, source, target, operand);
    if (temporal != 0) return temporal;
    if (!SqlNumericTypeRules.isNumeric(source)) return 0;
    return switch (operator) {
      case SqlScalarExpression.NEGATE, SqlScalarExpression.ABSOLUTE -> source;
      case SqlScalarExpression.CEILING, SqlScalarExpression.FLOOR ->
          SqlNumericTypeRules.isIntegral(source)
              ? SqlTypeDescriptor.BIGINT
              : SqlNumericTypeRules.isApproximate(source) ? source
              : SqlTypeDescriptor.decimal(
                  SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 0);
      case SqlScalarExpression.ROUND, SqlScalarExpression.TRUNCATE ->
          SqlNumericExpressionTypes.quantized(source, operand);
      case SqlScalarExpression.CAST -> SqlNumericTypeRules.isNumeric(target)
              && SqlTypeDescriptor.canExplicitlyCast(source, target)
          ? target : 0;
      default -> 0;
    };
  }

  static int binary(int operator, int left, int right) {
    int temporal = SqlRowExpressionTypes.dateArithmeticDescriptor(
        operator, left, right);
    if (temporal != SqlRowExpressionTypes.UNSUPPORTED_NUMERIC) return temporal;
    return SqlNumericExpressionTypes.binary(operator, left, right);
  }

  static boolean fixedWidth(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return SqlNumericTypeRules.isNumeric(descriptor)
        || type >= SqlTypeDescriptor.TYPE_ID_DATE
            && type <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }
}
