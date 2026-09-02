package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlGroupExpressions;

/** Publishes finalized aggregate and group-key values into an owned block row. */
final class SqlBlockAggregatePublisher {
  private final BoundSqlStatement bound;
  private final SqlBlockAggregateValuePublisher values =
      new SqlBlockAggregateValuePublisher();

  SqlBlockAggregatePublisher(BoundSqlStatement statement) {
    bound = statement;
  }

  StatusCode publish(
      int block,
      SqlAggregateAccumulatorSet accumulator,
      SqlBlockRow groupKey,
      SqlBlockRow destination,
      boolean grouped) {
    int groups = grouped
        ? bound.command.columnCount() - bound.command.aggregateOutputCount() : 0;
    SqlBlockSchema schema = bound.blockPlans().schema(block);
    StatusCode status = destination.reset(schema.count());
    if (status.isOk() && grouped) status = publishGroup(block, groupKey, destination, groups);
    for (int output = 0; status.isOk() && output < bound.command.aggregateOutputCount(); output++) {
      int selected = bound.command.aggregateOutputInvocation(output);
      int descriptor = bound.aggregates.resultDescriptor(selected);
      status = values.publish(
          accumulator, selected, descriptor, destination, groups + output);
    }
    for (int column = groups + bound.command.aggregateOutputCount();
        status.isOk() && column < schema.count(); column++) {
      int group = SqlBlockGroupOrderColumns.group(bound.command, schema.name(column));
      status = group < 0 ? StatusCode.CORRUPTION
          : publishGroupValue(schema, column, groupKey, group, destination);
    }
    return status;
  }

  void reset() { }

  private StatusCode publishGroup(
      int block, SqlBlockRow groupKey, SqlBlockRow destination, int groups) {
    SqlBlockSchema schema = bound.blockPlans().schema(block);
    for (int output = 0; output < groups; output++) {
      int group = SqlGroupExpressions.groupKey(bound.command, output);
      if (group < 0) return StatusCode.CORRUPTION;
      StatusCode status = publishGroupValue(schema, output, groupKey, group, destination);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static StatusCode publishGroupValue(
      SqlBlockSchema schema,
      int output,
      SqlBlockRow groupKey,
      int group,
      SqlBlockRow destination) {
    if (groupKey.nullValue(group)) {
      destination.setNull(output);
      return StatusCode.OK;
    }
    int descriptor = schema.descriptor(output);
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      destination.setDecimal128(output, groupKey.highValue(group), groupKey.value(group));
    } else destination.setValue(output, groupKey.value(group));
    return schema.varchar(output)
        ? destination.setText(output, groupKey.text(group), 0, groupKey.textLength(group))
        : StatusCode.OK;
  }
}
