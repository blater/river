package io.riverdb.jdbc;

@SuppressWarnings("deprecation")
abstract class AbstractConnection implements java.sql.Connection {
  @Override
  public void abort(java.util.concurrent.Executor argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void clearWarnings() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void close() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void commit() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Array createArrayOf(java.lang.String argument0, java.lang.Object[] argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Blob createBlob() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Clob createClob() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.NClob createNClob() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.SQLXML createSQLXML() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Statement createStatement() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Statement createStatement(int argument0, int argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Statement createStatement(int argument0, int argument1, int argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Struct createStruct(java.lang.String argument0, java.lang.Object[] argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean getAutoCommit() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getCatalog() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getClientInfo(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.util.Properties getClientInfo() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getHoldability() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getNetworkTimeout() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getSchema() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getTransactionIsolation() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.util.Map<java.lang.String, java.lang.Class<?>> getTypeMap() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isClosed() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isReadOnly() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isValid(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isWrapperFor(java.lang.Class<?> argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String nativeSQL(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.CallableStatement prepareCall(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.CallableStatement prepareCall(java.lang.String argument0, int argument1, int argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.CallableStatement prepareCall(java.lang.String argument0, int argument1, int argument2, int argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String argument0, int argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String argument0, int argument1, int argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String argument0, int argument1, int argument2, int argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String argument0, int[] argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.PreparedStatement prepareStatement(java.lang.String argument0, java.lang.String[] argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void releaseSavepoint(java.sql.Savepoint argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void rollback() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void rollback(java.sql.Savepoint argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setAutoCommit(boolean argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setCatalog(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setClientInfo(java.lang.String argument0, java.lang.String argument1) throws java.sql.SQLClientInfoException {
    throw new java.sql.SQLClientInfoException();
  }

  @Override
  public void setClientInfo(java.util.Properties argument0) throws java.sql.SQLClientInfoException {
    throw new java.sql.SQLClientInfoException();
  }

  @Override
  public void setHoldability(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setNetworkTimeout(java.util.concurrent.Executor argument0, int argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setReadOnly(boolean argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Savepoint setSavepoint() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Savepoint setSavepoint(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setSchema(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setTransactionIsolation(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setTypeMap(java.util.Map<java.lang.String, java.lang.Class<?>> argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public <T> T unwrap(java.lang.Class<T> argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

}
