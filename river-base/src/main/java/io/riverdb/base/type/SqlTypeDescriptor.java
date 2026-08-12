package io.riverdb.base.type;

/**
 * Packed dependency-neutral SQL type descriptor shared by catalog, binder, results, and wire
 * boundaries. The low byte is a stable type ID; the next two bytes are bounded type parameters.
 */
public final class SqlTypeDescriptor {
  public static final int TYPE_ID_BIGINT = 1;
  public static final int TYPE_ID_VARCHAR = 2;
  public static final int TYPE_ID_BOOLEAN = 3;
  public static final int TYPE_ID_DECIMAL = 4;
  public static final int TYPE_ID_DATE = 5;
  public static final int TYPE_ID_TIME = 6;
  public static final int TYPE_ID_TIMESTAMP = 7;
  public static final int TYPE_ID_TIMESTAMP_WITH_TIME_ZONE = 8;

  public static final int BIGINT = TYPE_ID_BIGINT;
  public static final int BOOLEAN = TYPE_ID_BOOLEAN;
  public static final int DATE = TYPE_ID_DATE;

  public static final int COMPARISON_NONE = 0;
  public static final int COMPARISON_BOOLEAN = 1;
  public static final int COMPARISON_EXACT_NUMERIC = 2;
  public static final int COMPARISON_TEXT = 3;
  public static final int COMPARISON_LOCAL_TEMPORAL = 4;
  public static final int COMPARISON_INSTANT = 5;

  /** NULL is represented only by a side bitmap; no in-band value is reserved. */
  public static final int NULL_REPRESENTATION_BITMAP = 1;
  public static final int LENGTH_UNIT_UNICODE_SCALAR = 1;
  public static final int PRECISION_UNIT_DECIMAL_DIGIT = 2;
  public static final int PRECISION_UNIT_FRACTIONAL_SECOND_DIGIT = 3;

  public static final int MAXIMUM_VARCHAR_SCALARS = 255;
  public static final int MAXIMUM_DECIMAL_PRECISION = 18;
  public static final int MAXIMUM_TEMPORAL_PRECISION = 6;

  private static final int TYPE_MASK = 0xff;
  private static final int PARAMETER_MASK = 0xff;
  private static final int PARAMETER_ONE_SHIFT = 8;
  private static final int PARAMETER_TWO_SHIFT = 16;
  private static final int RESERVED_MASK = 0xff000000;

  private SqlTypeDescriptor() {
  }

  public static int varchar(int maximumScalars) {
    return maximumScalars >= 1 && maximumScalars <= MAXIMUM_VARCHAR_SCALARS
        ? pack(TYPE_ID_VARCHAR, maximumScalars, 0) : 0;
  }

  public static int decimal(int precision, int scale) {
    return precision >= 1
            && precision <= MAXIMUM_DECIMAL_PRECISION
            && scale >= 0
            && scale <= precision
        ? pack(TYPE_ID_DECIMAL, precision, scale) : 0;
  }

  public static int time(int precision) {
    return temporal(TYPE_ID_TIME, precision);
  }

  public static int timestamp(int precision) {
    return temporal(TYPE_ID_TIMESTAMP, precision);
  }

  public static int timestampWithTimeZone(int precision) {
    return temporal(TYPE_ID_TIMESTAMP_WITH_TIME_ZONE, precision);
  }

  public static boolean isValid(int descriptor) {
    if ((descriptor & RESERVED_MASK) != 0) {
      return false;
    }
    int type = typeId(descriptor);
    int first = parameterOne(descriptor);
    int second = parameterTwo(descriptor);
    return switch (type) {
      case TYPE_ID_BIGINT, TYPE_ID_BOOLEAN, TYPE_ID_DATE -> first == 0 && second == 0;
      case TYPE_ID_VARCHAR -> first >= 1
          && first <= MAXIMUM_VARCHAR_SCALARS
          && second == 0;
      case TYPE_ID_DECIMAL -> first >= 1
          && first <= MAXIMUM_DECIMAL_PRECISION
          && second <= first;
      case TYPE_ID_TIME, TYPE_ID_TIMESTAMP, TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          first <= MAXIMUM_TEMPORAL_PRECISION && second == 0;
      default -> false;
    };
  }

  public static int typeId(int descriptor) {
    return descriptor & TYPE_MASK;
  }

  public static int parameterOne(int descriptor) {
    return descriptor >>> PARAMETER_ONE_SHIFT & PARAMETER_MASK;
  }

  public static int parameterTwo(int descriptor) {
    return descriptor >>> PARAMETER_TWO_SHIFT & PARAMETER_MASK;
  }

