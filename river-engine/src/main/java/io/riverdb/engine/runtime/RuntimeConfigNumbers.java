package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Allocation-light numeric grammar shared by runtime configuration settings. */
final class RuntimeConfigNumbers {
  private RuntimeConfigNumbers() {}

  static long parseSize(
      String property,
      String value,
      StatusDetail detail) {
    int digits = digitPrefix(value);
    if (digits == 0) {
      invalid(detail, property, value, "expected unsigned decimal size");
      return -1;
    }
    long multiplier = sizeMultiplier(value, digits);
    if (multiplier < 0) {
      invalid(detail, property, value, "expected bytes, KB, MB, or GB");
      return -1;
    }
    long parsed = parseDigits(value, digits);
    if (parsed < 0 || parsed > Long.MAX_VALUE / multiplier) {
      invalid(detail, property, value, "size overflow");
      return -1;
    }
    return parsed * multiplier;
  }

  static long parseUnsignedDecimal(
      String property,
      String value,
      StatusDetail detail) {
    if (value.isEmpty()) {
      invalid(detail, property, value, "expected unsigned decimal integer");
      return -1;
    }
    for (int index = 0; index < value.length(); index++) {
      if (!digit(value.charAt(index))) {
        invalid(detail, property, value, "expected unsigned decimal integer");
        return -1;
      }
    }
    long parsed = parseDigits(value, value.length());
    if (parsed < 0) {
      invalid(detail, property, value, "integer overflow");
      return -1;
    }
    return parsed;
  }

  static long parseDurationNanos(
      String property,
      String value,
      StatusDetail detail) {
    int digits = digitPrefix(value);
    if (digits == 0) {
      invalid(detail, property, value, "expected positive duration");
      return -1;
    }
    long multiplier = durationNanosMultiplier(value, digits);
    if (multiplier < 0) {
      invalid(detail, property, value, "expected ns, us, ms, s, m, or h");
      return -1;
    }
    long parsed = parseDigits(value, digits);
    if (parsed <= 0 || parsed > Long.MAX_VALUE / multiplier) {
      invalid(detail, property, value, parsed == 0 ? "must be positive" : "duration overflow");
      return -1;
    }
    return parsed * multiplier;
  }

  static long roundDown(long value, int unit) {
    return value / unit * unit;
  }

  static StatusCode invalid(
      StatusDetail detail,
      CharSequence property,
      CharSequence value,
      CharSequence reason) {
    detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
        .append("invalid ")
        .append(property)
        .append(": ")
        .append(value)
        .append(" (")
        .append(reason)
        .append(')');
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static int digitPrefix(String value) {
    int digits = 0;
    while (digits < value.length() && digit(value.charAt(digits))) digits++;
    return digits;
  }

  private static long sizeMultiplier(String value, int digits) {
    if (digits == value.length()) return 1;
    return switch (value.substring(digits)) {
      case "KB" -> 1_000L;
      case "MB" -> 1_000_000L;
      case "GB" -> 1_000_000_000L;
      default -> -1;
    };
  }

  private static long durationNanosMultiplier(String value, int digits) {
    return switch (value.substring(digits)) {
      case "ns" -> 1L;
      case "us" -> 1_000L;
      case "ms" -> 1_000_000L;
      case "s" -> 1_000_000_000L;
      case "m" -> 60_000_000_000L;
      case "h" -> 3_600_000_000_000L;
      default -> -1;
    };
  }

  private static long parseDigits(String value, int length) {
    long parsed = 0;
    for (int index = 0; index < length; index++) {
      int digit = value.charAt(index) - '0';
      if (parsed > (Long.MAX_VALUE - digit) / 10) return -1;
      parsed = parsed * 10 + digit;
    }
    return parsed;
  }

  private static boolean digit(char value) {
    return value >= '0' && value <= '9';
  }
}
