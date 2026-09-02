package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Pre-bound compact expression tuple materialized before descriptor set sorting. */
final class SqlDescriptorSetMaterialization {
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  private final BoundSqlStatement bound;
  private final SqlBlockSchema input = new SqlBlockSchema();
  private final SqlBlockSchema output = new SqlBlockSchema();
  private final SqlDescriptorSetBinding binding = new SqlDescriptorSetBinding();
  private final SqlDescriptorColumnName descriptorName = new SqlDescriptorColumnName();
  private final SqlRowProjectionEvaluator evaluator;
  private int[] aggregateLanes = new int[0];
  private int keyCount;
  private int laneCount;

  SqlDescriptorSetMaterialization(
      SqlRetainedArrayAllocator retainedAllocator,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    allocator = retainedAllocator;
    budget = shapeBudget;
    bound = new BoundSqlStatement(shapeBudget);
    evaluator = new SqlRowProjectionEvaluator(
        new SqlExpressionEvaluator(), temporal, shapeBudget);
  }

  StatusCode prepare(SqlCommand command, TableDescriptor table, int keys) {
    reset();
    keyCount = keys;
    int operands = aggregateOperandCount(command);
    if (keys < 0 || keys > SqlShapeLimits.MAX_TUPLE_PARTS - operands
        || keys == 0 && command.aggregateInvocationCount() == 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = reserveAggregateLanes(command.aggregateInvocationCount());
    if (status.isOk()) status = prepareInput(table);
    if (status.isOk()) status = bound.command.copyBlockFrom(command);
    if (status.isOk()) status = binding.bind(
        command, input, bound, keys, aggregateLanes);
    laneCount = binding.laneCount();
    if (status.isOk()) status = binding.describe(command, input, bound, output, keys);
    if (status.isOk()) status = evaluator.prepare(bound);
    if (!status.isOk()) reset();
    return status;
  }

  StatusCode project(SqlBlockRow source, SqlBlockRow result) {
    return evaluator.projectBlock(source, result);
  }

  SqlBlockSchema schema() { return output; }
  int laneCount() { return laneCount; }
  int keyCount() { return keyCount; }
  int aggregateLane(int invocation) { return aggregateLanes[invocation]; }
  int descriptor(int lane) { return output.descriptor(lane); }
  boolean nullable(int lane) { return output.nullable(lane); }
  int rawColumn(int lane) { return bound.projectionPrograms.rawColumn(lane); }

  void reset() {
    evaluator.reset();
    bound.reset();
    input.reset();
    output.reset();
    keyCount = 0;
    laneCount = 0;
  }

  private StatusCode prepareInput(TableDescriptor table) {
    input.set(table.columnCount());
    for (int column = 0; column < table.columnCount(); column++) {
      input.setColumn(
          column,
          descriptorName.load(table, column),
          table.typeDescriptorAt(column),
          table.isNullable(column));
    }
    return input.status();
  }

  private StatusCode reserveAggregateLanes(int required) {
    if (required <= aggregateLanes.length) return StatusCode.OK;
    long charged = (long) (required - aggregateLanes.length) * Integer.BYTES;
    StatusCode admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      aggregateLanes = allocator.integers(required);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static int aggregateOperandCount(SqlCommand command) {
    int count = 0;
    for (int invocation = 0; invocation < command.aggregateInvocationCount(); invocation++) {
      if (command.aggregateOperandProjection(invocation) >= 0) count++;
    }
    return count;
  }
}
