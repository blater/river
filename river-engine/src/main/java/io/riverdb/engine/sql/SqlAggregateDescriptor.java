package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommandType;

/** Result type rules for scalar and grouped aggregate commands. */
final class SqlAggregateDescriptor {
  private SqlAggregateDescriptor() { }

  static int command(SqlCommandType type, int inputDescriptor) {
    if (type == SqlCommandType.COUNT || type == SqlCommandType.COUNT_VALUE
        || type == SqlCommandType.GROUP_COUNT || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.COUNT_DISTINCT
        || type == SqlCommandType.GROUP_COUNT_DISTINCT) {
      return SqlTypeDescriptor.BIGINT;
    }
    if (type == SqlCommandType.GROUP_MIN || type == SqlCommandType.GROUP_MAX
        || type == SqlCommandType.MIN || type == SqlCommandType.MAX) return inputDescriptor;
    if (SqlNumericTypeRules.isApproximate(inputDescriptor)) {
      return type == SqlCommandType.AVG || type == SqlCommandType.GROUP_AVG
          ? SqlTypeDescriptor.DOUBLE : inputDescriptor;
    }
    if (type == SqlCommandType.AVG || type == SqlCommandType.GROUP_AVG) {
      int scale = SqlExactNumericDigits.scale(inputDescriptor);
      int integerDigits = SqlExactNumericDigits.integer(inputDescriptor);
      int resultScale = Math.min(Math.max(scale, 6),
          Math.max(0, SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION - integerDigits));
      return SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, resultScale);
    }
    return SqlTypeDescriptor.typeId(inputDescriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION,
            SqlTypeDescriptor.parameterTwo(inputDescriptor)) : SqlTypeDescriptor.BIGINT;
  }

  static int kind(int kind, int inputDescriptor) {
    return switch (kind) {
      case SqlAggregateKind.COUNT, SqlAggregateKind.COUNT_VALUE,
          SqlAggregateKind.COUNT_DISTINCT -> SqlTypeDescriptor.BIGINT;
      case SqlAggregateKind.MIN, SqlAggregateKind.MAX -> inputDescriptor;
      case SqlAggregateKind.AVG -> command(SqlCommandType.AVG, inputDescriptor);
      case SqlAggregateKind.SUM -> command(SqlCommandType.SUM, inputDescriptor);
      default -> 0;
    };
  }
}
