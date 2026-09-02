package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Reusable resolved columns and result shape for descriptor set execution. */
final class SqlDescriptorSetShape {
  private final SqlDescriptorSetStorage storage;
  private final SqlDescriptorAggregateShape aggregateShape =
      new SqlDescriptorAggregateShape();
  private final SqlDescriptorSetMaterialization materialization;
  private int keyCount;
  private int firstKeyDescriptor;
  private int groupOutputs;
  private int aggregateOutputs;
  private boolean grouped;

  SqlDescriptorSetShape() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlDescriptorSetShape(SqlRetainedArrayAllocator arrayAllocator) {
    this(
        arrayAllocator,
        new SqlTemporalContext(),
        new SqlSessionShapeBudget(null));
  }

  SqlDescriptorSetShape(
      SqlRetainedArrayAllocator arrayAllocator,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    storage = new SqlDescriptorSetStorage(arrayAllocator, shapeBudget);
    materialization = new SqlDescriptorSetMaterialization(
        arrayAllocator, temporal, shapeBudget);
  }

  StatusCode prepare(
      SqlCommand command, TableDescriptor table, SqlPhysicalPlan plan) {
    grouped = command.groupExpressionCount() > 0;
    if (!valid(command)) return StatusCode.FEATURE_NOT_SUPPORTED;
    keyCount = grouped ? command.groupExpressionCount() : command.columnCount();
    aggregateOutputs = grouped ? command.aggregateOutputCount() : 0;
    groupOutputs = grouped ? command.columnCount() - aggregateOutputs : keyCount;
    int resultCount = groupOutputs + aggregateOutputs;
    StatusCode status = reserve(Math.max(keyCount, resultCount));
    if (status.isOk()) status = materialization.prepare(command, table, keyCount);
    for (int key = 0; status.isOk() && key < keyCount; key++) {
      storage.sources[key] = key;
    }
    if (!status.isOk()) return status;
    firstKeyDescriptor = materialization.descriptor(0);
    for (int output = 0; output < groupOutputs; output++) {
      int column = grouped ? SqlDescriptorSetOrdering.groupKey(command, output) : output;
      if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      storage.outputs[output] = column;
      storage.descriptors[output] = materialization.descriptor(column);
    }
    if (grouped) status = aggregateShape.prepare(command, table, materialization);
    for (int output = 0; status.isOk() && grouped
        && output < command.aggregateOutputCount(); output++) {
      int invocation = command.aggregateOutputInvocation(output);
      storage.aggregates[output] = invocation;
      storage.descriptors[groupOutputs + output] =
          aggregateShape.bound().resultDescriptor(invocation);
    }
    if (status.isOk()) status = SqlDescriptorSetOrdering.configure(command, storage, keyCount);
    return status.isOk()
        ? SqlDescriptorSetPlan.configure(command, plan, this, storage, materialization)
        : status;
  }

  int sourceColumn(int key) { return storage.sources[key]; }
  int outputColumn(int output) { return storage.outputs[output]; }
  int firstSourceColumn() { return storage.sources[0]; }
  int firstKeyDescriptor() { return firstKeyDescriptor; }
  int[] sortColumns() { return storage.sort; }
  int[] outputColumns() { return storage.outputs; }
  boolean[] descending() { return storage.descending; }
  int keyCount() { return keyCount; }
  int resultCount() { return groupOutputs + aggregateOutputs; }
  int groupOutputCount() { return groupOutputs; }
  int aggregateOutputCount() { return aggregateOutputs; }
  int aggregateInvocation(int output) { return storage.aggregates[output]; }
  int[] descriptors() { return storage.descriptors; }
  boolean grouped() { return grouped; }
  SqlBoundAggregateSet aggregates() { return aggregateShape.bound(); }
  SqlDescriptorSetMaterialization materialization() { return materialization; }

  StatusCode reserve(int required) {
    return storage.reserve(required);
  }

  private static boolean valid(SqlCommand command) {
    if (command.type() == SqlCommandType.DISTINCT_SCAN) {
      return command.columnCount() > 0 && !command.isSelectAll();
    }
    return command.groupExpressionCount() > 0
        && command.columnCount() >= command.aggregateOutputCount();
  }

}
