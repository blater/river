package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.ColumnConstraintDescriptorSet;

/** Primitive scratch for constraint fields streamed alongside catalog columns. */
final class CatalogColumnConstraintAssembly {
  private int[] columnTypes;
  private byte[] defaultKinds;
  private long[] defaultHighs;
  private long[] defaultValues;
  private byte[] checkComparisons;
  private int[] checkTypes;
  private long[] checkHighs;
  private long[] checkValues;
  private final ColumnConstraintDescriptorSet.Result result =
      new ColumnConstraintDescriptorSet.Result();

  StatusCode begin(int count) {
    reset();
    try {
      columnTypes = new int[count];
      defaultKinds = new byte[count];
      defaultHighs = new long[count];
      defaultValues = new long[count];
      checkComparisons = new byte[count];
      checkTypes = new int[count];
      checkHighs = new long[count];
      checkValues = new long[count];
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      reset();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void put(
      int index, int columnType, int defaultKind, long defaultHigh, long defaultValue,
      int comparison, int checkType, long checkHigh, long checkValue) {
    columnTypes[index] = columnType;
    defaultKinds[index] = (byte) defaultKind;
    defaultHighs[index] = defaultHigh;
    defaultValues[index] = defaultValue;
    checkComparisons[index] = (byte) comparison;
    checkTypes[index] = checkType;
    checkHighs[index] = checkHigh;
    checkValues[index] = checkValue;
  }

  StatusCode freeze(int count) {
    StatusCode status = ColumnConstraintDescriptorSet.create(
        columnTypes, defaultKinds, defaultHighs, defaultValues,
        checkComparisons, checkTypes, checkHighs, checkValues, count, result);
    return status == StatusCode.INVALID_EXTERNAL_INPUT ? StatusCode.CORRUPTION : status;
  }

  ColumnConstraintDescriptorSet value() { return result.value(); }

  void reset() {
    columnTypes = null;
    defaultKinds = null;
    defaultHighs = null;
    defaultValues = null;
    checkComparisons = null;
    checkTypes = null;
    checkHighs = null;
    checkValues = null;
    result.reset();
  }
}
