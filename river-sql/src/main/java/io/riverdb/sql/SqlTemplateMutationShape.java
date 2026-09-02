package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;

/** Immutable INSERT/UPDATE value programs owned by one statement template. */
final class SqlTemplateMutationShape {
  private final SqlTemplateExpression[] expressions;
  private final long[] insertHighs;
  private final long[] insertValues;
  private final int[] insertDescriptors;
  private final boolean[] insertNulls;
  private final boolean[] insertDefaults;
  private final long[] updateHighs;
  private final long[] updateValues;
  private final int[] updateDescriptors;
  private final int[] updateOperators;
  private final boolean[] updateNulls;
  private final boolean[] updateDefaults;
  private final int insertRows;
  private final int insertColumns;

  SqlTemplateMutationShape(SqlCommand source) {
    expressions = new SqlTemplateExpression[source.mutationExpressions.programCount()];
    for (int program = 0; program < expressions.length; program++) {
      expressions[program] = new SqlTemplateExpression(source.mutationExpressions, program);
    }
    insertRows = source.insertRowCount;
    insertColumns = source.insertColumnCount;
    int cells = insertRows * insertColumns;
    insertHighs = new long[cells];
    insertValues = new long[cells];
    insertDescriptors = new int[cells];
    insertNulls = new boolean[cells];
    insertDefaults = new boolean[cells];
    for (int row = 0; row < insertRows; row++) captureInsertRow(source, row);
    int updates = source.updateColumnCount;
    updateHighs = Arrays.copyOf(source.updateHighs, updates);
    updateValues = Arrays.copyOf(source.updateValues, updates);
    updateDescriptors = Arrays.copyOf(source.updateTypeDescriptors, updates);
    updateOperators = Arrays.copyOf(source.updateOperators, updates);
    updateNulls = Arrays.copyOf(source.nullUpdates, updates);
    updateDefaults = Arrays.copyOf(source.defaultUpdates, updates);
  }

  StatusCode restore(SqlCommand target) {
    for (SqlTemplateExpression expression : expressions) {
      StatusCode status = expression.restore(target.scalarExpression);
      if (!status.isOk() || target.appendMutationExpression(target.scalarExpression) < 0) {
        return status.isOk() ? StatusCode.RESOURCE_EXHAUSTED : status;
      }
    }
    for (int row = 0; row < insertRows; row++) {
      int offset = row * insertColumns;
      if (!target.inserts.append(
          insertHighs, insertValues, insertNulls, insertDefaults,
          insertDescriptors, offset, insertColumns)) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      target.insertRowCount++;
      target.insertColumnCount = insertColumns;
    }
    for (int update = 0; update < updateValues.length; update++) {
      target.appendUpdate(
          updateHighs[update], updateValues[update], updateNulls[update],
          updateDefaults[update], updateDescriptors[update], updateOperators[update]);
    }
    return StatusCode.OK;
  }

  int parameterMaximum() {
    int maximum = -1;
    for (SqlTemplateExpression expression : expressions) {
      maximum = Math.max(maximum, expression.parameterMaximum());
    }
    for (int cell = 0; cell < insertValues.length; cell++) {
      if (insertDescriptors[cell] == SqlCommand.mutationParameterDescriptor()) {
        maximum = Math.max(maximum, (int) insertValues[cell]);
      }
    }
    for (int update = 0; update < updateValues.length; update++) {
      if (updateOperators[update] == SqlCommand.UPDATE_PARAMETER) {
        maximum = Math.max(maximum, (int) updateValues[update]);
      }
    }
    return maximum;
  }

  long byteCharge() {
    long bytes = SqlTemplateRetainedSize.add(
        160L, SqlTemplateRetainedSize.array(
            expressions.length, SqlTemplateRetainedSize.REFERENCE_BYTES));
    bytes = addValueArrays(
        bytes, insertHighs, insertValues, insertDescriptors, insertNulls, insertDefaults);
    bytes = addValueArrays(
        bytes, updateHighs, updateValues, updateDescriptors, updateNulls, updateDefaults);
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(updateOperators.length, Integer.BYTES));
    for (SqlTemplateExpression expression : expressions) {
      bytes = SqlTemplateRetainedSize.add(bytes, expression.byteCharge());
    }
    return bytes;
  }

  private static long addValueArrays(
      long bytes, long[] highs, long[] values, int[] descriptors,
      boolean[] nulls, boolean[] defaults) {
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(highs.length, Long.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(values.length, Long.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(descriptors.length, Integer.BYTES));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(nulls.length, Byte.BYTES));
    return SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(defaults.length, Byte.BYTES));
  }

  private void captureInsertRow(SqlCommand source, int row) {
    for (int column = 0; column < insertColumns; column++) {
      int slot = row * insertColumns + column;
      insertHighs[slot] = source.inserts.high(row, column);
      insertValues[slot] = source.inserts.value(row, column);
      insertDescriptors[slot] = source.inserts.typeDescriptor(row, column);
      insertNulls[slot] = source.inserts.isNull(row, column);
      insertDefaults[slot] = source.inserts.isDefault(row, column);
    }
  }
}
