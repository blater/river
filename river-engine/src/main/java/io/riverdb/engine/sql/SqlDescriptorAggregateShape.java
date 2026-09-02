package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;

/** Binds a direct-column descriptor aggregate set to reusable physical lanes. */
final class SqlDescriptorAggregateShape {
  private final SqlBoundAggregateSet bound = new SqlBoundAggregateSet();

  StatusCode prepare(
      SqlCommand command,
      TableDescriptor table,
      SqlDescriptorSetMaterialization materialization) {
    bound.reset();
    StatusCode status = bound.reserve(command.aggregateInvocationCount());
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregateInvocationCount(); invocation++) {
      int projection = command.aggregateOperandProjection(invocation);
      int column = projection < 0 ? -1 : materialization.aggregateLane(invocation);
      int input = column < 0
          ? SqlTypeDescriptor.BIGINT : materialization.descriptor(column);
      int kind = command.aggregateKind(invocation);
      status = validate(kind, projection, column, input);
      int result = status.isOk()
          ? SqlProjectionBinder.aggregateResultDescriptor(kind, input) : 0;
      if (status.isOk() && result == 0) status = StatusCode.DATATYPE_MISMATCH;
      if (status.isOk()) bound.append(kind, column, input, result);
    }
    return status;
  }

  SqlBoundAggregateSet bound() { return bound; }

  private static StatusCode validate(
      int kind, int projection, int column, int descriptor) {
    if (kind == SqlAggregateKind.COUNT || kind == SqlAggregateKind.COUNT_DISTINCT) {
      return kind == SqlAggregateKind.COUNT_DISTINCT && (projection < 0 || column < 0)
          ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
    }
    if (projection < 0 || column < 0) return StatusCode.FEATURE_NOT_SUPPORTED;
    int family = SqlTypeDescriptor.comparisonFamily(descriptor);
    if ((kind == SqlAggregateKind.SUM || kind == SqlAggregateKind.AVG)
        && !SqlNumericTypeRules.isNumeric(descriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return (kind == SqlAggregateKind.MIN || kind == SqlAggregateKind.MAX)
            && family == SqlTypeDescriptor.COMPARISON_BOOLEAN
        ? StatusCode.DATATYPE_MISMATCH : StatusCode.OK;
  }
}
