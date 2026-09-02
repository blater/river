package io.riverdb.jdbc;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

/** Maps supported JDBC objects into one statement's primitive bindings. */
final class RiverJdbcParameterObjects {
  private final RiverJdbcParameterObjectInference inference;
  private final RiverJdbcParameterObjectConversion conversion;

  RiverJdbcParameterObjects(RiverJdbcParameterBindings parameterBindings) {
    RiverJdbcTemporalObjectConversion temporal =
        new RiverJdbcTemporalObjectConversion(parameterBindings);
    inference = new RiverJdbcParameterObjectInference(parameterBindings, temporal);
    conversion = new RiverJdbcParameterObjectConversion(parameterBindings, temporal);
  }

  void date(int index, Date value) throws SQLException {
    conversion.date(index, value);
  }

  void time(int index, Time value) throws SQLException {
    conversion.time(index, value);
  }

  void timestamp(int index, Timestamp value) throws SQLException {
    conversion.timestamp(index, value);
  }

  void set(int index, Object value) throws SQLException {
    inference.set(index, value);
  }

  void set(int index, Object value, int targetType) throws SQLException {
    conversion.set(index, value, targetType);
  }
}
