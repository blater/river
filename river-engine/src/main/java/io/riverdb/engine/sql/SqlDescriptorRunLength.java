package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Status-preserving reusable run-length carrier for descriptor set execution. */
final class SqlDescriptorRunLength {
  private long count;

  StatusCode measure(
      SqlDescriptorOrderedRows rows,
      SqlDescriptorSetShape shape,
      SqlDescriptorSetKey key,
      SqlAggregateAccumulatorSet accumulators) {
    count = 1;
    StatusCode status = accumulate(rows, shape, accumulators);
    while (status.isOk()) {
      status = rows.readOffset(count);
      if (status == StatusCode.CONFLICT) return StatusCode.OK;
      if (!status.isOk()) return status;
      if (!key.same(rows.row(), shape)) return StatusCode.OK;
      status = accumulate(rows, shape, accumulators);
      if (status.isOk()) {
        if (count == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
        count++;
      }
    }
    return status;
  }

  long count() { return count; }

  private static StatusCode accumulate(
      SqlDescriptorOrderedRows rows,
      SqlDescriptorSetShape shape,
      SqlAggregateAccumulatorSet accumulators) {
    return shape.grouped()
        ? accumulators.accumulateBlock(shape.aggregates(), rows.row())
        : StatusCode.OK;
  }
}
