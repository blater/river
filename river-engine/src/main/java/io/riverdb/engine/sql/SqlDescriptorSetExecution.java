package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Retained DISTINCT and grouped COUNT execution over one descriptor scan. */
final class SqlDescriptorSetExecution {
  private final SqlDescriptorHavingCount having;
  private final SqlDescriptorSetKey key = new SqlDescriptorSetKey();
  private final SqlDescriptorSetShape shape;
  private final SqlDescriptorRunLength run = new SqlDescriptorRunLength();
  private final SqlAggregateAccumulatorSet accumulators;
  private boolean active;

  SqlDescriptorSetExecution() {
    this(new SqlTemporalContext(), new SqlSessionShapeBudget(null));
  }

  SqlDescriptorSetExecution(
      SqlTemporalContext temporal, SqlSessionShapeBudget shapeBudget) {
    accumulators = new SqlAggregateAccumulatorSet(shapeBudget);
    having = new SqlDescriptorHavingCount(temporal, shapeBudget);
    shape = new SqlDescriptorSetShape(
        SqlRetainedArrayAllocator.STANDARD, temporal, shapeBudget);
  }

  boolean handles(SqlCommand command) {
    return command.type() == SqlCommandType.DISTINCT_SCAN
        || SqlDescriptorQueryTypes.grouped(command.type());
  }

  void reset() {
    active = false;
    having.reset();
  }

  StatusCode prepare(
      SqlCommand command, TableDescriptor table, SqlPhysicalPlan plan) {
    active = false;
    StatusCode status = shape.prepare(command, table, plan);
    if (status.isOk() && shape.grouped()) {
      status = SqlAggregateAccumulatorCapacity.reserve(accumulators, shape.aggregates());
    }
    if (status.isOk()) status = key.prepare(shape.materialization());
    if (status.isOk() && shape.grouped()) status = key.prepareAggregateText(accumulators);
    if (status.isOk() && shape.grouped()) status = having.prepare(command, shape);
    if (status.isOk()) {
      active = true;
    }
    return status;
  }

  int sourceColumn() { return shape.firstSourceColumn(); }
  int[] sortColumns() { return shape.sortColumns(); }
  boolean[] descending() { return shape.descending(); }
  int keyCount() { return shape.keyCount(); }
  SqlDescriptorSetMaterialization materialization() { return shape.materialization(); }
  boolean active() { return active; }

  StatusCode next(
      SqlScanCursor cursor, SqlScanRowResult result, SqlDescriptorOrderedRows rows) {
    while (!cursor.limitReached()) {
      StatusCode status = rows.read();
      if (!status.isOk()) return status;
      status = key.capture(rows.row());
      if (!status.isOk()) return status;
      if (shape.grouped()) status = accumulators.reset(shape.aggregates());
      if (!status.isOk()) return status;
      status = run.measure(rows, shape, key, accumulators);
      if (!status.isOk()) return status;
      long count = run.count();
      if (shape.grouped()) status = accumulators.finish(shape.aggregates());
      if (status.isOk() && shape.grouped()) status = having.matches(
          accumulators, key, shape);
      if (status == StatusCode.CONFLICT) {
        status = rows.advance(count);
        if (!status.isOk()) return status;
        continue;
      }
      if (!status.isOk()) return status;
      if (status.isOk()) status = key.publish(result, shape, accumulators);
      if (status.isOk()) {
        status = rows.advance(count);
        if (!status.isOk()) return status;
        cursor.rowReturned();
      }
      return status;
    }
    return StatusCode.CONFLICT;
  }

}
