package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlGroupExpressions;

/** Publishes aggregate output descriptors and hidden grouping columns. */
final class SqlBlockAggregateMetadataPublisher {
  private SqlBlockAggregateMetadataPublisher() { }

  static StatusCode publish(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      boolean grouped,
      SqlBlockExpressionBinder expressions) {
    int groups = grouped ? command.columnCount() - command.aggregateOutputCount() : 0;
    int columns = groups + command.aggregateOutputCount();
    int hidden = SqlBlockGroupOrderColumns.hiddenCount(command);
    StatusCode status = bound.reserveProjectionColumns(columns + hidden);
    if (!status.isOk()) return status;
    output.set(columns + hidden);
    if (!output.status().isOk()) return output.status();
    publishGroups(command, child, output, bound, groups, expressions);
    publishAggregates(command, output, bound, groups);
    return publishOrderColumns(command, child, output, bound, groups, columns, expressions);
  }

  private static void publishGroups(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      int groups,
      SqlBlockExpressionBinder expressions) {
    for (int outputColumn = 0; outputColumn < groups; outputColumn++) {
      int group = SqlGroupExpressions.groupKey(command, outputColumn);
      if (group < 0) continue;
      int descriptor = bound.projectionPrograms.resultDescriptor(group);
      int source = bound.projectionPrograms.rawColumn(group);
      boolean nullable = source >= 0 ? child.nullable(source)
          : expressions.nullable(command, command.groupExpression(group), child);
      output.setColumn(
          outputColumn, command.columnOutputName(outputColumn), descriptor, nullable);
      bound.projectedTypeDescriptors[outputColumn] = descriptor;
    }
  }

  private static void publishAggregates(
      SqlCommand command, SqlBlockSchema output, BoundSqlStatement bound, int groups) {
    for (int outputColumn = 0; outputColumn < command.aggregateOutputCount(); outputColumn++) {
      int invocation = command.aggregateOutputInvocation(outputColumn);
      int aggregateColumn = groups + outputColumn;
      int aggregateKind = bound.aggregates.kind(invocation);
      output.setColumn(
          aggregateColumn,
          SqlResultMetadata.invocationColumnName(command, aggregateColumn, aggregateKind),
          bound.aggregates.resultDescriptor(invocation),
          aggregateKind != SqlAggregateKind.COUNT
              && aggregateKind != SqlAggregateKind.COUNT_VALUE
              && aggregateKind != SqlAggregateKind.COUNT_DISTINCT);
      bound.projectedTypeDescriptors[aggregateColumn] =
          bound.aggregates.resultDescriptor(invocation);
    }
  }

  private static StatusCode publishOrderColumns(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      int groups,
      int columns,
      SqlBlockExpressionBinder expressions) {
    int privateColumn = columns;
    for (int order = 0; order < command.orderExpressionCount(); order++) {
      CharSequence name = command.orderColumnName(order);
      if (SqlBlockGroupOrderColumns.selected(command, name)
          || output.find(name) >= 0) continue;
      int group = SqlBlockGroupOrderColumns.group(command, name);
      if (group < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      int source = bound.projectionPrograms.rawColumn(group);
      int descriptor = bound.projectionPrograms.resultDescriptor(group);
      output.setColumn(
          privateColumn++, name, descriptor,
          source >= 0 ? child.nullable(source)
              : expressions.nullable(command, command.groupExpression(group), child));
    }
    bound.projectedColumnCount = columns;
    return output.status();
  }
}
