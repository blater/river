package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.engine.api.ParameterSet;
import java.math.BigDecimal;
import java.sql.SQLException;

/** Statement-owned typed values copied into one synchronous parameter carrier. */
final class RiverJdbcParameterBindings {
  private static final byte UNSET = 0;
  private static final byte NULL = 1;
  private static final byte FIXED = 2;
  private static final byte TEXT = 3;

  private final int[] descriptors;
  private final long[] values;
  private final String[] texts;
  private final byte[] kinds;
  private final ParameterSet current;

  RiverJdbcParameterBindings(int count) {
    descriptors = new int[count];
    values = new long[count];
    texts = new String[count];
    kinds = new byte[count];
    current = new ParameterSet(count, ParameterSet.MAXIMUM_TEXT_BYTES);
  }

  void setNull(int index, int descriptor) throws SQLException {
    if (descriptor != 0 && !SqlTypeDescriptor.isValid(descriptor)) {
      throw JdbcExceptions.invalid("invalid SQL NULL type");
    }
    publish(index, descriptor, 0, null, NULL);
  }

  void setFixed(int index, int descriptor, long value) throws SQLException {
    if (!SqlValueDomain.validFixed(descriptor, value)) {
      throw JdbcExceptions.invalid("parameter is outside its declared domain");
    }
    publish(index, descriptor, value, null, FIXED);
  }

  void setText(int index, String value) throws SQLException {
    if (value == null) {
      setNull(index, SqlTypeDescriptor.varchar(Utf8Text.MAXIMUM_SCALARS));
      return;
    }
    int scalars = Utf8Text.scalarCount(value);
    if (scalars < 0) {
      throw JdbcExceptions.invalid("VARCHAR parameter is malformed");
    }
    if (scalars > Utf8Text.MAXIMUM_SCALARS) {
      throw JdbcExceptions.failure(
          StatusCode.STRING_DATA_RIGHT_TRUNCATION, "set VARCHAR parameter");
    }
    publish(
        index,
        SqlTypeDescriptor.varchar(Math.max(1, scalars)),
        0,
        value,
        TEXT);
  }

  void setDecimal(int index, BigDecimal value) throws SQLException {
    if (value == null) {
      setNull(index, SqlTypeDescriptor.decimal(
          SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 0));
      return;
    }
    BigDecimal normalized;
    try {
      normalized = value.scale() < 0 ? value.setScale(0) : value;
    } catch (ArithmeticException failure) {
      throw JdbcExceptions.failure(
          StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, "set DECIMAL parameter");
    }
    int scale = normalized.scale();
    int precision = Math.max(normalized.precision(), scale);
    if (precision < 1
        || precision > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION) {
      throw JdbcExceptions.failure(
          StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, "set DECIMAL parameter");
    }
    long unscaled;
    try {
      unscaled = normalized.unscaledValue().longValueExact();
    } catch (ArithmeticException failure) {
      throw JdbcExceptions.failure(
          StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, "set DECIMAL parameter");
    }
    setFixed(index, SqlTypeDescriptor.decimal(precision, scale), unscaled);
  }

  ParameterSet parameters() throws SQLException {
    fill(current);
    return current;
  }

  ParameterSet snapshot() throws SQLException {
    requireComplete();
    int textBytes = 0;
    for (int index = 0; index < kinds.length; index++) {
      if (kinds[index] == TEXT) {
        int bytes = Utf8Text.encodedLength(texts[index], Utf8Text.MAXIMUM_SCALARS);
        if (bytes < 0 || textBytes > ParameterSet.MAXIMUM_TEXT_BYTES - bytes) {
          throw JdbcExceptions.failure(
              StatusCode.RESOURCE_EXHAUSTED, "snapshot parameters");
        }
        textBytes += bytes;
      }
    }
    ParameterSet snapshot = new ParameterSet(kinds.length, textBytes);
    fill(snapshot);
    return snapshot;
  }

  void clear() {
    for (int index = 0; index < kinds.length; index++) {
      kinds[index] = UNSET;
      descriptors[index] = 0;
      values[index] = 0;
      texts[index] = null;
    }
    current.reset();
  }

  private void fill(ParameterSet target) throws SQLException {
    requireComplete();
    target.reset();
    for (int index = 0; index < kinds.length; index++) {
      StatusCode status = switch (kinds[index]) {
        case NULL -> target.appendNull(descriptors[index]);
        case FIXED -> target.appendFixed(descriptors[index], values[index]);
        case TEXT -> target.appendText(descriptors[index], texts[index]);
        default -> StatusCode.PARAMETER_COUNT_MISMATCH;
      };
      if (!status.isOk()) {
        throw JdbcExceptions.failure(status, "bind parameter " + (index + 1));
      }
    }
  }

  private void requireComplete() throws SQLException {
    for (int index = 0; index < kinds.length; index++) {
      if (kinds[index] == UNSET) {
        throw JdbcExceptions.failure(
            StatusCode.PARAMETER_COUNT_MISMATCH,
            "bind parameter " + (index + 1));
      }
    }
  }

  private void publish(
      int index, int descriptor, long value, String text, byte kind) {
    descriptors[index] = descriptor;
    values[index] = value;
    texts[index] = text;
    kinds[index] = kind;
  }
}
