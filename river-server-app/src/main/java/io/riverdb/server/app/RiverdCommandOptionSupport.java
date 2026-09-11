package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Shared admission and scalar validation for server command options. */
final class RiverdCommandOptionSupport {
  private RiverdCommandOptionSupport() { }

  static boolean startsWith(String value, RiverCommandCatalog.Option option) {
    return value.startsWith(option.longName + "=");
  }

  static String valueOf(String value, RiverCommandCatalog.Option option) {
    return value.substring(option.longName.length() + 1);
  }

  static Path path(String value) {
    if (value == null || value.isEmpty() || !value.equals(value.strip())) return null;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == 0 || character == '*' || character == '?' || character == '\n'
          || character == '\r' || Character.getType(character) == Character.CONTROL) return null;
    }
    try {
      return Path.of(value);
    } catch (InvalidPathException failure) {
      return null;
    }
  }

  static Integer decimal(String value, int minimum, int maximum) {
    if (value == null || value.isEmpty()) return null;
    long parsed = 0;
    for (int index = 0; index < value.length(); index++) {
      char digit = value.charAt(index);
      if (digit < '0' || digit > '9') return null;
      int valueDigit = digit - '0';
      if (parsed > (maximum - valueDigit) / 10) return null;
      parsed = parsed * 10 + valueDigit;
    }
    return parsed < minimum ? null : (int) parsed;
  }

  static Long duration(String value) {
    if (value == null || value.length() < 2) return null;
    boolean milliseconds = value.endsWith("ms");
    String suffix = value.substring(value.length() - 1);
    long multiplier = milliseconds ? 1 : "s".equals(suffix) ? 1_000
        : "m".equals(suffix) ? 60_000 : -1;
    int digits = milliseconds ? value.length() - 2 : value.length() - 1;
    if (multiplier < 0 || digits <= 0) return null;
    String number = value.substring(0, digits);
    long parsed = 0;
    for (int index = 0; index < number.length(); index++) {
      char digit = number.charAt(index);
      int valueDigit = digit - '0';
      if (valueDigit < 0 || valueDigit > 9
          || parsed > (Long.MAX_VALUE - valueDigit) / 10) return null;
      parsed = parsed * 10 + valueDigit;
    }
    return parsed <= 0 || parsed > Long.MAX_VALUE / multiplier ? null : parsed * multiplier;
  }

  static StatusCode fail(RiverdCommandResult result, String diagnostic) {
    result.fail(diagnostic);
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  static final class Cursor {
    private final String[] arguments;
    private final int limit;
    private int index = 1;

    Cursor(String[] arguments, int limit) {
      this.arguments = arguments;
      this.limit = limit;
    }

    boolean hasNext() { return index < limit; }
    boolean hasArgument() { return index < arguments.length; }
    String next() { return arguments[index++]; }
    String nextArgument() { return arguments[index++]; }
  }
}
