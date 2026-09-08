package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.client.RiverClientConnection;
import io.riverdb.client.RiverClientConfiguration;
import io.riverdb.client.RiverClientConfigurationResult;
import io.riverdb.client.RiverClientOpenResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/** Driver for generated client configuration {@code jdbc:river:client-file:PATH}. */
public final class RiverDriver implements Driver {
  public static final String CLIENT_FILE_PREFIX = "jdbc:river:client-file:";

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
    return openClientFile(url);
  }

  private static Connection openClientFile(String url) throws SQLException {
    String pathText = url.substring(CLIENT_FILE_PREFIX.length());
    if (pathText.isEmpty() || !pathText.equals(pathText.strip())) {
      throw JdbcExceptions.invalid("River client-file URL requires an absolute path");
    }
    final Path path;
    try {
      path = Path.of(pathText);
    } catch (InvalidPathException failure) {
      throw JdbcExceptions.invalid("River client-file URL has an invalid path");
    }
    if (!path.isAbsolute() || !path.equals(path.normalize())) {
      throw JdbcExceptions.invalid("River client-file URL requires an absolute normalized path");
    }

    RiverClientConfigurationResult configurationResult =
        new RiverClientConfigurationResult();
    StatusCode status = RiverClientConfiguration.load(path, configurationResult);
    JdbcExceptions.require(status, "load River client configuration");
    RiverClientOpenResult connected = new RiverClientOpenResult();
    status = configurationResult.configuration().connect(connected);
    if (status == StatusCode.INVALID_EXTERNAL_INPUT || status == StatusCode.FENCED) {
      throw JdbcExceptions.authentication(status);
    }
    JdbcExceptions.require(status, "connect using River client configuration");
    RiverClientConnection client = connected.connection();
    SessionOpenResult opened = new SessionOpenResult();
    status = client.createSession(opened);
    if (!status.isOk()) {
      client.close();
      throw JdbcExceptions.failure(status, "open session");
    }
    return new RiverJdbcConnection(client, opened.session(), url);
  }

  static Connection connectFile(Path path) throws SQLException {
    if (path == null) throw JdbcExceptions.invalid("client properties path is required");
    return openClientFile(CLIENT_FILE_PREFIX + path);
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith(CLIENT_FILE_PREFIX);
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

}
