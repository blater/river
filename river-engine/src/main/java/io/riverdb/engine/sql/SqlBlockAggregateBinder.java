package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlGroupExpressions;
import io.riverdb.sql.SqlScalarExpression;

/** Binds one scalar/grouped aggregate block against a virtual child schema. */
final class SqlBlockAggregateBinder {
  private final SqlBlockExpressionBinder expressions;
  private final SqlPostAggregateProgramBinder having =
      new SqlPostAggregateProgramBinder();

  SqlBlockAggregateBinder(SqlBlockExpressionBinder expressionBinder) {
    expressions = expressionBinder;
  }

  StatusCode bind(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      boolean grouped) {
    int lanes = grouped ? command.groupExpressionCount() : 0;
    for (int invocation = 0; invocation < command.aggregateInvocationCount(); invocation++) {
      int lane = command.aggregateOperandProjection(invocation);
      if (lane >= lanes) lanes = lane + 1;
    }
    bound.projectionPrograms.begin(lanes);
    StatusCode status = bound.projectionPrograms.status();
    if (status.isOk()) status = bound.aggregates.reserve(command.aggregateInvocationCount());
    if (status.isOk() && grouped) {
      for (int expression = 0;
          status.isOk() && expression < command.groupExpressionCount(); expression++) {
        status = expressions.bind(
            command, command.groupExpression(expression), expression, child, bound);
      }
    }
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregateInvocationCount(); invocation++) {
      int lane = command.aggregateOperandProjection(invocation);
      if (lane >= 0) status = bindLane(command, child, bound, lane);
      if (status.isOk()) status = bindInvocation(command, bound, invocation);
    }
    if (!status.isOk()) return status;
    status = publish(command, child, output, bound, grouped);
    return status.isOk()
        ? SqlBlockShapeAdmission.finishAggregate(output, having, command, bound) : status;
  }

  StatusCode bindJoined(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      boolean grouped) {
    StatusCode status = bound.aggregates.reserve(command.aggregateInvocationCount());
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregateInvocationCount(); invocation++) {
      int lane = command.aggregateOperandProjection(invocation);
      int input = lane < 0 ? SqlTypeDescriptor.BIGINT : child.descriptor(lane);
      status = validate(command, invocation, command.aggregateKind(invocation), input);
      int result = status.isOk()
          ? SqlProjectionBinder.aggregateResultDescriptor(command.aggregateKind(invocation), input)
          : 0;
      if (status.isOk() && result == 0) status = StatusCode.DATATYPE_MISMATCH;
      if (status.isOk()) bound.aggregates.append(
          command.aggregateKind(invocation), lane, input, result);
    }
    if (status.isOk()) status = publish(command, child, output, bound, grouped);
    return status.isOk()
        ? SqlBlockShapeAdmission.finishAggregate(output, having, command, bound) : status;
  }

  StatusCode bindLoweredJoin(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      boolean grouped) {
    int lanes = grouped ? command.groupExpressionCount() : 0;
    bound.projectionPrograms.begin(loweredLaneCount(command, lanes));
    StatusCode status = bound.projectionPrograms.status();
    for (int lane = 0; status.isOk() && lane < lanes; lane++) {
      int source = command.groupOperandProjection(lane);
      if (source < 0 || source >= child.count()) return StatusCode.INVALID_EXTERNAL_INPUT;
      status = appendColumnLane(bound, lane, source, child.descriptor(source));
    }
    if (status.isOk()) status = bound.aggregates.reserve(command.aggregateInvocationCount());
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregateInvocationCount(); invocation++) {
      int source = command.aggregateOperandProjection(invocation);
      int lane = source < 0 ? -1 : existingLane(bound, lanes, source);
      if (source >= 0 && lane < 0) {
        if (source >= child.count()) return StatusCode.INVALID_EXTERNAL_INPUT;
        lane = lanes++;
        status = appendColumnLane(bound, lane, source, child.descriptor(source));
      }
      if (status.isOk()) {
        status = bindInvocationFromChild(command, child, bound, invocation, source, lane);
      }
    }
    if (!status.isOk()) return status;
    status = publish(command, child, output, bound, grouped);
    return status.isOk()
        ? SqlBlockShapeAdmission.finishAggregate(output, having, command, bound) : status;
  }

  private static StatusCode appendColumnLane(
      BoundSqlStatement bound, int lane, int source, int descriptor) {
    bound.projectionPrograms.append(
        lane, SqlScalarExpression.COLUMN, source, descriptor);
    bound.projectionPrograms.finish(lane, descriptor, source);
    return bound.projectionPrograms.status();
  }

  private static StatusCode bindInvocationFromChild(
      SqlCommand command, SqlBlockSchema child,
      BoundSqlStatement bound, int invocation, int source, int lane) {
    int kind = command.aggregateKind(invocation);
    int input = source < 0 ? SqlTypeDescriptor.BIGINT : child.descriptor(source);
    StatusCode status = validate(command, invocation, kind, input);
    int result = status.isOk()
        ? SqlProjectionBinder.aggregateResultDescriptor(kind, input) : 0;
    if (status.isOk() && result == 0) status = StatusCode.DATATYPE_MISMATCH;
    if (status.isOk()) bound.aggregates.append(kind, lane, input, result);
    return status;
  }

  private static int existingLane(
      BoundSqlStatement bound, int lanes, int source) {
    for (int lane = 0; lane < lanes; lane++) {
      if (bound.projectionPrograms.rawColumn(lane) == source) return lane;
    }
    return -1;
  }

  private static int loweredLaneCount(SqlCommand command, int groups) {
    int count = groups;
    for (int invocation = 0;
        invocation < command.aggregateInvocationCount(); invocation++) {
      int source = command.aggregateOperandProjection(invocation);
      if (source < 0 || groupSource(command, groups, source)
          || priorAggregateSource(command, invocation, source)) continue;
      count++;
    }
    return count;
  }

  static int requiredOperandLanes(SqlCommand command) {
    int groups = command.groupExpressionCount();
    if (command.joinChain() != null) return loweredLaneCount(command, groups);
    int lanes = groups;
    for (int invocation = 0;
        invocation < command.aggregateInvocationCount(); invocation++) {
      int operand = command.aggregateOperandProjection(invocation);
      if (operand >= lanes) lanes = operand + 1;
    }
    return lanes;
  }

  private static boolean groupSource(
      SqlCommand command, int groups, int source) {
    for (int group = 0; group < groups; group++) {
      if (command.groupOperandProjection(group) == source) return true;
    }
    return false;
  }

  private static boolean priorAggregateSource(
      SqlCommand command, int invocation, int source) {
    for (int prior = 0; prior < invocation; prior++) {
      if (command.aggregateOperandProjection(prior) == source) return true;
    }
    return false;
  }

  private StatusCode bindLane(
      SqlCommand command,
      SqlBlockSchema child,
      BoundSqlStatement bound,
      int lane) {
    SqlScalarExpression expression = command.aggregateOperandExpression(lane);
    return expressions.bind(command, expression, lane, child, bound);
  }

  private static StatusCode bindInvocation(
      SqlCommand command, BoundSqlStatement bound, int invocation) {
    int kind = command.aggregateKind(invocation);
    int lane = command.aggregateOperandProjection(invocation);
    int input = lane < 0
        ? SqlTypeDescriptor.BIGINT : bound.projectionPrograms.resultDescriptor(lane);
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

  private StatusCode publish(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      boolean grouped) {
    int groups = grouped ? command.columnCount() - command.aggregateOutputCount() : 0;
    int columns = groups + command.aggregateOutputCount();
    int hidden = SqlBlockGroupOrderColumns.hiddenCount(command);
    StatusCode status = bound.reserveProjectionColumns(columns + hidden);
    if (!status.isOk()) return status;
    output.set(columns + hidden);
    if (!output.status().isOk()) return output.status();
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
