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
    grouped = command.grouping().count() > 0;
    if (!valid(command)) return StatusCode.FEATURE_NOT_SUPPORTED;
    setCounts(command);
    StatusCode status = reserve(Math.max(keyCount, groupOutputs + aggregateOutputs));
    if (!status.isOk()) return status;
    status = materialization.prepare(command, table, keyCount);
    if (!status.isOk()) return status;
    initializeSources();
    firstKeyDescriptor = materialization.descriptor(0);
    status = bindGroupOutputs(command);
    if (!status.isOk()) return status;
    status = bindAggregateOutputs(command, table);
    if (!status.isOk()) return status;
    status = SqlDescriptorSetOrdering.configure(command, storage, keyCount);
    if (!status.isOk()) return status;
    return SqlDescriptorSetPlan.configure(command, plan, this, storage, materialization);
  }

  private void setCounts(SqlCommand command) {
    keyCount = grouped ? command.grouping().count() : command.columnCount();
    aggregateOutputs = grouped ? command.aggregates().outputCount() : 0;
    groupOutputs = grouped ? command.columnCount() - aggregateOutputs : keyCount;
  }

  private void initializeSources() {
    for (int key = 0; key < keyCount; key++) {
      storage.sources[key] = key;
    }
  }

  private StatusCode bindGroupOutputs(SqlCommand command) {
    for (int output = 0; output < groupOutputs; output++) {
      int column = grouped ? SqlDescriptorSetOrdering.groupKey(command, output) : output;
      if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      storage.outputs[output] = column;
      storage.descriptors[output] = materialization.descriptor(column);
    }
    return StatusCode.OK;
  }

  private StatusCode bindAggregateOutputs(
      SqlCommand command, TableDescriptor table) {
    if (!grouped) return StatusCode.OK;
    StatusCode status = aggregateShape.prepare(command, table, materialization);
    if (!status.isOk()) return status;
    for (int output = 0;
        output < command.aggregates().outputCount(); output++) {
      int invocation = command.aggregates().outputInvocation(output);
      storage.aggregates[output] = invocation;
      storage.descriptors[groupOutputs + output] =
          aggregateShape.bound().resultDescriptor(invocation);
    }
    return StatusCode.OK;
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
    return command.grouping().count() > 0
        && command.columnCount() >= command.aggregates().outputCount();
  }

}
