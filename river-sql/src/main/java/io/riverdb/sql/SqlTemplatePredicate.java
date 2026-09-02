package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;

/** Immutable actual-count Boolean predicate owned by one statement template. */
final class SqlTemplatePredicate {
  private final SqlBooleanPredicateProgram value = new SqlBooleanPredicateProgram();

  SqlTemplatePredicate(SqlBooleanPredicateProgram source) {
    if (!value.copyFrom(source).isOk()) throw new OutOfMemoryError();
    trim(value);
  }

  StatusCode restore(SqlBooleanPredicateProgram target) { return target.copyFrom(value); }

  int parameterMaximum() {
    int maximum = -1;
    for (int node = 0; node < value.scalarNodeCount; node++) {
      if (Byte.toUnsignedInt(value.scalarOperators[node]) == SqlScalarExpression.PARAMETER) {
        maximum = Math.max(maximum, (int) value.scalarOperands[node]);
      }
    }
    for (int member = 0; member < value.memberCount; member++) {
      if (Byte.toUnsignedInt(value.memberKinds[member]) == SqlScalarExpression.PARAMETER) {
        maximum = Math.max(maximum, (int) value.memberValues[member]);
      }
    }
    return maximum;
  }

  long byteCharge() {
    long bytes = 192L;
    bytes = add(bytes, value.scalarOperators, value.scalarOperandHighs,
        value.scalarOperands, value.scalarDescriptors);
    bytes = add(bytes, value.programOffsets, value.programCounts,
        value.leafTests, value.comparisons);
    bytes = add(bytes, value.leafNegated, value.subqueryEdges,
        value.memberOffsets, value.memberCounts);
    bytes = add(bytes, value.memberValues, value.memberHighs,
        value.memberDescriptors, value.memberNulls);
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(value.memberKinds.length, Byte.BYTES));
    bytes = add(bytes, value.booleanOperators, value.booleanLeft,
        value.booleanRight, value.booleanDepth);
    return bytes;
  }

  private static long add(
      long bytes, byte[] first, long[] second, long[] third, int[] fourth) {
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(first.length, Byte.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(second.length, Long.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(third.length, Long.BYTES));
    return SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(fourth.length, Integer.BYTES));
  }

  private static long add(
      long bytes, int[] first, int[] second, byte[] third, Object[] fourth) {
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(first.length, Integer.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(second.length, Integer.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(third.length, Byte.BYTES));
    return SqlTemplateRetainedSize.add(bytes, SqlTemplateRetainedSize.array(
        fourth.length, SqlTemplateRetainedSize.REFERENCE_BYTES));
  }

  private static long add(
      long bytes, boolean[] first, int[] second, int[] third, int[] fourth) {
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(first.length, Byte.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(second.length, Integer.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(third.length, Integer.BYTES));
    return SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(fourth.length, Integer.BYTES));
  }

  private static long add(
      long bytes, long[] first, long[] second, int[] third, boolean[] fourth) {
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(first.length, Long.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(second.length, Long.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(third.length, Integer.BYTES));
    return SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(fourth.length, Byte.BYTES));
  }

  private static long add(
      long bytes, byte[] first, int[] second, int[] third, int[] fourth) {
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(first.length, Byte.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(second.length, Integer.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(third.length, Integer.BYTES));
    return SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(fourth.length, Integer.BYTES));
  }

  private static void trim(SqlBooleanPredicateProgram value) {
    value.scalarOperators = Arrays.copyOf(value.scalarOperators, value.scalarNodeCount);
    value.scalarOperandHighs = Arrays.copyOf(value.scalarOperandHighs, value.scalarNodeCount);
    value.scalarOperands = Arrays.copyOf(value.scalarOperands, value.scalarNodeCount);
    value.scalarDescriptors = Arrays.copyOf(value.scalarDescriptors, value.scalarNodeCount);
    value.programOffsets = Arrays.copyOf(value.programOffsets, value.leafCount * 4);
    value.programCounts = Arrays.copyOf(value.programCounts, value.leafCount * 4);
    value.leafTests = Arrays.copyOf(value.leafTests, value.leafCount);
    value.comparisons = Arrays.copyOf(value.comparisons, value.leafCount);
    value.leafNegated = Arrays.copyOf(value.leafNegated, value.leafCount);
    value.subqueryEdges = Arrays.copyOf(value.subqueryEdges, value.leafCount);
    value.memberOffsets = Arrays.copyOf(value.memberOffsets, value.leafCount);
    value.memberCounts = Arrays.copyOf(value.memberCounts, value.leafCount);
    value.memberValues = value.memberCount == 0 ? new long[0]
        : Arrays.copyOf(value.memberValues, value.memberCount);
    value.memberHighs = value.memberCount == 0 ? new long[0]
        : Arrays.copyOf(value.memberHighs, value.memberCount);
    value.memberDescriptors = value.memberCount == 0 ? new int[0]
        : Arrays.copyOf(value.memberDescriptors, value.memberCount);
    value.memberNulls = value.memberCount == 0 ? new boolean[0]
        : Arrays.copyOf(value.memberNulls, value.memberCount);
    value.memberKinds = value.memberCount == 0 ? new byte[0]
        : Arrays.copyOf(value.memberKinds, value.memberCount);
    value.booleanOperators = Arrays.copyOf(value.booleanOperators, value.booleanNodeCount);
    value.booleanLeft = Arrays.copyOf(value.booleanLeft, value.booleanNodeCount);
    value.booleanRight = Arrays.copyOf(value.booleanRight, value.booleanNodeCount);
    value.booleanDepth = Arrays.copyOf(value.booleanDepth, value.booleanNodeCount);
  }
}
