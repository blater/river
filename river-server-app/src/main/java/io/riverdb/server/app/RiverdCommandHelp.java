package io.riverdb.server.app;

import io.riverdb.cli.RiverSqlMain;
import io.riverdb.protocol.ProtocolFrameCodec;

/** Pure rendering of the unified River command help and version text. */
public final class RiverdCommandHelp {
  private RiverdCommandHelp() { }

  public static String render(String topic) {
    RiverCommandCatalog.Spec spec = RiverCommandCatalog.find(topic);
    if (spec == null) return "Unknown help topic: " + topic + "\nUse `river help` to list topics.\n";
    if (spec == RiverCommandCatalog.ROOT) return root();
    if (!spec.children.isEmpty()) return group(spec);
    return leaf(spec);
  }

  public static String version(String distributionVersion) {
    return "riverd_version=" + distributionVersion + "\n"
        + "riverd_contract=riverd-v1\nriverd_protocol=river-v"
        + ProtocolFrameCodec.VERSION + "\nriverd_status=OK\n";
  }

  private static String root() {
    return "River is the local SQL client and authenticated River server command.\n"
        + "\nUsage:\n"
        + "  river [CLIENT_PROPERTIES] < script.sql\n"
        + "  river server <command> [options]\n"
        + "  river version\n\n"
        + "The default mode reads semicolon-terminated SQL from stdin, writes tab-separated\n"
        + "results to stdout, and stops on the first error. Without CLIENT_PROPERTIES it uses\n"
        + "~/.river/default/security/client.properties. An explicit path selects another instance;\n"
        + "use an absolute path such as /absolute/path/server when a client file has a reserved name.\n"
        + "All topics accept `-h` and `--help`; `river help <topic>` is equivalent.\n\n"
        + "Examples:\n"
        + "  river /path/to/security/client.properties < setup.sql\n"
        + "  river server start --port=9191\n"
        + "  river server version\n\n"
        + "Use `river help cli`, `river help server`, or `river help server start` for details.\n";
  }

  private static String group(RiverCommandCatalog.Spec spec) {
    StringBuilder output = new StringBuilder();
    output.append(spec.summary).append('\n').append('\n')
        .append("Usage: ").append(spec.usage).append("\n\n")
        .append("Commands:\n");
    for (RiverCommandCatalog.Spec child : spec.children) {
      output.append("  ").append(lastWord(child.topic)).append("  ")
          .append(child.summary);
      if (!child.available) output.append(" (unavailable)");
      output.append('\n');
    }
    output.append("\nUse `river help <topic>` for each command's options and behavior.\n");
    if (spec == RiverCommandCatalog.SERVER) {
      output.append("The default data directory is .river/default under the user's home and the\n"
          + "default port is ").append(RiverCommandCatalog.PORT.defaultValue)
          .append(". Start runs in the foreground, publishes TLS client configuration, and\n"
          + "accepts `--ready-file` for automation. Use `river help server <command>` for\n"
          + "command-specific options.\n");
    }
    if (spec == RiverCommandCatalog.CREDENTIALS) {
      output.append("Credential operations require a stopped instance. Use `river help server credentials renew`\n"
          + "for the renewal workflow.\n");
    }
    return output.toString();
  }

  private static String leaf(RiverCommandCatalog.Spec spec) {
    StringBuilder output = new StringBuilder();
    output.append(spec.summary).append('\n').append('\n')
        .append("Usage: ").append(RiverCommandCatalog.usage(spec)).append("\n\n");
    if (!spec.available) output.append("Availability: unavailable in this milestone.\n\n");
    if (spec == RiverCommandCatalog.CLI) {
      output.append("CLIENT_PROPERTIES is an absolute path to the generated security/client.properties;\n"
          + "when omitted, the default is ~/.river/default/security/client.properties.\n"
          + "SQL is framed by semicolons from stdin; output is tab-separated rows and ROWS counts.\n"
          + "Execution stops on the first SQL or connection error. Statements are limited to "
          + RiverSqlMain.MAXIMUM_STATEMENT_CHARACTERS + " Java characters.\n"
          + "Start a server to create client.properties, then pass its path to this command.\n"
          + "Example: river /absolute/path/security/client.properties < setup.sql\n");
    } else if (spec == RiverCommandCatalog.HELP) {
      output.append("Topics may be written after `help`, `-h`, or `--help`; a topic may also\n"
          + "carry its trailing `-h` or `--help`. These forms are equivalent.\n"
          + "Examples: `river help server`, `river server start --help`, and\n"
          + "`river help server credentials renew`.\n");
    } else if (spec == RiverCommandCatalog.START) {
      output.append("Defaults: datadir=.river/default under the user's home, port="
          + RiverCommandCatalog.PORT.defaultValue + ", ip=" + RiverCommandCatalog.IP.defaultValue
          + ", maximum-connections=" + RiverCommandCatalog.MAXIMUM_CONNECTIONS.defaultValue + ".\n"
          + "Relative paths resolve from the working directory. The server uses TLS and an\n"
          + "instance token, then publishes security/client.properties. Port zero selects an\n"
          + "available port; --ready-file writes a readiness record without overwriting a target.\n"
          + "The maximum-connections value must fit the configured resources before startup.\n"
          + "The process stays in the foreground and closes gracefully on shutdown.\n");
    } else if (spec == RiverCommandCatalog.STOP) {
      output.append("The command requests cooperative shutdown and waits up to the timeout; it\n"
          + "does not force-kill an instance. The default timeout is "
          + RiverCommandCatalog.TIMEOUT.defaultValue + ".\n");
    } else if (spec == RiverCommandCatalog.PS) {
      output.append("This command has no operation options. It lists verified current-user\n"
          + "instances with data directory, PID, and endpoint; an empty list is successful.\n"
          + "Stale records are reported as warnings and preserved.\n");
    } else if (spec == RiverCommandCatalog.RENEW) {
      output.append("Renewal requires a stopped instance, replaces its credentials, and does not\n"
          + "delete database data. Restart the instance and use the newly published client.properties.\n");
    } else if (spec == RiverCommandCatalog.VERSION || spec == RiverCommandCatalog.SERVER_VERSION) {
      output.append("Reports the distribution contract and protocol version without connecting\n"
          + "to an instance or changing instance files.\n");
    }
    if (!spec.options.isEmpty()) {
      output.append("\nOptions:\n");
      for (RiverCommandCatalog.Option option : spec.options) {
        output.append("  ").append(option.shortName.isEmpty() ? option.display()
            : option.shortName + " / " + option.display())
            .append(" (default: ").append(option.defaultValue).append(")\n")
            .append("      ").append(option.constraints).append('\n');
      }
    } else if (spec != RiverCommandCatalog.CLI) {
      output.append("This command has no operation options, apart from its help switches.\n");
    }
    if (spec == RiverCommandCatalog.START) {
      output.append("\nExample: river server start --datadir=/path/to/database --port=9191\n");
    } else if (spec == RiverCommandCatalog.STOP) {
      output.append("\nExample: river server stop --datadir=/path/to/database --timeout=60s\n");
    } else if (spec == RiverCommandCatalog.RENEW) {
      output.append("\nExample: river server credentials renew --datadir=/path/to/database\n");
    }
    return output.toString();
  }

  private static String lastWord(String topic) {
    int separator = topic.lastIndexOf(' ');
    return separator < 0 ? topic : topic.substring(separator + 1);
  }
}
