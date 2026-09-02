package io.riverdb.engine.api;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;

/** Validates compact and two-word result values against admitted descriptors. */
final class PublicResultValueValidation {
  private PublicResultValueValidation() { }

  static boolean compact(
      long[] values,
      int[] descriptors,
      int columns,
      long[] nullWords,
      long nullMask) {
    for (int index = 0; index < columns; index++) {
      boolean isNull = nullWords == null
          ? (nullMask & 1L << index) != 0
          : (nullWords[index >>> 6] & 1L << (index & 63)) != 0;
      int type = SqlTypeDescriptor.typeId(descriptors[index]);
      if (!isNull && type != SqlTypeDescriptor.TYPE_ID_VARCHAR
          && !SqlValueDomain.validFixed(descriptors[index], values[index])) return false;
    }
    return true;
  }

  static boolean decimal128(
      long[] highValues,
      long[] values,
      int[] descriptors,
      int columns,
      long[] nullWords) {
    for (int index = 0; index < columns; index++) {
      boolean isNull = (nullWords[index >>> 6] & 1L << (index & 63)) != 0;
      int type = SqlTypeDescriptor.typeId(descriptors[index]);
      if (!isNull && type != SqlTypeDescriptor.TYPE_ID_VARCHAR
          && !PublicDecimal128.valid(
              highValues[index], values[index], descriptors[index])) return false;
    }
    return true;
  }
}
