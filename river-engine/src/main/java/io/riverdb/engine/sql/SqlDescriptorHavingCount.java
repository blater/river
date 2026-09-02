package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Reusable common HAVING binder/evaluator for descriptor aggregate rows. */
final class SqlDescriptorHavingCount {
  private final BoundSqlStatement bound;
  private final SqlPostAggregateProgramBinder binder = new SqlPostAggregateProgramBinder();
  private final SqlHavingEvaluator evaluator;
  private SqlCommand command;

  SqlDescriptorHavingCount() {
    this(new SqlTemporalContext(), new SqlSessionShapeBudget(null));
  }

  SqlDescriptorHavingCount(
      SqlTemporalContext temporal, SqlSessionShapeBudget shapeBudget) {
    bound = new BoundSqlStatement(shapeBudget);
    evaluator = new SqlHavingEvaluator(
        bound, new SqlExpressionEvaluator(), temporal, shapeBudget);
  }

  StatusCode prepare(SqlCommand source, SqlDescriptorSetShape shape) {
    return prepare(
        source, shape.aggregates(), shape.materialization(), shape.keyCount());
  }

  StatusCode prepare(
      SqlCommand source,
      SqlBoundAggregateSet aggregates,
      SqlDescriptorSetMaterialization materialization,
      int keyCount) {
    reset();
    bound.reset();
    StatusCode status = bound.command.copyBlockFrom(source);
    if (status.isOk()) status = bound.aggregates.reserve(aggregates.count());
    for (int invocation = 0;
        status.isOk() && invocation < aggregates.count(); invocation++) {
      bound.aggregates.append(
          aggregates.kind(invocation),
          aggregates.operandLane(invocation),
          aggregates.inputDescriptor(invocation),
          aggregates.resultDescriptor(invocation));
    }
    if (status.isOk()) status = bound.reserveProjectionColumns(keyCount);
    if (status.isOk()) bound.projectionPrograms.begin(keyCount);
    for (int key = 0; status.isOk() && key < keyCount; key++) {
      int descriptor = materialization.descriptor(key);
      bound.projectedTypeDescriptors[key] = descriptor;
      bound.projectionPrograms.finish(key, descriptor, -1);
    }
    if (status.isOk()) bound.projectedColumnCount = keyCount;
    if (status.isOk()) status = binder.bind(source, bound);
    if (status.isOk()) status = evaluator.prepare(bound.command);
    if (status.isOk()) command = bound.command;
    if (!status.isOk()) reset();
    return status;
  }

  void reset() {
    command = null;
    evaluator.reset();
  }

  StatusCode matches(SqlAggregateAccumulatorSet accumulators) {
    if (command == null) return StatusCode.CONFLICT;
    StatusCode status = evaluator.evaluate(command, accumulators, null, 0);
    return status.isOk() && evaluator.matched() ? StatusCode.OK
        : status.isOk() ? StatusCode.CONFLICT : status;
  }

  StatusCode matches(
      SqlAggregateAccumulatorSet accumulators,
      SqlDescriptorSetKey key,
      SqlDescriptorSetShape shape) {
    if (command == null) return StatusCode.CONFLICT;
    StatusCode status = evaluator.evaluate(
        command,
        accumulators,
        key.row(),
        shape.keyCount());
    return status.isOk() && evaluator.matched() ? StatusCode.OK
        : status.isOk() ? StatusCode.CONFLICT : status;
  }
}
