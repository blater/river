package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;

/** Optional immutable fixed-literal DEFAULT and direct-column CHECK metadata. */
public final class ColumnConstraintDescriptorSet {
  public static final int CHECK_NONE = 0;
  public static final int CHECK_EQUAL = 1;
  public static final int CHECK_NOT_EQUAL = 2;
  public static final int CHECK_LESS_THAN = 3;
  public static final int CHECK_LESS_OR_EQUAL = 4;
  public static final int CHECK_GREATER_THAN = 5;
  public static final int CHECK_GREATER_OR_EQUAL = 6;

  private final byte[] defaultKinds;
  private final long[] defaultHighs;
  private final long[] defaultValues;
  private final byte[] checkComparisons;
  private final int[] checkTypes;
  private final long[] checkHighs;
  private final long[] checkValues;
  private final long byteCharge;

  ColumnConstraintDescriptorSet(
      byte[] kinds, long[] defaultHigh, long[] defaults, byte[] comparisons,
      int[] types, long[] checkHigh, long[] checks, long charge) {
    defaultKinds = kinds;
    defaultHighs = defaultHigh;
    defaultValues = defaults;
    checkComparisons = comparisons;
    checkTypes = types;
    checkHighs = checkHigh;
    checkValues = checks;
    byteCharge = charge;
  }

  public static final class Result {
    private ColumnConstraintDescriptorSet value;
    public void reset() { value = null; }
    public ColumnConstraintDescriptorSet value() { return value; }
    void set(ColumnConstraintDescriptorSet published) { value = published; }
  }

  public static StatusCode create(
      int[] columnTypes, byte[] kinds, long[] defaultHighs, long[] defaults,
      byte[] comparisons, int[] checkTypes, long[] checkHighs, long[] checks,
      int count, Result result) {
    return ColumnConstraintDescriptorFactory.create(
        columnTypes, kinds, defaultHighs, defaults, comparisons,
        checkTypes, checkHighs, checks, count, result);
  }

  public int defaultKindAt(int index) { return Byte.toUnsignedInt(defaultKinds[index]); }
  public long defaultHighAt(int index) { return defaultHighs[index]; }
  public long defaultValueAt(int index) { return defaultValues[index]; }
  public int checkComparisonAt(int index) { return Byte.toUnsignedInt(checkComparisons[index]); }
  public int checkTypeAt(int index) { return checkTypes[index]; }
  public long checkHighAt(int index) { return checkHighs[index]; }
  public long checkValueAt(int index) { return checkValues[index]; }
  public long byteCharge() { return byteCharge; }

}
