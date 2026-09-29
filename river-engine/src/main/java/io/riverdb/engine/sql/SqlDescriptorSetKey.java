package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Reusable retained tuple and result publication for descriptor set execution. */
final class SqlDescriptorSetKey {
  private final SqlBlockRow row;
  private ByteBuffer aggregateText;
  private byte[] aggregateBytes;

  SqlDescriptorSetKey(SqlSessionShapeBudget budget) {
    row = new SqlBlockRow(budget);
  }

  StatusCode capture(SqlBlockRow source) { return row.copyFrom(source); }

  SqlBlockRow row() { return row; }

  long firstValue(SqlDescriptorSetShape shape) {
    return row.value(shape.firstSourceColumn());
  }

  boolean firstNull(SqlDescriptorSetShape shape) {
    return row.nullValue(shape.firstSourceColumn());
  }

  StatusCode prepare(SqlDescriptorSetMaterialization materialization) {
    return row.reset(materialization.laneCount());
  }

  boolean same(SqlBlockRow candidate, SqlDescriptorSetShape shape) {
    for (int part = 0; part < shape.keyCount(); part++) {
      int column = shape.sourceColumn(part);
      if (row.nullValue(column) != candidate.nullValue(column)) return false;
      if (row.nullValue(column)) continue;
      boolean textKey = SqlTypeDescriptor.typeId(
          shape.materialization().descriptor(column)) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
      if (!textKey) {
        if (row.value(column) != candidate.value(column)
            || SqlTypeDescriptor.isWideDecimal(
                shape.materialization().descriptor(column))
                && row.highValue(column) != candidate.highValue(column)) return false;
      } else if (row.textLength(column) != candidate.textLength(column)
          || !sameText(candidate, column)) return false;
    }
    return true;
  }

  StatusCode publish(
      SqlScanRowResult result,
      SqlDescriptorSetShape shape,
      SqlAggregateAccumulatorSet accumulators) {
    StatusCode status = result.beginProjected(
        0, shape.descriptors(), shape.resultCount());
    for (int part = 0; status.isOk() && part < shape.groupOutputCount(); part++) {
      int column = shape.outputColumn(part);
      if (row.nullValue(column)) result.setProjectedNull(part);
      else if (SqlTypeDescriptor.typeId(shape.descriptors()[part])
          == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        status = row.hasUtf8(column)
            ? result.setUtf8At(
                part, row.utf8Slice(column), 0, row.utf8Length(column))
            : result.setTextAt(part, row.text(column), row.textLength(column));
      } else if (SqlTypeDescriptor.isWideDecimal(shape.descriptors()[part])) {
        result.setProjectedDecimal128(part, row.highValue(column), row.value(column));
      } else result.setProjectedValue(part, row.value(column));
    }
    for (int output = 0; status.isOk() && output < shape.aggregateOutputCount(); output++) {
      int invocation = shape.aggregateInvocation(output);
      int projection = shape.groupOutputCount() + output;
      if (accumulators.nullValue(invocation)) result.setProjectedNull(projection);
      else if (SqlTypeDescriptor.typeId(shape.aggregates().resultDescriptor(invocation))
          == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        status = publishText(result, projection, accumulators, invocation);
      } else if (SqlTypeDescriptor.isWideDecimal(
          shape.aggregates().resultDescriptor(invocation))) {
        result.setProjectedDecimal128(
            projection,
            accumulators.highValue(invocation),
            accumulators.value(invocation));
      } else result.setProjectedValue(projection, accumulators.value(invocation));
    }
    return status;
  }

  StatusCode prepareAggregateText(SqlAggregateAccumulatorSet accumulators) {
    if (accumulators.text() == aggregateBytes) return StatusCode.OK;
    aggregateBytes = accumulators.text();
    try {
      aggregateText = aggregateBytes == null ? null : ByteBuffer.wrap(aggregateBytes);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      aggregateBytes = null;
      aggregateText = null;
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private boolean sameText(SqlBlockRow candidate, int column) {
    if (row.hasUtf8(column) && candidate.hasUtf8(column)) {
      return SqlBlockRow.compareUtf8(row, column, candidate, column) == 0;
    }
    for (int index = 0; index < row.textLength(column); index++) {
      if (row.textCharacter(column, index) != candidate.textCharacter(column, index)) return false;
    }
    return true;
  }

  private StatusCode publishText(
      SqlScanRowResult result,
      int projection,
      SqlAggregateAccumulatorSet accumulators,
      int invocation) {
    if (aggregateText == null) return StatusCode.CORRUPTION;
    return result.setUtf8At(
        projection, aggregateText,
        accumulators.textOffset(invocation), accumulators.textLength(invocation));
  }
}
