package io.riverdb.engine.api;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;

/** Shared validation and access rules for fixed and two-word exact decimals. */
final class PublicDecimal128 {
  private PublicDecimal128() { }

  static boolean isWide(int descriptor) {
    return SqlTypeDescriptor.isWideDecimal(descriptor);
  }

  static boolean valid(long high, long low, int descriptor) {
    return isWide(descriptor)
        ? SqlValueDomain.validDecimal128(descriptor, high, low)
        : SqlValueDomain.validFixed(descriptor, low);
  }

  static long inferredHigh(long low, int descriptor) {
    return isWide(descriptor) ? low >> Long.SIZE - 1 : 0;
  }
}
