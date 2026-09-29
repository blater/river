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
    if (left.hasUtf8(leftColumn) && right.hasUtf8(rightColumn)) {
      return SqlBlockRow.compareUtf8(left, leftColumn, right, rightColumn);
    }
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.textLength(leftColumn)
        && rightIndex < right.textLength(rightColumn)) {
      int leftScalar = scalar(left, leftColumn, leftIndex);
      int rightScalar = scalar(right, rightColumn, rightIndex);
      if (leftScalar != rightScalar) return Integer.compare(leftScalar, rightScalar);
      leftIndex += Character.charCount(leftScalar);
      rightIndex += Character.charCount(rightScalar);
    }
    return Integer.compare(
        left.textLength(leftColumn) - leftIndex,
        right.textLength(rightColumn) - rightIndex);
  }

  private static int scalar(SqlBlockRow row, int column, int index) {
    char first = row.textCharacter(column, index);
    return Character.isHighSurrogate(first)
        ? Character.toCodePoint(first, row.textCharacter(column, index + 1)) : first;
  }
}
