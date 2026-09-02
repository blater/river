package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Immutable actual-count scalar program owned by one statement template. */
final class SqlTemplateExpression {
  private final byte[] operators;
  private final long[] highs;
  private final long[] values;
  private final int[] descriptors;
  private final int resultDescriptor;
  private final boolean available;

  SqlTemplateExpression(SqlScalarExpression source) {
    int count = source.nodeCount();
    operators = new byte[count];
    highs = new long[count];
    values = new long[count];
    descriptors = new int[count];
    for (int node = 0; node < count; node++) {
      operators[node] = (byte) source.operator(node);
      highs[node] = source.operandHigh(node);
      values[node] = source.operand(node);
      descriptors[node] = source.typeDescriptor(node);
    }
    resultDescriptor = source.resultTypeDescriptor();
    available = source.isAvailable();
  }

  SqlTemplateExpression(SqlMutationExpressions source, int program) {
    int count = source.nodeCount(program);
    operators = new byte[count];
    highs = new long[count];
    values = new long[count];
    descriptors = new int[count];
    for (int node = 0; node < count; node++) {
      operators[node] = (byte) source.operator(program, node);
      highs[node] = source.operandHigh(program, node);
      values[node] = source.operand(program, node);
      descriptors[node] = source.descriptor(program, node);
    }
    resultDescriptor = count == 0 ? 0 : descriptors[count - 1];
    available = count > 0;
  }

  StatusCode restore(SqlScalarExpression target) {
    target.reset();
    for (int node = 0; node < operators.length; node++) {
      if (!target.append(operators[node], highs[node], values[node], descriptors[node])) {
        target.reset();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    if (available) {
      if (resultDescriptor == 0) target.finishUnresolved();
      else target.finish(resultDescriptor);
    }
    return StatusCode.OK;
  }

  int parameterMaximum() {
    int maximum = -1;
    for (int node = 0; node < operators.length; node++) {
      if (Byte.toUnsignedInt(operators[node]) == SqlScalarExpression.PARAMETER) {
        maximum = Math.max(maximum, (int) values[node]);
      }
    }
    return maximum;
  }

  long byteCharge() {
    long bytes = SqlTemplateRetainedSize.add(
        64L,
        SqlTemplateRetainedSize.array(operators.length, Byte.BYTES),
        SqlTemplateRetainedSize.array(highs.length, Long.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(values.length, Long.BYTES));
    return SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(descriptors.length, Integer.BYTES));
  }
}
