package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.engine.api.ParameterSet;
import java.math.BigDecimal;
import java.sql.SQLException;

/** Statement-owned typed values copied into one synchronous parameter carrier. */
final class RiverJdbcParameterBindings {
  private final RiverJdbcParameterStore store;

  RiverJdbcParameterBindings(int count) {
    store = new RiverJdbcParameterStore(count);
  }

  void setNull(int index, int descriptor) throws SQLException {
    if (descriptor != 0 && !SqlTypeDescriptor.isValid(descriptor)) {
      throw JdbcExceptions.invalid("invalid SQL NULL type");
    }
    store.setNull(index, descriptor);
  }

  void setFixed(int index, int descriptor, long value) throws SQLException {
    if (!SqlValueDomain.validFixed(descriptor, value)) {
      throw JdbcExceptions.invalid("parameter is outside its declared domain");
    }
    store.setFixed(index, descriptor, value);
  }

  void setReal(int index, float value) throws SQLException {
    long bits = SqlApproximateNumeric.realBits(value);
    if (!SqlValueDomain.validFixed(SqlTypeDescriptor.REAL, bits)) {
      throw JdbcExceptions.failure(
          StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, "set REAL parameter");
    }
    store.setFixed(index, SqlTypeDescriptor.REAL, bits);
  }

  void setDouble(int index, double value) throws SQLException {
    long bits = SqlApproximateNumeric.doubleBits(value);
    if (!SqlValueDomain.validFixed(SqlTypeDescriptor.DOUBLE, bits)) {
      throw JdbcExceptions.failure(
          StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, "set DOUBLE parameter");
    }
    store.setFixed(index, SqlTypeDescriptor.DOUBLE, bits);
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
    store.setText(index, SqlTypeDescriptor.varchar(Math.max(1, scalars)), value);
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
    int descriptor = SqlTypeDescriptor.decimal(precision, scale);
    RiverJdbcDecimalBinding.publish(store, index, descriptor, normalized);
  }

  ParameterSet parameters() throws SQLException {
    return store.parameters();
  }

  ParameterSet snapshot() throws SQLException {
    return store.snapshot();
  }

  void clear() {
    store.clear();
  }
}
