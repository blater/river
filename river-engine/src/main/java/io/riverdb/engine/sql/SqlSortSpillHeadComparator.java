package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableSchema;
import java.nio.ByteBuffer;

/** Stable comparison over retained canonical heads of active spill runs. */
final class SqlSortSpillHeadComparator {
  private SqlLegacyGroupTupleComparator group;
  private boolean descending;
  private boolean textKey;
  private int descriptor;
  private int groupKeys;

  void configure(
      SqlLegacyGroupTupleComparator tupleComparator, int tupleKeys,
      boolean textualKey, int keyDescriptor, boolean descendingOrder) {
    group = tupleComparator;
    groupKeys = tupleKeys;
    textKey = textualKey;
    descriptor = keyDescriptor;
    descending = descendingOrder;
  }

  int compare(
      int left, int right, long[] highs, long[] keys, boolean[] nulls,
      long[] ordinals, long[] valuesHigh, long[] values,
      SqlSortNullWords valueNulls, ByteBuffer rows) {
    int compared;
    if (groupKeys > 1) {
      compared = group.compare(valuesHigh, values, valueNulls, rows, null, left, right);
    } else if (nulls[left] != nulls[right]) {
      compared = nulls[left] ? -1 : 1;
    } else if (textKey) {
      compared = compareText(keys[left], keys[right], left, right, rows);
    } else if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      compared = compare128(highs[left], keys[left], highs[right], keys[right]);
    } else {
      compared = SqlNumericTypeRules.isNumeric(descriptor)
          ? SqlNumericValue.compare(keys[left], descriptor, keys[right], descriptor)
          : Long.compare(keys[left], keys[right]);
    }
    if (compared != 0) return descending ? -compared : compared;
    return Long.compare(ordinals[left], ordinals[right]);
  }

  private static int compareText(
      long leftHandle, long rightHandle, int left, int right, ByteBuffer rows) {
    int leftOffset = left * TableSchema.MAXIMUM_ROW_BYTES + (int) (leftHandle >>> 32);
    int rightOffset = right * TableSchema.MAXIMUM_ROW_BYTES + (int) (rightHandle >>> 32);
    int leftLength = (int) leftHandle;
    int rightLength = (int) rightHandle;
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(
          Byte.toUnsignedInt(rows.get(leftOffset + index)),
          Byte.toUnsignedInt(rows.get(rightOffset + index)));
      if (compared != 0) return compared;
    }
    return Integer.compare(leftLength, rightLength);
  }

  private static int compare128(long leftHigh, long leftLow, long rightHigh, long rightLow) {
    int high = Long.compare(leftHigh, rightHigh);
    return high != 0 ? high : Long.compareUnsigned(leftLow, rightLow);
  }
}
