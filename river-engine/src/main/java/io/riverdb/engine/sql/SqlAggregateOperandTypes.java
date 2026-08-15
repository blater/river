package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Validates the resolved descriptor of one aggregate operand. */
final class SqlAggregateOperandTypes {
  private SqlAggregateOperandTypes() {
  }

  static StatusCode validate(
      SqlCommand command, BoundSqlStatement bound, boolean computed) {
    return validate(command, bound, 0, computed);
  }

  static StatusCode validate(
      SqlCommand command,
      BoundSqlStatement bound,
      int projection,
      boolean computed) {
    int family = SqlTypeDescriptor.comparisonFamily(
        bound.projectedTypeDescriptors[projection]);
    if (computed) {
      StatusCode status = validateComputed(command, projection, family);
      if (!status.isOk()) return status;
    }
    return validateFamily(command.type(), family);
  }

  private static StatusCode validateComputed(
      SqlCommand command, int projection, int family) {
    if (!command.projectionExpression(projection).hasColumnReference()
        || family == SqlTypeDescriptor.COMPARISON_TEXT) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return StatusCode.OK;
  }

  private static StatusCode validateFamily(SqlCommandType type, int family) {
    if ((type == SqlCommandType.SUM
            || type == SqlCommandType.AVG
            || type == SqlCommandType.GROUP_SUM
            || type == SqlCommandType.GROUP_AVG)
        && family != SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if ((type == SqlCommandType.MIN
            || type == SqlCommandType.MAX
            || type == SqlCommandType.GROUP_MIN
            || type == SqlCommandType.GROUP_MAX)
        && family == SqlTypeDescriptor.COMPARISON_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return StatusCode.OK;
  }
}
