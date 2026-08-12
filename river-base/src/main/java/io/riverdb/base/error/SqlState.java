package io.riverdb.base.error;

/** Stable SQLSTATE values used by River's type and constraint semantics. */
public final class SqlState {
  public static final String SUCCESS = "00000";
  public static final String DATA_EXCEPTION = "22000";
  public static final String STRING_DATA_RIGHT_TRUNCATION = "22001";
  public static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";
  public static final String INVALID_DATETIME_FORMAT = "22007";
  public static final String DATETIME_FIELD_OVERFLOW = "22008";
  public static final String INVALID_TIME_ZONE_DISPLACEMENT = "22009";
  public static final String DIVISION_BY_ZERO = "22012";
  public static final String INVALID_CHARACTER_VALUE_FOR_CAST = "22018";
  public static final String INVALID_PARAMETER_VALUE = "22023";
  public static final String DATATYPE_MISMATCH = "42804";
  public static final String CANNOT_COERCE = "42846";

  private SqlState() {
  }
}
