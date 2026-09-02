package io.riverdb.engine.schema;

import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;

/** Canonical validation for optional fixed-literal column constraint fields. */
final class ColumnConstraintDescriptorValidation {
  private ColumnConstraintDescriptorValidation() { }

  static boolean validArrays(
      int[] columnTypes, byte[] kinds, long[] defaultHighs, long[] defaults,
      byte[] comparisons, int[] checkTypes, long[] checkHighs, long[] checks, int count) {
    return count > 0 && columnTypes != null && columnTypes.length >= count
        && kinds != null && kinds.length >= count && defaultHighs != null
        && defaultHighs.length >= count && defaults != null && defaults.length >= count
        && comparisons != null && comparisons.length >= count && checkTypes != null
        && checkTypes.length >= count && checkHighs != null && checkHighs.length >= count
        && checks != null && checks.length >= count;
  }

  static boolean validDefault(int type, byte kindByte, long high, long value) {
    int kind = Byte.toUnsignedInt(kindByte);
    if (kind == SqlDefaultKind.NONE) return high == 0 && value == 0;
    if (kind != SqlDefaultKind.LITERAL
        || SqlTypeDescriptor.typeId(type) == SqlTypeDescriptor.TYPE_ID_VARCHAR) return false;
    return validValue(type, high, value);
  }

  static boolean validCheck(
      int ownerType, byte comparisonByte, int type, long high, long value) {
    int comparison = Byte.toUnsignedInt(comparisonByte);
    if (comparison == ColumnConstraintDescriptorSet.CHECK_NONE) {
      return type == 0 && high == 0 && value == 0;
    }
    return comparison <= ColumnConstraintDescriptorSet.CHECK_GREATER_OR_EQUAL
        && SqlTypeDescriptor.isValid(type)
        && SqlTypeDescriptor.typeId(type) != SqlTypeDescriptor.TYPE_ID_VARCHAR
        && SqlTypeDescriptor.canCompare(ownerType, type)
        && validValue(type, high, value);
  }

  private static boolean validValue(int type, long high, long value) {
    return SqlTypeDescriptor.isWideDecimal(type)
        ? SqlValueDomain.validDecimal128(type, high, value)
        : high == value >> 63 && SqlValueDomain.validFixed(type, value);
  }
}
