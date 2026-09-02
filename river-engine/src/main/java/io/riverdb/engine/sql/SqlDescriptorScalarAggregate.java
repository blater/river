package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Reusable first-class scalar aggregate state over descriptor-backed rows. */
final class SqlDescriptorScalarAggregate {
  private final SqlDescriptorSetMaterialization materialization;
  private final SqlDescriptorAggregateShape shape = new SqlDescriptorAggregateShape();
  private final SqlAggregateAccumulatorSet accumulators;
  private final SqlDescriptorBlockRowValues input;
  private final SqlBlockRow projected;
  private final SqlDescriptorHavingCount having;
  private final SqlDescriptorAggregateResult output = new SqlDescriptorAggregateResult();
  private boolean available;

  SqlDescriptorScalarAggregate(
      SqlTemporalContext temporal, SqlSessionShapeBudget shapeBudget) {
    accumulators = new SqlAggregateAccumulatorSet(shapeBudget);
    materialization = new SqlDescriptorSetMaterialization(
        SqlRetainedArrayAllocator.STANDARD, temporal, shapeBudget);
    input = new SqlDescriptorBlockRowValues(shapeBudget);
    projected = new SqlBlockRow(shapeBudget);
    having = new SqlDescriptorHavingCount(temporal, shapeBudget);
  }

  StatusCode prepare(
      SqlCommand command, TableDescriptor table, SqlPhysicalPlan plan) {
    StatusCode status = reset();
    if (!status.isOk()) return status;
    status = materialization.prepare(command, table, 0);
    if (status.isOk()) status = shape.prepare(command, table, materialization);
    if (status.isOk()) status = SqlAggregateAccumulatorCapacity.reserve(
        accumulators, shape.bound());
    if (status.isOk()) status = input.prepare(table);
    if (status.isOk()) status = prepareProjected();
    if (status.isOk()) status = having.prepare(
        command, shape.bound(), materialization, 0);
    if (status.isOk()) status = output.prepare(command, shape.bound(), plan);
    if (status.isOk()) configurePlan(command, plan);
    if (status.isOk()) status = output.prepareText(accumulators);
    if (status.isOk()) status = accumulators.reset(shape.bound());
    return status;
  }

  StatusCode accumulate(io.riverdb.base.type.SqlValueBuffer values) {
    StatusCode status = input.load(values);
    if (status.isOk()) status = materialization.project(input.row(), projected);
    return status.isOk()
        ? accumulators.accumulateBlock(shape.bound(), projected) : status;
  }

  StatusCode finish() {
    StatusCode status = accumulators.finish(shape.bound());
    if (status.isOk()) status = having.matches(accumulators);
    available = status.isOk();
    return status == StatusCode.CONFLICT ? StatusCode.OK : status;
  }

  StatusCode publish(SqlExecutionResult result, long commitSequence) {
    return available ? output.publish(result, accumulators, commitSequence) : StatusCode.OK;
  }

  StatusCode next(
      SqlScanCursor cursor, SqlScanRowResult result) {
    if (!available || cursor.rowsReturned() > 0) return StatusCode.CONFLICT;
    StatusCode status = output.publish(result, accumulators);
    if (status.isOk()) cursor.rowReturned();
    return status;
  }

  StatusCode reset() {
    available = false;
    having.reset();
    StatusCode status = accumulators.closeDistinct();
    StatusCode cleared = accumulators.clear(shape.bound());
    if (status.isOk()) status = cleared;
    materialization.reset();
    input.reset();
    return status;
  }

  private StatusCode prepareProjected() {
    return projected.reset(materialization.laneCount());
  }

  private void configurePlan(SqlCommand command, SqlPhysicalPlan plan) {
    if (plan == null) return;
    plan.setHavingCount(command.booleanHavingPredicates().leafCount());
    int lane = shape.bound().count() == 0 ? -1 : shape.bound().operandLane(0);
    plan.setAggregate(lane < 0 ? -1 : materialization.rawColumn(lane));
  }
}
