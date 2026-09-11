package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;

/** Validates one aggregate invocation and records its reusable result shape. */
final class SqlBlockAggregateInvocationBinder {
  private SqlBlockAggregateInvocationBinder() { }

  static StatusCode bind(
      SqlCommand command, BoundSqlStatement bound, int invocation, int lane, int input) {
    int kind = command.aggregateKind(invocation);
    StatusCode status = validate(command, invocation, kind, input);
    int result = status.isOk()
        ? SqlProjectionBinder.aggregateResultDescriptor(kind, input) : 0;
    if (status.isOk() && result == 0) status = StatusCode.DATATYPE_MISMATCH;
    if (status.isOk()) bound.aggregates.append(kind, lane, input, result);
    return status;
  }

  private static StatusCode validate(
      SqlCommand command, int invocation, int kind, int descriptor) {
    int lane = command.aggregateOperandProjection(invocation);
    if (kind == SqlAggregateKind.COUNT || kind == SqlAggregateKind.COUNT_DISTINCT) {
      return kind == SqlAggregateKind.COUNT_DISTINCT
          && (lane < 0 || !command.aggregateOperandExpression(lane).hasColumnReference())
          ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
    }
    if (lane < 0 || !command.aggregateOperandExpression(lane).hasColumnReference()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    int family = SqlTypeDescriptor.comparisonFamily(descriptor);
    if ((kind == SqlAggregateKind.SUM || kind == SqlAggregateKind.AVG)
        && !SqlNumericTypeRules.isNumeric(descriptor)
        || (kind == SqlAggregateKind.MIN || kind == SqlAggregateKind.MAX)
            && family == SqlTypeDescriptor.COMPARISON_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return StatusCode.OK;
  }
}
