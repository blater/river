package io.riverdb.jdbc;

@SuppressWarnings("deprecation")
abstract class AbstractStatement implements java.sql.Statement {
  @Override
  public void addBatch(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void cancel() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void clearBatch() throws java.sql.SQLException {
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
  public void closeOnCompletion() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean execute(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean execute(java.lang.String argument0, int argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean execute(java.lang.String argument0, int[] argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean execute(java.lang.String argument0, java.lang.String[] argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int[] executeBatch() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet executeQuery(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int executeUpdate(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int executeUpdate(java.lang.String argument0, int argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int executeUpdate(java.lang.String argument0, int[] argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int executeUpdate(java.lang.String argument0, java.lang.String[] argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Connection getConnection() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getFetchDirection() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getFetchSize() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getGeneratedKeys() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxFieldSize() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxRows() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean getMoreResults() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean getMoreResults(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getQueryTimeout() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getResultSet() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getResultSetConcurrency() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getResultSetHoldability() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getResultSetType() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getUpdateCount() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isCloseOnCompletion() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isClosed() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isPoolable() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isWrapperFor(java.lang.Class<?> argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setCursorName(java.lang.String argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setEscapeProcessing(boolean argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setFetchDirection(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setFetchSize(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setMaxFieldSize(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setMaxRows(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setPoolable(boolean argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public void setQueryTimeout(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public <T> T unwrap(java.lang.Class<T> argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

}
