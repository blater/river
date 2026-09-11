package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
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
    int lanes = grouped ? command.grouping().count() : 0;
    for (int invocation = 0; invocation < command.aggregates().invocationCount(); invocation++) {
      int lane = command.aggregates().operandProjection(invocation);
      if (lane >= lanes) lanes = lane + 1;
    }
    bound.projectionPrograms.begin(lanes);
    StatusCode status = bound.projectionPrograms.status();
    if (status.isOk()) status = bound.aggregates.reserve(command.aggregates().invocationCount());
    if (status.isOk() && grouped) {
      for (int expression = 0;
          status.isOk() && expression < command.grouping().count(); expression++) {
        status = expressions.bind(
            command, command.grouping().expression(expression), expression, child, bound);
      }
    }
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregates().invocationCount(); invocation++) {
      int lane = command.aggregates().operandProjection(invocation);
      if (lane >= 0) status = bindLane(command, child, bound, lane);
      if (status.isOk()) status = bindInvocation(command, bound, invocation);
    }
    if (!status.isOk()) return status;
    status = SqlBlockAggregateMetadataPublisher.publish(
        command, child, output, bound, grouped, expressions);
    return status.isOk()
        ? SqlBlockShapeAdmission.finishAggregate(output, having, command, bound) : status;
  }

  StatusCode bindJoined(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      boolean grouped) {
    StatusCode status = bound.aggregates.reserve(command.aggregates().invocationCount());
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregates().invocationCount(); invocation++) {
      int lane = command.aggregates().operandProjection(invocation);
      int input = lane < 0 ? SqlTypeDescriptor.BIGINT : child.descriptor(lane);
      status = SqlBlockAggregateInvocationBinder.bind(command, bound, invocation, lane, input);
    }
    if (status.isOk()) {
      status = SqlBlockAggregateMetadataPublisher.publish(
          command, child, output, bound, grouped, expressions);
    }
    return status.isOk()
        ? SqlBlockShapeAdmission.finishAggregate(output, having, command, bound) : status;
  }

  StatusCode bindLoweredJoin(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound,
      boolean grouped) {
    int lanes = grouped ? command.grouping().count() : 0;
    bound.projectionPrograms.begin(loweredLaneCount(command, lanes));
    StatusCode status = bound.projectionPrograms.status();
    for (int lane = 0; status.isOk() && lane < lanes; lane++) {
      int source = command.grouping().operandProjection(lane);
      if (source < 0 || source >= child.count()) return StatusCode.INVALID_EXTERNAL_INPUT;
      status = appendColumnLane(bound, lane, source, child.descriptor(source));
    }
    if (status.isOk()) status = bound.aggregates.reserve(command.aggregates().invocationCount());
    for (int invocation = 0;
        status.isOk() && invocation < command.aggregates().invocationCount(); invocation++) {
      int source = command.aggregates().operandProjection(invocation);
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
    status = SqlBlockAggregateMetadataPublisher.publish(
        command, child, output, bound, grouped, expressions);
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

  private StatusCode bindInvocationFromChild(
      SqlCommand command, SqlBlockSchema child,
      BoundSqlStatement bound, int invocation, int source, int lane) {
    int input = source < 0 ? SqlTypeDescriptor.BIGINT : child.descriptor(source);
    return SqlBlockAggregateInvocationBinder.bind(command, bound, invocation, lane, input);
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
        invocation < command.aggregates().invocationCount(); invocation++) {
      int source = command.aggregates().operandProjection(invocation);
      if (source < 0 || groupSource(command, groups, source)
          || priorAggregateSource(command, invocation, source)) continue;
      count++;
    }
    return count;
  }

  static int requiredOperandLanes(SqlCommand command) {
    int groups = command.grouping().count();
    if (command.joinChain() != null) return loweredLaneCount(command, groups);
    int lanes = groups;
    for (int invocation = 0;
        invocation < command.aggregates().invocationCount(); invocation++) {
      int operand = command.aggregates().operandProjection(invocation);
      if (operand >= lanes) lanes = operand + 1;
    }
    return lanes;
  }

  private static boolean groupSource(
      SqlCommand command, int groups, int source) {
    for (int group = 0; group < groups; group++) {
      if (command.grouping().operandProjection(group) == source) return true;
    }
    return false;
  }

  private static boolean priorAggregateSource(
      SqlCommand command, int invocation, int source) {
    for (int prior = 0; prior < invocation; prior++) {
      if (command.aggregates().operandProjection(prior) == source) return true;
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

  private StatusCode bindInvocation(
      SqlCommand command, BoundSqlStatement bound, int invocation) {
    int lane = command.aggregates().operandProjection(invocation);
    int input = lane < 0
        ? SqlTypeDescriptor.BIGINT : bound.projectionPrograms.resultDescriptor(lane);
    return SqlBlockAggregateInvocationBinder.bind(command, bound, invocation, lane, input);
  }
}
