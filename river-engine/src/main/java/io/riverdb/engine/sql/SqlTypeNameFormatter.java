package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Allocation-free SQL spelling of one scalar type descriptor. */
final class SqlTypeNameFormatter {
  private final char[] text = new char[48];

  char[] text() { return text; }

  int format(int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> copy("SMALLINT");
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> copy("INTEGER");
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> copy("BIGINT");
      case SqlTypeDescriptor.TYPE_ID_REAL -> copy("REAL");
      case SqlTypeDescriptor.TYPE_ID_DOUBLE -> copy("DOUBLE PRECISION");
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> copy("BOOLEAN");
      case SqlTypeDescriptor.TYPE_ID_DATE -> copy("DATE");
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> parameterized("DECIMAL", descriptor, true);
      case SqlTypeDescriptor.TYPE_ID_VARCHAR -> parameterized("VARCHAR", descriptor, false);
      case SqlTypeDescriptor.TYPE_ID_TIME -> parameterized("TIME", descriptor, false);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> parameterized("TIMESTAMP", descriptor, false);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE -> timestampWithZone(descriptor);
      default -> -1;
    };
  }

  private int parameterized(String name, int descriptor, boolean scale) {
    int length = copy(name);
    text[length++] = '(';
    length = number(SqlTypeDescriptor.parameterOne(descriptor), length);
    if (scale) {
      text[length++] = ',';
      length = number(SqlTypeDescriptor.parameterTwo(descriptor), length);
    }
    text[length++] = ')';
    return length;
  }

  private int timestampWithZone(int descriptor) {
    return append(" WITH TIME ZONE", parameterized("TIMESTAMP", descriptor, false));
  }

  private int copy(String value) { return append(value, 0); }

  private int append(String value, int offset) {
    for (int index = 0; index < value.length(); index++) text[offset++] = value.charAt(index);
    return offset;
  }

  private int number(int value, int offset) {
    if (value >= 100) {
      text[offset++] = (char) ('0' + value / 100);
      value %= 100;
      text[offset++] = (char) ('0' + value / 10);
    } else if (value >= 10) {
      text[offset++] = (char) ('0' + value / 10);
    }
    text[offset++] = (char) ('0' + value % 10);
    return offset;
  }
}
