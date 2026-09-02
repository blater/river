package io.riverdb.protocol;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;

/** Wire width and validation rules for two-word DECIMAL values. */
final class ProtocolDecimal128 {
  private ProtocolDecimal128() { }

  static boolean isWide(int descriptor) {
    return SqlTypeDescriptor.isWideDecimal(descriptor);
  }

  static int bytes(int descriptor) {
    return isWide(descriptor) ? Long.BYTES * 2 : Long.BYTES;
  }

  static boolean valid(int descriptor, long high, long low) {
    return isWide(descriptor)
        && SqlValueDomain.validDecimal128(descriptor, high, low);
  }
}
