package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Decimal digit shape of exact numeric inputs used by aggregate result typing. */
final class SqlExactNumericDigits {
  private SqlExactNumericDigits() { }

  static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }

  static int integer(int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> 5;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> 10;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          SqlTypeDescriptor.parameterOne(descriptor) - SqlTypeDescriptor.parameterTwo(descriptor);
      default -> 19;
    };
  }
}
