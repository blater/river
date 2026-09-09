package io.riverdb.server.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Distribution version supplied by an embedded application resource. */
final class RiverDaemonVersion {
  private RiverDaemonVersion() { }

  static String value() {
    try (InputStream input = RiverDaemonVersion.class.getResourceAsStream("version.properties")) {
      if (input == null) return "unpackaged";
      Properties properties = new Properties();
      properties.load(input);
      String version = properties.getProperty("river.version");
      return version == null || version.isBlank() ? "unpackaged" : version;
    } catch (IOException failure) {
      return "unpackaged";
    }
  }
}
