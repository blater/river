package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.math.BigDecimal;
import java.sql.SQLException;

/** Converts and publishes one validated JDBC DECIMAL parameter. */
final class RiverJdbcDecimalBinding {
  private RiverJdbcDecimalBinding() { }

  static void publish(
      RiverJdbcParameterStore store,
      int index,
      int descriptor,
      BigDecimal value) throws SQLException {
    long low = RiverJdbcDecimal128.low(value);
    long high = RiverJdbcDecimal128.high(value);
    if (!SqlValueDomain.validDecimal128(descriptor, high, low)) {
      throw JdbcExceptions.failure(
          StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, "set DECIMAL parameter");
    }
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      store.setDecimal128(index, descriptor, high, low);
    } else {
      store.setFixed(index, descriptor, low);
    }
  }
}
