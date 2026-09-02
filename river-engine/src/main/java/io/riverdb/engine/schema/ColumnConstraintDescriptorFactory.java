package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;

/** Validates, exactly sizes, and atomically publishes column constraint metadata. */
final class ColumnConstraintDescriptorFactory {
  private ColumnConstraintDescriptorFactory() { }

  static StatusCode create(
      int[] columnTypes, byte[] kinds, long[] defaultHighs, long[] defaults,
      byte[] comparisons, int[] checkTypes, long[] checkHighs, long[] checks,
      int count, ColumnConstraintDescriptorSet.Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!ColumnConstraintDescriptorValidation.validArrays(
        columnTypes, kinds, defaultHighs, defaults, comparisons,
        checkTypes, checkHighs, checks, count)) return StatusCode.INVALID_EXTERNAL_INPUT;
    boolean any = false;
    for (int index = 0; index < count; index++) {
      if (!ColumnConstraintDescriptorValidation.validDefault(
          columnTypes[index], kinds[index], defaultHighs[index], defaults[index])
          || !ColumnConstraintDescriptorValidation.validCheck(
              columnTypes[index], comparisons[index], checkTypes[index],
              checkHighs[index], checks[index])) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      any |= kinds[index] != 0 || comparisons[index] != 0;
    }
    if (!any) return StatusCode.OK;
    long charge = charge(count);
    if (!SchemaByteCharge.fits(charge)) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      result.set(new ColumnConstraintDescriptorSet(
          Arrays.copyOf(kinds, count), Arrays.copyOf(defaultHighs, count),
          Arrays.copyOf(defaults, count), Arrays.copyOf(comparisons, count),
          Arrays.copyOf(checkTypes, count), Arrays.copyOf(checkHighs, count),
          Arrays.copyOf(checks, count), charge));
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static long charge(int count) {
    return SchemaByteCharge.object(8, 7)
        + SchemaByteCharge.array(1, count) * 2
        + SchemaByteCharge.array(Integer.BYTES, count)
        + SchemaByteCharge.array(Long.BYTES, count) * 4;
  }
}
