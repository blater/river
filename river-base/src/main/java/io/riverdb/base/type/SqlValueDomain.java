package io.riverdb.base.type;

/** Canonical validation for fixed-width SQL values crossing a trust boundary. */
public final class SqlValueDomain {
  private SqlValueDomain() {
  }

  public static boolean validFixed(int descriptor, long value) {
    if (!SqlTypeDescriptor.isValid(descriptor)) {
      return false;
    }
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> true;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> value == 0 || value == 1;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> ExactDecimal.fits(value, precision);
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.validDate(value);
      case SqlTypeDescriptor.TYPE_ID_TIME -> LocalTemporal.validTime(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.validTimestamp(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.validInstant(value, precision);
      default -> false;
    };
  }
}
