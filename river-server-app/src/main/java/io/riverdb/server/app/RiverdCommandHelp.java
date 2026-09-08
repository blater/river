package io.riverdb.server.app;

import io.riverdb.protocol.ProtocolFrameCodec;

/** Pure rendering of the first installed riverd command help and version text. */
public final class RiverdCommandHelp {
  private RiverdCommandHelp() { }

  public static String brief() {
    return "Usage: riverd <command> [options]\n"
        + "Commands: start (run server), stop (unavailable), ps (unavailable),\n"
        + "          audit archive (unavailable), credentials renew (unavailable), version\n"
        + "Start example: riverd start --port=9191\n"
        + "Default data directory: .river/default under the user's home; default port: 9191\n"
        + "Use `riverd --help` for full help.\n";
  }

  public static String brief(String topic) {
    if ("start".equals(topic)) {
      return "Usage: riverd start [options]\n"
          + "Starts the authenticated foreground server; defaults to port 9191 and 127.0.0.1.\n"
          + "Use `riverd start --help` for full start options.\n";
    }
    if ("stop".equals(topic)) return "Usage: riverd stop [options]\nStop is not yet available.\n";
    if ("ps".equals(topic)) return "Usage: riverd ps\nListing instances is not yet available.\n";
    if ("audit".equals(topic) || "audit archive".equals(topic)) {
      return "Usage: riverd audit archive [-D PATH|--datadir=PATH]\n"
          + "Audit archive is not yet available.\n";
    }
    if ("credentials".equals(topic) || "credentials renew".equals(topic)) {
      return "Usage: riverd credentials renew [-D PATH|--datadir=PATH]\n"
          + "Credential renewal is not yet available.\n";
    }
    if ("version".equals(topic)) {
      return "Usage: riverd version\nPrints the installed riverd contract and protocol version.\n";
    }
    return brief();
  }

  public static String full(String topic) {
    if (topic == null || "global".equals(topic)) {
      return "riverd manages one authenticated local River instance.\n"
          + "\nUsage:\n"
          + "  riverd start [-D PATH|--datadir=PATH] [--port=PORT] [--ip=ADDRESS]\n"
          + "               [--maximum-connections=N] [--ready-file=PATH]\n"
          + "  riverd stop [-D PATH|--datadir=PATH] [--timeout=DURATION]  (not yet available)\n"
          + "  riverd ps  (not yet available)\n"
          + "  riverd audit archive [-D PATH|--datadir=PATH]  (not yet available)\n"
          + "  riverd credentials renew [-D PATH|--datadir=PATH]  (not yet available)\n"
          + "  riverd version\n\n"
          + "Start runs in the foreground, creates or reopens persistent identity, and uses TLS plus\n"
          + "the generated instance token. TLS verifies the server; the generated token authenticates\n"
          + "the client. It publishes security/client.properties for the River JDBC driver; users do\n"
          + "not configure a trust store or construct an authentication handshake.\n\n"
          + "Use `riverd start --help` for start options.\n";
    }
    if ("start".equals(topic)) {
      return "Usage: riverd start [-D PATH|--datadir=PATH] [--port=PORT] [--ip=ADDRESS]\n"
          + "                     [--maximum-connections=N] [--ready-file=PATH]\n\n"
          + "Defaults: datadir=.river/default under the user's home, port=9191, ip=127.0.0.1,\n"
          + "maximum-connections=16. Port accepts 0..65535; zero selects an available port.\n"
          + "IP accepts 127.0.0.1 or ::1. Relative paths resolve from the working directory.\n"
          + "maximum-connections is the positive connection limit selected for configured resources.\n"
          + "The server uses TLS and an instance token, then publishes security/client.properties.\n"
          + "--ready-file writes a readiness record for automation and never overwrites an existing target.\n"
          + "The foreground process closes gracefully on Ctrl-C and other platform shutdown signals.\n";
    }
    if ("stop".equals(topic)) return "Usage: riverd stop [-D PATH|--datadir=PATH] [--timeout=DURATION]\n"
        + "Stop is not yet available in this milestone.\n";
    if ("ps".equals(topic)) return "Usage: riverd ps\nListing instances is not yet available in this milestone.\n";
    if ("audit".equals(topic) || "audit archive".equals(topic)) return "Usage: riverd audit archive [-D PATH|--datadir=PATH]\n"
        + "Audit archive is not yet available in this milestone.\n";
    if ("credentials".equals(topic) || "credentials renew".equals(topic)) {
      return "Usage: riverd credentials renew [-D PATH|--datadir=PATH]\n"
          + "Credential renewal is not yet available in this milestone.\n";
    }
    if ("version".equals(topic)) return "Usage: riverd version\n";
    return "Unknown help topic.\n";
  }

  public static String version(String distributionVersion) {
    return "riverd_version=" + distributionVersion + "\n"
        + "riverd_contract=riverd-v1\nriverd_protocol=river-v"
        + ProtocolFrameCodec.VERSION + "\nriverd_status=OK\n";
  }
}
