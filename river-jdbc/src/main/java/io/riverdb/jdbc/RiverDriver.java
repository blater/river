package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.client.RiverClientConnection;
import io.riverdb.client.RiverClientOpenResult;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/** Driver for the pre-V1 loopback URL {@code jdbc:river://localhost:PORT}. */
public final class RiverDriver implements Driver {
  public static final String URL_PREFIX = "jdbc:river://localhost:";

  static {
    try {
      DriverManager.registerDriver(new RiverDriver());
    } catch (SQLException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  @Override
  public Connection connect(String url, Properties properties) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    if (properties != null && !properties.isEmpty()) {
      throw JdbcExceptions.unsupported();
    }
    int port = parsePort(url);
    if (port <= 0) {
      throw JdbcExceptions.invalid("River JDBC URL must end with a valid port");
    }
    RiverClientOpenResult connected = new RiverClientOpenResult();
    JdbcExceptions.require(
        RiverClientConnection.connectLoopback(port, connected),
        "connect");
    RiverClientConnection client = connected.connection();
    SessionOpenResult opened = new SessionOpenResult();
    StatusCode status = client.createSession(opened);
    if (!status.isOk()) {
      client.close();
      throw JdbcExceptions.failure(status, "open session");
    }
    RiverSession session = opened.session();
    return new RiverJdbcConnection(client, session);
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith(URL_PREFIX);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion() {
    return 0;
  }

  @Override
  public int getMinorVersion() {
    return 1;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw JdbcExceptions.unsupported();
  }

  private static int parsePort(String url) {
    if (url.length() <= URL_PREFIX.length()) {
      return -1;
    }
    int port = 0;
    for (int index = URL_PREFIX.length(); index < url.length(); index++) {
      char digit = url.charAt(index);
      if (digit < '0' || digit > '9') {
        return -1;
      }
      port = port * 10 + digit - '0';
      if (port > 65_535) {
        return -1;
      }
    }
    return port;
  }
}
