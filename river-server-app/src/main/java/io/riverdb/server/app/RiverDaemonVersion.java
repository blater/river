package io.riverdb.server.app;

/** Distribution version supplied by the application JAR manifest. */
final class RiverDaemonVersion {
  private RiverDaemonVersion() { }

  static String value() {
    String version = RiverDaemonVersion.class.getPackage().getImplementationVersion();
    return version == null ? "unpackaged" : version;
  }
}
