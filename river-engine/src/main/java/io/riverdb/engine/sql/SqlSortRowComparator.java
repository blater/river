package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableSchema;
import java.nio.ByteBuffer;

/** Allocation-free comparison of two retained sort rows. */
final class SqlSortRowComparator {
  int compare(
      int left,
      int right,
      int groupKeyCount,
      SqlLegacyGroupTupleComparator groupComparator,
      SqlSortArrays arrays,
      SqlSortNullWords nulls,
      ByteBuffer rows,
      boolean textKey,
      int keyDescriptor,
      boolean descending) {
    int comparison;
    if (groupKeyCount > 1) {
      comparison = groupComparator.compare(
          arrays.highs(), arrays.values(), nulls, rows, arrays.rowSlots(), left, right);
    } else if (arrays.keyNulls()[left] != arrays.keyNulls()[right]) {
      comparison = arrays.keyNulls()[left] ? -1 : 1;
    } else if (textKey) {
      comparison = compareText(left, right, arrays, rows);
    } else if (SqlTypeDescriptor.isWideDecimal(keyDescriptor)) {
      comparison = compare128(
          arrays.keyHighs()[left], arrays.keys()[left],
          arrays.keyHighs()[right], arrays.keys()[right]);
    } else {
      comparison = SqlNumericTypeRules.isNumeric(keyDescriptor)
          ? SqlNumericValue.compare(
              arrays.keys()[left], keyDescriptor, arrays.keys()[right], keyDescriptor)
          : Long.compare(arrays.keys()[left], arrays.keys()[right]);
    }
    if (comparison != 0) return descending ? -comparison : comparison;
    return Long.compare(arrays.ordinals()[left], arrays.ordinals()[right]);
  }

  private static int compareText(
      int left, int right, SqlSortArrays arrays, ByteBuffer rows) {
    long leftHandle = arrays.keys()[left];
    long rightHandle = arrays.keys()[right];
    int leftOffset = arrays.rowSlots()[left] * TableSchema.MAXIMUM_ROW_BYTES
        + (int) (leftHandle >>> 32);
    int rightOffset = arrays.rowSlots()[right] * TableSchema.MAXIMUM_ROW_BYTES
        + (int) (rightHandle >>> 32);
    int leftLength = (int) leftHandle;
    int rightLength = (int) rightHandle;
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(rows.get(leftOffset + index)),
          Byte.toUnsignedInt(rows.get(rightOffset + index)));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(leftLength, rightLength);
  }

  private static int compare128(
      long leftHigh, long leftLow, long rightHigh, long rightLow) {
    int high = Long.compare(leftHigh, rightHigh);
    return high != 0 ? high : Long.compareUnsigned(leftLow, rightLow);
  }
}
