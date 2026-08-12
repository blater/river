package io.riverdb.jdbc;

import io.riverdb.client.RiverClientConnection;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.sql.DataSource;

/** Configurable loopback data source with optional TLS-bound token authentication. */
public final class RiverDataSource implements DataSource, AutoCloseable {
  private static final int LOGIN_TIMEOUT_SECONDS = 5;

  private SSLContext sslContext;
  private byte[] token;
  private PrintWriter logWriter;
  private int port;
  private boolean closed;

  public synchronized void setPort(int listenerPort) throws SQLException {
    requireOpen();
    if (listenerPort <= 0 || listenerPort > 65_535) {
      throw JdbcExceptions.invalid("port must be between 1 and 65535");
    }
    port = listenerPort;
  }

  public synchronized int getPort() throws SQLException {
    requireOpen();
    return port;
  }

  public synchronized void setAuthentication(
      SSLContext context,
      byte[] sourceToken,
      int tokenBytes) throws SQLException {
    requireOpen();
    if (context == null
        || sourceToken == null
        || tokenBytes < RiverClientConnection.MINIMUM_TOKEN_BYTES
        || tokenBytes > RiverClientConnection.MAXIMUM_TOKEN_BYTES
        || tokenBytes > sourceToken.length) {
      throw JdbcExceptions.invalid("TLS context and bounded token are required");
    }
    clearToken();
    sslContext = context;
    token = Arrays.copyOf(sourceToken, tokenBytes);
  }

  public synchronized void clearAuthentication() throws SQLException {
    requireOpen();
    clearToken();
    sslContext = null;
  }

  @Override
  public Connection getConnection() throws SQLException {
    SSLContext context;
    byte[] connectionToken;
    int listenerPort;
    synchronized (this) {
      requireOpen();
      if (port == 0) {
        throw JdbcExceptions.invalid("River data source port is not configured");
      }
      context = sslContext;
      connectionToken = token == null ? null : Arrays.copyOf(token, token.length);
      listenerPort = port;
    }
    try {
      return RiverDriver.openLoopback(
          listenerPort,
          context,
          connectionToken,
          connectionToken == null ? 0 : connectionToken.length);
    } finally {
      if (connectionToken != null) {
        Arrays.fill(connectionToken, (byte) 0);
      }
    }
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
    clearToken();
    sslContext = null;
    logWriter = null;
    closed = true;
  }

  private void requireOpen() throws SQLException {
    if (closed) {
      throw JdbcExceptions.closed("data source");
    }
  }

  private void clearToken() {
    if (token != null) {
      Arrays.fill(token, (byte) 0);
      token = null;
    }
  }
}
