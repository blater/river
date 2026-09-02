package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;

/** Lazily admitted query-block objects retained at a session high-water mark. */
final class SqlBoundQueryBlocks {
  private static final long CHARGED_BLOCK_BYTES = 512;
  private static final long CHARGED_REFERENCE_BYTES = 8;
  private BoundSqlQuery.Block[] blocks = new BoundSqlQuery.Block[0];
  private final SqlSessionShapeBudget budget;

  SqlBoundQueryBlocks(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode reserve(int required) {
    int capacity = BoundedArrayGrowth.capacity(
        blocks.length, required, BoundSqlQuery.MAXIMUM_BLOCKS, 1);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = grow(capacity);
    if (!status.isOk()) return status;
    for (int index = 0; index < required; index++) {
      status = ensure(index);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  BoundSqlQuery.Block get(int index) {
    return index >= 0 && index < blocks.length ? blocks[index] : null;
  }

  private StatusCode grow(int capacity) {
    if (capacity == blocks.length) return StatusCode.OK;
    long charged = (long) (capacity - blocks.length) * CHARGED_REFERENCE_BYTES;
    StatusCode status = budget.reserve(charged);
    if (!status.isOk()) return status;
    try {
      blocks = java.util.Arrays.copyOf(blocks, capacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode ensure(int index) {
    if (blocks[index] != null) return StatusCode.OK;
    StatusCode admitted = budget.reserve(CHARGED_BLOCK_BYTES);
    if (!admitted.isOk()) return admitted;
    try {
      blocks[index] = new BoundSqlQuery.Block(budget);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(CHARGED_BLOCK_BYTES);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
