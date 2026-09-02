package io.riverdb.engine.sql;

import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Allocation-free typed comparison between two retained block-row lanes. */
final class SqlBlockRowValueComparator {
  private final ExactDecimal128.Scratch decimal = new ExactDecimal128.Scratch();

  int compare(
      SqlBlockRow left, int leftColumn, int leftDescriptor,
      SqlBlockRow right, int rightColumn, int rightDescriptor) {
    if (SqlNumericTypeRules.isNumeric(leftDescriptor)
        && SqlNumericTypeRules.isNumeric(rightDescriptor)) {
      return SqlNumericComparison.compare(
          left.highValue(leftColumn), left.value(leftColumn), leftDescriptor,
          right.highValue(rightColumn), right.value(rightColumn), rightDescriptor,
          decimal);
    }
    if (SqlTypeDescriptor.typeId(leftDescriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && SqlTypeDescriptor.typeId(rightDescriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return compareText(left, leftColumn, right, rightColumn);
    }
    return Long.compare(left.value(leftColumn), right.value(rightColumn));
  }

  private static int compareText(
      SqlBlockRow left, int leftColumn, SqlBlockRow right, int rightColumn) {
    int common = Math.min(left.textLength(leftColumn), right.textLength(rightColumn));
    for (int index = 0; index < common; index++) {
      int compared = Character.compare(
          left.textCharacter(leftColumn, index), right.textCharacter(rightColumn, index));
      if (compared != 0) return compared;
    }
    return Integer.compare(left.textLength(leftColumn), right.textLength(rightColumn));
  }
}
