package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;

/** Publishes the compact grouped/distinct result contract to the physical plan. */
final class SqlDescriptorSetPlan {
  private SqlDescriptorSetPlan() { }

  static StatusCode configure(
      SqlCommand command,
      SqlPhysicalPlan plan,
      SqlDescriptorSetShape shape,
      SqlDescriptorSetStorage storage,
      SqlDescriptorSetMaterialization materialization) {
    StatusCode status = plan.beginResult(shape.resultCount());
    for (int output = 0;
        status.isOk() && output < shape.groupOutputCount(); output++) {
      int lane = storage.outputs[output];
      plan.setResultColumn(
          output,
          materialization.rawColumn(lane),
          storage.descriptors[output],
          command.columnOutputName(output));
      plan.setResultNullable(output, materialization.nullable(lane));
    }
    for (int output = 0;
        status.isOk() && output < shape.aggregateOutputCount(); output++) {
      int result = shape.groupOutputCount() + output;
      int invocation = command.aggregateOutputInvocation(output);
      plan.setResultColumn(
          result, -1, storage.descriptors[result],
          aggregateName(command, result, shape.aggregates().kind(invocation)));
      int kind = shape.aggregates().kind(invocation);
      plan.setResultNullable(
          result, kind != SqlAggregateKind.COUNT && kind != SqlAggregateKind.COUNT_VALUE
              && kind != SqlAggregateKind.COUNT_DISTINCT);
    }
    if (status.isOk()) {
      plan.setFilterCount(command.wherePredicates().leafCount());
      if (shape.grouped()) {
        plan.setHavingCount(command.booleanHavingPredicates().leafCount());
        int lane = shape.aggregates().count() == 0
            ? -1 : shape.aggregates().operandLane(0);
        plan.setGroupAggregate(
            materialization.rawColumn(0),
            lane < 0 ? -1 : materialization.rawColumn(lane));
      } else {
        plan.setDistinct(materialization.rawColumn(0));
      }
    }
    return status;
  }

  private static CharSequence aggregateName(
      SqlCommand command, int result, int kind) {
    CharSequence alias = command.columnAlias(result);
    if (alias != null && alias.length() > 0) return alias;
    return switch (kind) {
      case SqlAggregateKind.SUM -> "sum";
      case SqlAggregateKind.AVG -> "avg";
      case SqlAggregateKind.MIN -> "min";
      case SqlAggregateKind.MAX -> "max";
      default -> "count";
    };
  }
}
