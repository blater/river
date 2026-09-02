package io.riverdb.jdbc;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.RiverSession;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Time;
import java.sql.Timestamp;

/** Implements supported typed setters over a bounded primitive binding owner. */
abstract class RiverJdbcTypedPreparedStatement extends AbstractPreparedStatement {
  private final RiverJdbcParameterBindings bindings;
  private final RiverJdbcParameterObjects objects;
  private final int parameterCount;

  RiverJdbcTypedPreparedStatement(
      RiverJdbcConnection owner,
      RiverSession session,
      int count) {
    super(owner, session);
    parameterCount = count;
    bindings = new RiverJdbcParameterBindings(count);
    objects = new RiverJdbcParameterObjects(bindings);
  }

  @Override
  public final void setBoolean(int index, boolean value) throws SQLException {
    setFixed(index, SqlTypeDescriptor.BOOLEAN, value ? 1 : 0);
  }

  @Override
  public final void setBigDecimal(int index, BigDecimal value) throws SQLException {
    requireParameter(index);
    bindings.setDecimal(index - 1, value);
  }

  @Override
  public final void setByte(int index, byte value) throws SQLException {
    setFixed(index, SqlTypeDescriptor.SMALLINT, value);
  }

  @Override
  public final void setShort(int index, short value) throws SQLException {
    setFixed(index, SqlTypeDescriptor.SMALLINT, value);
  }

  @Override
  public final void setInt(int index, int value) throws SQLException {
    setFixed(index, SqlTypeDescriptor.INTEGER, value);
  }

  @Override
  public final void setLong(int index, long value) throws SQLException {
    setFixed(index, SqlTypeDescriptor.BIGINT, value);
  }

  @Override
  public final void setFloat(int index, float value) throws SQLException {
    requireParameter(index);
    bindings.setReal(index - 1, value);
  }

  @Override
  public final void setDouble(int index, double value) throws SQLException {
    requireParameter(index);
    bindings.setDouble(index - 1, value);
  }

  @Override
  public final void setString(int index, String value) throws SQLException {
    requireParameter(index);
    bindings.setText(index - 1, value);
  }

  @Override
  public final void setNull(int index, int sqlType) throws SQLException {
    requireParameter(index);
    bindings.setNull(
        index - 1, RiverJdbcParameterTypes.nullDescriptor(sqlType));
  }

  @Override
  public final void setNull(int index, int sqlType, String typeName)
      throws SQLException {
    if (typeName != null && !typeName.isEmpty()) throw JdbcExceptions.unsupported();
    setNull(index, sqlType);
  }

  @Override
  public final void setDate(int index, Date value) throws SQLException {
    requireParameter(index);
    objects.date(index - 1, value);
  }

  @Override
  public final void setTime(int index, Time value) throws SQLException {
    requireParameter(index);
    objects.time(index - 1, value);
  }

  @Override
  public final void setTimestamp(int index, Timestamp value) throws SQLException {
    requireParameter(index);
    objects.timestamp(index - 1, value);
  }

  @Override
  public final void setObject(int index, Object value) throws SQLException {
    requireParameter(index);
    objects.set(index - 1, value);
  }

  @Override
  public final void setObject(int index, Object value, int targetType)
      throws SQLException {
    requireParameter(index);
    objects.set(index - 1, value, targetType);
  }

  @Override
  public final void setObject(
      int index,
      Object value,
      int targetType,
      int scale) throws SQLException {
    if (scale != 0) throw JdbcExceptions.unsupported();
    setObject(index, value, targetType);
  }

  @Override
  public final void setObject(int index, Object value, SQLType targetType)
      throws SQLException {
    setObject(index, value, vendorType(targetType));
  }

  @Override
  public final void setObject(
      int index,
      Object value,
      SQLType targetType,
      int scale) throws SQLException {
    setObject(index, value, vendorType(targetType), scale);
  }

  @Override
  public final void clearParameters() throws SQLException {
    requirePreparedOpen();
    bindings.clear();
  }

  @Override
  public void close() throws SQLException {
    bindings.clear();
    super.close();
  }

  final ParameterSet boundParameters() throws SQLException {
    requirePreparedOpen();
    return bindings.parameters();
  }

  final ParameterSet snapshotParameters() throws SQLException {
    requirePreparedOpen();
    return bindings.snapshot();
  }

  final void requirePreparedOpen() throws SQLException {
    if (isClosed()) throw JdbcExceptions.closed("prepared statement");
  }

  private void setFixed(int index, int descriptor, long value)
      throws SQLException {
    requireParameter(index);
    bindings.setFixed(index - 1, descriptor, value);
  }

  private void requireParameter(int index) throws SQLException {
    requirePreparedOpen();
    if (index <= 0 || index > parameterCount) {
      throw JdbcExceptions.invalid("parameter index is out of range");
    }
  }

  private static int vendorType(SQLType type) throws SQLException {
    if (type == null || type.getVendorTypeNumber() == null) {
      throw JdbcExceptions.invalid("SQL parameter type must not be null");
    }
    return type.getVendorTypeNumber().intValue();
  }
}
