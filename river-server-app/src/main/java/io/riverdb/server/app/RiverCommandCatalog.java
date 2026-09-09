package io.riverdb.server.app;

import java.util.List;

/** Concrete command and option metadata shared by parsing and help rendering. */
final class RiverCommandCatalog {
  static final int DEFAULT_PORT = 9191;
  static final int DEFAULT_MAXIMUM_CONNECTIONS = 16;
  static final long DEFAULT_TIMEOUT_MILLIS = 30_000;

  static final Option DATADIR = new Option("-D", "--datadir", "PATH", "~/.river/default",
      "The persistent instance directory; relative paths use the working directory.");
  static final Option PORT = new Option("", "--port", "PORT", Integer.toString(DEFAULT_PORT),
      "Decimal 0..65535; zero selects an available port.");
  static final Option IP = new Option("", "--ip", "ADDRESS", "127.0.0.1",
      "127.0.0.1 or ::1; remote addresses and hostnames are rejected.");
  static final Option MAXIMUM_CONNECTIONS = new Option("", "--maximum-connections", "N",
      Integer.toString(DEFAULT_MAXIMUM_CONNECTIONS), "Positive decimal 1..2147483647.");
  static final Option READY_FILE = new Option("", "--ready-file", "PATH", "None",
      "Optional readiness record; an existing target is never overwritten.");
  static final Option TIMEOUT = new Option("", "--timeout", "DURATION",
      (DEFAULT_TIMEOUT_MILLIS / 1_000) + "s",
      "Positive decimal duration with ms, s, or m units, fitting signed-long milliseconds; zero, missing units, and overflow are rejected.");

  static final Spec CLI = new Spec("cli", "river [CLIENT_PROPERTIES] < script.sql",
      "Run SQL from stdin using a generated client configuration.", true, List.of(), List.of());
  static final Spec HELP = new Spec("help", "river help [TOPIC...]", "Navigate command help.", true,
      List.of(), List.of());
  static final Spec VERSION = new Spec("version", "river version",
      "Print distribution and protocol version.", true, List.of(), List.of());
  static final Spec START = new Spec("server start", "river server start [options]",
      "Start the authenticated server in the foreground.", true,
      List.of(DATADIR, PORT, IP, MAXIMUM_CONNECTIONS, READY_FILE), List.of());
  static final Spec STOP = new Spec("stop", "river stop [HOST:PORT]",
      "Request cooperative shutdown of an instance.", true,
      List.of(DATADIR, TIMEOUT), List.of());
  static final Spec PS = new Spec("ps", "river ps",
      "List verified instances owned by the current user.", true,
      List.of(), List.of());
  static final Spec RENEW = new Spec("server credentials renew",
      "river server credentials renew [options]", "Replace credentials for a stopped instance.", false,
      List.of(DATADIR), List.of());
  static final Spec CREDENTIALS = new Spec("server credentials", "river server credentials <command>",
      "Manage credentials for a stopped instance.", true, List.of(), List.of(RENEW));
  static final Spec SERVER_VERSION = new Spec("server version", "river server version",
      "Print the installed server and protocol version.", true, List.of(), List.of());
  static final Spec SERVER = new Spec("server", "river server <command> [options]",
      "Run or inspect one authenticated local River instance.", true, List.of(),
      List.of(START, STOP, PS, CREDENTIALS, SERVER_VERSION));
  static final Spec ROOT = new Spec("", "river [CLIENT_PROPERTIES] < script.sql",
      "The River client and local server command.", true, List.of(),
      List.of(CLI, SERVER, START, STOP, PS, VERSION, HELP));

  private RiverCommandCatalog() { }

  static Spec find(String topic) {
    if (topic == null || topic.isEmpty()) return ROOT;
    if ("cli".equals(topic)) return CLI;
    if ("help".equals(topic)) return HELP;
    if ("version".equals(topic)) return VERSION;
    if ("server".equals(topic)) return SERVER;
    if ("start".equals(topic) || "server start".equals(topic)) return START;
    if ("stop".equals(topic) || "server stop".equals(topic)) return STOP;
    if ("ps".equals(topic) || "server ps".equals(topic)) return PS;
    if ("server credentials".equals(topic)) return CREDENTIALS;
    if ("server credentials renew".equals(topic)) return RENEW;
    if ("server version".equals(topic)) return SERVER_VERSION;
    return null;
  }

  static String serverTopic(String topic) {
    if (topic == null || topic.isEmpty()) return "server";
    String canonical = topic.startsWith("server ") || "server".equals(topic)
        ? topic : "server " + topic;
    return find(canonical) == null ? null : canonical;
  }

  static String rootTopic(String topic) {
    if (topic == null || topic.isEmpty()) return "";
    return find(topic) == null ? null : topic;
  }

  static boolean isHelp(String value) { return "-h".equals(value) || "--help".equals(value); }

  static String join(String[] arguments, int start) {
    StringBuilder result = new StringBuilder();
    for (int index = start; index < arguments.length; index++) {
      if (result.length() > 0) result.append(' ');
      result.append(arguments[index]);
    }
    return result.toString();
  }

  static String usage(Spec spec) {
    StringBuilder usage = new StringBuilder(spec.usage);
    for (Option option : spec.options) {
      usage.append(' ').append(option.shortName.isEmpty() ? option.display()
          : option.shortName + " " + option.valueSyntax + " / " + option.display());
    }
    return usage.toString();
  }

  static final class Option {
    final String shortName;
    final String longName;
    final String valueSyntax;
    final String defaultValue;
    final String constraints;

    Option(String shortName, String longName, String valueSyntax, String defaultValue,
        String constraints) {
      this.shortName = shortName;
      this.longName = longName;
      this.valueSyntax = valueSyntax;
      this.defaultValue = defaultValue;
      this.constraints = constraints;
    }

    String display() { return longName + "=" + valueSyntax; }
  }

  static final class Spec {
    final String topic;
    final String usage;
    final String summary;
    final boolean available;
    final List<Option> options;
    final List<Spec> children;

    Spec(String topic, String usage, String summary, boolean available,
        List<Option> options, List<Spec> children) {
      this.topic = topic;
      this.usage = usage;
      this.summary = summary;
      this.available = available;
      this.options = options;
      this.children = children;
    }
  }
}
