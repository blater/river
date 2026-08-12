package io.riverdb.jdbc;

@SuppressWarnings("deprecation")
abstract class AbstractPreparedStatement extends RiverJdbcStatement
    implements java.sql.PreparedStatement {
  AbstractPreparedStatement(
      RiverJdbcConnection owner,
      io.riverdb.engine.api.RiverSession session) {
    super(owner, session);
  }

  @Override
  public void addBatch() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void clearParameters() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean execute() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet executeQuery() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int executeUpdate() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSetMetaData getMetaData() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ParameterMetaData getParameterMetaData() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setArray(int argument0, java.sql.Array argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setAsciiStream(int argument0, java.io.InputStream argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setAsciiStream(int argument0, java.io.InputStream argument1, int argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setAsciiStream(int argument0, java.io.InputStream argument1, long argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setBigDecimal(int argument0, java.math.BigDecimal argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setBinaryStream(int argument0, java.io.InputStream argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setBinaryStream(int argument0, java.io.InputStream argument1, int argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setBinaryStream(int argument0, java.io.InputStream argument1, long argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setBlob(int argument0, java.io.InputStream argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setBlob(int argument0, java.io.InputStream argument1, long argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setBlob(int argument0, java.sql.Blob argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setBoolean(int argument0, boolean argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setByte(int argument0, byte argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setBytes(int argument0, byte[] argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setCharacterStream(int argument0, java.io.Reader argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setCharacterStream(int argument0, java.io.Reader argument1, int argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setCharacterStream(int argument0, java.io.Reader argument1, long argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setClob(int argument0, java.io.Reader argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setClob(int argument0, java.io.Reader argument1, long argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setClob(int argument0, java.sql.Clob argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setDate(int argument0, java.sql.Date argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setDate(int argument0, java.sql.Date argument1, java.util.Calendar argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setDouble(int argument0, double argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setFloat(int argument0, float argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setInt(int argument0, int argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setLong(int argument0, long argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setNCharacterStream(int argument0, java.io.Reader argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setNCharacterStream(int argument0, java.io.Reader argument1, long argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setNClob(int argument0, java.io.Reader argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setNClob(int argument0, java.io.Reader argument1, long argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setNClob(int argument0, java.sql.NClob argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setNString(int argument0, java.lang.String argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setNull(int argument0, int argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setNull(int argument0, int argument1, java.lang.String argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setObject(int argument0, java.lang.Object argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setObject(int argument0, java.lang.Object argument1, int argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setObject(int argument0, java.lang.Object argument1, int argument2, int argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setRef(int argument0, java.sql.Ref argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setRowId(int argument0, java.sql.RowId argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setSQLXML(int argument0, java.sql.SQLXML argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setShort(int argument0, short argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setString(int argument0, java.lang.String argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setTime(int argument0, java.sql.Time argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setTime(int argument0, java.sql.Time argument1, java.util.Calendar argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setTimestamp(int argument0, java.sql.Timestamp argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setTimestamp(int argument0, java.sql.Timestamp argument1, java.util.Calendar argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setURL(int argument0, java.net.URL argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setUnicodeStream(int argument0, java.io.InputStream argument1, int argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

}
