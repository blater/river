package io.riverdb.jdbc;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** JDBC data source backed by a generated River client-properties file. */
public final class RiverDataSource implements DataSource, AutoCloseable {
  private static final int LOGIN_TIMEOUT_SECONDS = 5;

  private Path clientFile;
  private PrintWriter logWriter;
  private boolean closed;

  public synchronized void setClientFile(Path file) throws SQLException {
    requireOpen();
    if (file == null || !file.isAbsolute() || !file.equals(file.normalize())) {
      throw JdbcExceptions.invalid("client properties path must be absolute and normalized");
    }
    clientFile = file;
  }

  public synchronized Path getClientFile() throws SQLException {
    requireOpen();
    return clientFile;
  }

  @Override
  public Connection getConnection() throws SQLException {
    Path file;
    synchronized (this) {
      requireOpen();
      file = clientFile;
    }
    if (file == null) throw JdbcExceptions.invalid("client properties path is not configured");
    return RiverDriver.connectFile(file);
  }

  @Override
  public Connection getConnection(String username, String password)
      throws SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public synchronized PrintWriter getLogWriter() throws SQLException {
    requireOpen();
    return logWriter;
  }

  @Override
  public synchronized void setLogWriter(PrintWriter writer) throws SQLException {
    requireOpen();
    logWriter = writer;
  }

  @Override
  public synchronized void setLoginTimeout(int seconds) throws SQLException {
    requireOpen();
    if (seconds != LOGIN_TIMEOUT_SECONDS) {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public synchronized int getLoginTimeout() throws SQLException {
    requireOpen();
    return LOGIN_TIMEOUT_SECONDS;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    if (type != null && type.isInstance(this)) {
      return type.cast(this);
    }
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isWrapperFor(Class<?> type) {
    return type != null && type.isInstance(this);
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    clientFile = null;
    logWriter = null;
    closed = true;
  }

  private void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("data source");
    }
  }

}
