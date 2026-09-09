package io.riverdb.server.app;

/** Canonical loopback endpoint accepted by daemon selection commands. */
record RiverDaemonEndpoint(String address, int port) {
  static RiverDaemonEndpoint parse(String value) {
    if (value == null || value.isEmpty() || !value.equals(value.strip())) return null;
    String address;
    String portText;
    if (value.startsWith("[")) {
      if (!value.startsWith("[::1]:")) return null;
      address = "::1";
      portText = value.substring("[::1]:".length());
    } else {
      int separator = value.indexOf(':');
      if (separator <= 0 || separator != value.lastIndexOf(':')) return null;
      address = value.substring(0, separator);
      portText = value.substring(separator + 1);
    }
    Integer port = decimal(portText);
    return port == null ? null : of(address, port);
  }

  static RiverDaemonEndpoint of(String address, int port) {
    String canonical = canonicalAddress(address);
    return canonical == null || port < 1 || port > 65535
        ? null : new RiverDaemonEndpoint(canonical, port);
  }

  boolean matches(String otherAddress, int otherPort) {
    return address.equals(canonicalAddress(otherAddress)) && port == otherPort;
  }

  @Override
  public String toString() {
    return address.indexOf(':') >= 0 ? "[" + address + "]:" + port : address + ":" + port;
  }

  private static String canonicalAddress(String value) {
    if ("localhost".equals(value) || "127.0.0.1".equals(value)) return "127.0.0.1";
    return "::1".equals(value) ? "::1" : null;
  }

  private static Integer decimal(String value) {
    if (value == null || value.isEmpty()) return null;
    int parsed = 0;
    for (int index = 0; index < value.length(); index++) {
      char digit = value.charAt(index);
      if (digit < '0' || digit > '9') return null;
      int valueDigit = digit - '0';
      if (parsed > (65535 - valueDigit) / 10) return null;
      parsed = parsed * 10 + valueDigit;
    }
    return parsed < 1 ? null : parsed;
  }
}
