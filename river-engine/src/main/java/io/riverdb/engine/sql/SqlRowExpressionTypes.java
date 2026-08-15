package io.riverdb.engine.sql;

import io.riverdb.base.type.LocalTemporal;
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
    if (operator == SqlScalarExpression.CAST) {
      return admittedCast(source, target) ? target : 0;
    }
    if (operator == SqlScalarExpression.AT_TIME_ZONE) {
      int type = SqlTypeDescriptor.typeId(source);
      return type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
          ? SqlTypeDescriptor.timestampWithTimeZone(
              SqlTypeDescriptor.parameterOne(source))
          : type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
              ? SqlTypeDescriptor.timestamp(SqlTypeDescriptor.parameterOne(source)) : 0;
    }
    return extractDescriptor(source, (int) operand);
  }

  static int dateArithmeticDescriptor(
      int operator, int left, int right) {
    if (SqlTypeDescriptor.typeId(left) != SqlTypeDescriptor.TYPE_ID_DATE) {
      return SqlTypeDescriptor.comparisonFamily(left)
                  == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC
              && SqlTypeDescriptor.comparisonFamily(right)
                  == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC
          ? UNSUPPORTED_NUMERIC : 0;
    }
    int rightType = SqlTypeDescriptor.typeId(right);
    if (rightType == SqlTypeDescriptor.TYPE_ID_BIGINT) return SqlTypeDescriptor.DATE;
    return operator == SqlScalarExpression.SUBTRACT
            && rightType == SqlTypeDescriptor.TYPE_ID_DATE
        ? SqlTypeDescriptor.BIGINT : 0;
  }

  static boolean temporal(int type) {
    return type >= SqlTypeDescriptor.TYPE_ID_DATE
        && type <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }

  private static boolean admittedCast(int source, int target) {
    if (!SqlTypeDescriptor.canExplicitlyCast(source, target)) return false;
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    return temporalCastPair(sourceType, targetType)
        || sourceType == SqlTypeDescriptor.TYPE_ID_VARCHAR && temporal(targetType)
        || targetType == SqlTypeDescriptor.TYPE_ID_VARCHAR && temporal(sourceType);
  }

  private static boolean temporalCastPair(int source, int target) {
    return source == target && temporal(source)
        || source == SqlTypeDescriptor.TYPE_ID_DATE
            && target == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || source == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            && (target == SqlTypeDescriptor.TYPE_ID_DATE
                || target == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE)
        || source == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
            && target == SqlTypeDescriptor.TYPE_ID_TIMESTAMP;
  }

  private static int extractDescriptor(int source, int field) {
    int type = SqlTypeDescriptor.typeId(source);
    if (field >= LocalTemporal.EXTRACT_YEAR && field <= LocalTemporal.EXTRACT_DAY) {
      return type == SqlTypeDescriptor.TYPE_ID_DATE
              || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
              || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
          ? SqlTypeDescriptor.BIGINT : 0;
    }
    if (field >= LocalTemporal.EXTRACT_HOUR && field <= LocalTemporal.EXTRACT_SECOND) {
      if (type != SqlTypeDescriptor.TYPE_ID_TIME
          && type != SqlTypeDescriptor.TYPE_ID_TIMESTAMP
          && type != SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE) return 0;
      int precision = SqlTypeDescriptor.parameterOne(source);
      return field == LocalTemporal.EXTRACT_SECOND
          ? SqlTypeDescriptor.decimal(2 + precision, precision)
          : SqlTypeDescriptor.BIGINT;
    }
    return (field == LocalTemporal.EXTRACT_TIMEZONE_HOUR
            || field == LocalTemporal.EXTRACT_TIMEZONE_MINUTE)
            && type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        ? SqlTypeDescriptor.BIGINT : 0;
  }
}
