package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.sql.SqlCommand;
import java.nio.ByteBuffer;

/** Reusable scalar aggregate output mapping and UTF-8 publication. */
final class SqlDescriptorAggregateResult {
  private final char[] text = new char[Utf8Text.MAXIMUM_BUFFER_CHARACTERS];
  private int[] descriptors = new int[0];
  private int[] invocations = new int[0];
  private ByteBuffer aggregateText;
  private byte[] aggregateBytes;
  private int count;

  StatusCode prepare(
      SqlCommand command, SqlBoundAggregateSet aggregates, SqlPhysicalPlan plan) {
    count = command.aggregateOutputCount();
    if (count <= 0 || count != command.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = reserve(count);
    if (status.isOk() && plan != null) status = plan.beginResult(count);
    for (int output = 0; status.isOk() && output < count; output++) {
      int invocation = command.aggregateOutputInvocation(output);
      if (invocation < 0 || invocation >= aggregates.count()) {
        return StatusCode.CORRUPTION;
      }
      invocations[output] = invocation;
      descriptors[output] = aggregates.resultDescriptor(invocation);
      if (plan != null) {
        int kind = aggregates.kind(invocation);
        plan.setResultColumn(
            output,
            -1,
            descriptors[output],
            SqlResultMetadata.invocationColumnName(command, output, kind));
        plan.setResultNullable(
            output, kind != SqlAggregateKind.COUNT && kind != SqlAggregateKind.COUNT_VALUE
                && kind != SqlAggregateKind.COUNT_DISTINCT);
      }
    }
    return status;
  }

  StatusCode publish(
      SqlExecutionResult result,
      SqlAggregateAccumulatorSet values,
      long commitSequence) {
    StatusCode status = result.beginProjection(0, descriptors, count, commitSequence);
    for (int output = 0; status.isOk() && output < count; output++) {
      status = publish(result, values, output);
    }
    return status;
  }

  StatusCode publish(
      SqlScanRowResult result, SqlAggregateAccumulatorSet values) {
    StatusCode status = result.beginProjected(0, descriptors, count);
    for (int output = 0; status.isOk() && output < count; output++) {
      status = publish(result, values, output);
    }
    return status;
  }

  StatusCode prepareText(SqlAggregateAccumulatorSet values) {
    if (aggregateBytes == values.text()) return StatusCode.OK;
    aggregateBytes = values.text();
    try {
      aggregateText = aggregateBytes == null ? null : ByteBuffer.wrap(aggregateBytes);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      aggregateBytes = null;
      aggregateText = null;
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode publish(
      SqlExecutionResult result, SqlAggregateAccumulatorSet values, int output) {
    int invocation = invocations[output];
    if (values.nullValue(invocation)) result.setProjectedNull(output);
    else if (text(output)) {
      int length = decode(values, invocation);
      return length < 0 ? StatusCode.CORRUPTION : result.setTextAt(output, text, length);
    } else if (SqlTypeDescriptor.isWideDecimal(descriptors[output])) {
      result.setProjectedDecimal128(
          output, values.highValue(invocation), values.value(invocation));
    } else result.setProjectedValue(output, values.value(invocation));
    return StatusCode.OK;
  }

  private StatusCode publish(
      SqlScanRowResult result, SqlAggregateAccumulatorSet values, int output) {
    int invocation = invocations[output];
    if (values.nullValue(invocation)) result.setProjectedNull(output);
    else if (text(output)) {
      int length = decode(values, invocation);
      return length < 0 ? StatusCode.CORRUPTION : result.setTextAt(output, text, length);
    } else if (SqlTypeDescriptor.isWideDecimal(descriptors[output])) {
      result.setProjectedDecimal128(
          output, values.highValue(invocation), values.value(invocation));
    } else result.setProjectedValue(output, values.value(invocation));
    return StatusCode.OK;
  }

  private int decode(SqlAggregateAccumulatorSet values, int invocation) {
    if (aggregateText == null) return -1;
    return Utf8Text.decode(
        aggregateText,
        values.textOffset(invocation),
        values.textLength(invocation),
        text,
        0);
  }

  private boolean text(int output) {
    return SqlTypeDescriptor.typeId(descriptors[output])
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private StatusCode reserve(int required) {
    if (required <= descriptors.length) return StatusCode.OK;
    try {
      int[] nextDescriptors = new int[required];
      int[] nextInvocations = new int[required];
      descriptors = nextDescriptors;
      invocations = nextInvocations;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