  public static int lengthUnit(int descriptor) {
    return typeId(descriptor) == TYPE_ID_VARCHAR ? LENGTH_UNIT_UNICODE_SCALAR : 0;
  }

  public static int precisionUnit(int descriptor) {
    return switch (typeId(descriptor)) {
      case TYPE_ID_DECIMAL -> PRECISION_UNIT_DECIMAL_DIGIT;
      case TYPE_ID_TIME, TYPE_ID_TIMESTAMP, TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          PRECISION_UNIT_FRACTIONAL_SECOND_DIGIT;
      default -> 0;
    };
  }

  public static int comparisonFamily(int descriptor) {
    if (!isValid(descriptor)) {
      return COMPARISON_NONE;
    }
    return switch (typeId(descriptor)) {
      case TYPE_ID_BOOLEAN -> COMPARISON_BOOLEAN;
      case TYPE_ID_BIGINT, TYPE_ID_DECIMAL -> COMPARISON_EXACT_NUMERIC;
      case TYPE_ID_VARCHAR -> COMPARISON_TEXT;
      case TYPE_ID_DATE, TYPE_ID_TIME, TYPE_ID_TIMESTAMP -> COMPARISON_LOCAL_TEMPORAL;
      case TYPE_ID_TIMESTAMP_WITH_TIME_ZONE -> COMPARISON_INSTANT;
      default -> COMPARISON_NONE;
    };
  }

  public static boolean canCompare(int left, int right) {
    if (!isValid(left) || !isValid(right)) {
      return false;
    }
    if (left == right) {
      return true;
    }
    int family = comparisonFamily(left);
    if (family != comparisonFamily(right)) {
      return false;
    }
    return family == COMPARISON_EXACT_NUMERIC
        || family == COMPARISON_TEXT
        || (family == COMPARISON_LOCAL_TEMPORAL || family == COMPARISON_INSTANT)
            && typeId(left) == typeId(right);
  }

  public static boolean canImplicitlyCast(int source, int target) {
    if (!isValid(source) || !isValid(target)) {
      return false;
    }
    if (source == target) {
      return true;
    }
    if (typeId(source) == TYPE_ID_VARCHAR && typeId(target) == TYPE_ID_VARCHAR) {
      return parameterOne(source) <= parameterOne(target);
    }
    if ((typeId(source) == TYPE_ID_TIME
            || typeId(source) == TYPE_ID_TIMESTAMP
            || typeId(source) == TYPE_ID_TIMESTAMP_WITH_TIME_ZONE)
        && typeId(source) == typeId(target)) {
      return parameterOne(source) <= parameterOne(target);
    }
    if (typeId(source) != TYPE_ID_DECIMAL || typeId(target) != TYPE_ID_DECIMAL) {
      return false;
    }
    int sourceIntegerDigits = parameterOne(source) - parameterTwo(source);
    int targetIntegerDigits = parameterOne(target) - parameterTwo(target);
    return targetIntegerDigits >= sourceIntegerDigits
        && parameterTwo(target) >= parameterTwo(source);
  }

  public static boolean canExplicitlyCast(int source, int target) {
    if (!isValid(source) || !isValid(target)) {
      return false;
    }
    if (canImplicitlyCast(source, target)) {
      return true;
    }
    int sourceType = typeId(source);
    int targetType = typeId(target);
    if (sourceType == targetType) {
      return true;
    }
    if (sourceType == TYPE_ID_VARCHAR || targetType == TYPE_ID_VARCHAR) {
      return true;
    }
    if ((sourceType == TYPE_ID_BIGINT || sourceType == TYPE_ID_DECIMAL)
        && (targetType == TYPE_ID_BIGINT || targetType == TYPE_ID_DECIMAL)) {
      return true;
    }
    if ((sourceType == TYPE_ID_DATE && targetType == TYPE_ID_TIMESTAMP)
        || (sourceType == TYPE_ID_TIMESTAMP && targetType == TYPE_ID_DATE)) {
      return true;
    }
    return sourceType == TYPE_ID_TIMESTAMP
            && targetType == TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        || sourceType == TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
            && targetType == TYPE_ID_TIMESTAMP;
  }

  private static int temporal(int typeId, int precision) {
    return precision >= 0 && precision <= MAXIMUM_TEMPORAL_PRECISION
        ? pack(typeId, precision, 0) : 0;
  }

  private static int pack(int typeId, int first, int second) {
    return typeId | first << PARAMETER_ONE_SHIFT | second << PARAMETER_TWO_SHIFT;
  }
}
