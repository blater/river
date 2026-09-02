package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import java.nio.ByteBuffer;

/** Retained lexicographic layout and comparator for legacy tuple sorts. */
final class SqlLegacyGroupTupleComparator {
  private final SqlLegacySortTupleLayout layout = new SqlLegacySortTupleLayout();
  private BoundSqlStatement bound;
  private int keys;
  private int projections;
  private boolean grouped;

  StatusCode configure(
      SqlCommand command,
      BoundSqlStatement statement,
      int keyCount,
      int projectionCount,
      boolean groupTuple) {
    bound = statement;
    keys = keyCount;
    projections = projectionCount;
    grouped = groupTuple;
    return layout.configure(command, keyCount, projectionCount, groupTuple);
  }

  int compare(
      long[] highs,
      long[] values,
      SqlSortNullWords nulls,
      ByteBuffer rows,
      int[] rowSlots,
      int left,
      int right) {
    for (int part = 0; part < keys; part++) {
      int key = layout.lane(part);
      boolean leftNull = nulls.nullAt(left, key);
      boolean rightNull = nulls.nullAt(right, key);
      int comparison = leftNull != rightNull ? leftNull ? -1 : 1
          : leftNull ? 0 : compareValue(
              highs, values, rows, rowSlots, left, right, key);
      if (comparison != 0) return layout.descending(part) ? -comparison : comparison;
    }
    return 0;
  }

  private int compareValue(
      long[] highs,
      long[] values, ByteBuffer rows, int[] rowSlots,
      int left, int right, int key) {
    int descriptor = grouped
        ? bound.projectionPrograms.resultDescriptor(key)
        : bound.projectedTypeDescriptors[key];
    long leftValue = values[left * projections + key];
    long rightValue = values[right * projections + key];
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return compareText(rows, rowSlots, left, leftValue, right, rightValue);
    }
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      int leftAt = left * projections + key;
      int rightAt = right * projections + key;
      int high = Long.compare(highs[leftAt], highs[rightAt]);
      return high != 0 ? high : Long.compareUnsigned(leftValue, rightValue);
    }
    return SqlNumericTypeRules.isNumeric(descriptor)
        ? SqlNumericValue.compare(leftValue, descriptor, rightValue, descriptor)
        : Long.compare(leftValue, rightValue);
  }

  private static int compareText(
      ByteBuffer rows, int[] rowSlots, int left, long leftHandle,
      int right, long rightHandle) {
    int leftSlot = rowSlots == null ? left : rowSlots[left];
    int rightSlot = rowSlots == null ? right : rowSlots[right];
    int leftOffset = leftSlot * TableSchema.MAXIMUM_ROW_BYTES
        + (int) (leftHandle >>> 32);
    int rightOffset = rightSlot * TableSchema.MAXIMUM_ROW_BYTES
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
}
