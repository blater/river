package io.riverdb.bench.tpcc;

import java.math.BigDecimal;
import java.util.SplittableRandom;

/** Deterministic streaming values and standard non-uniform key selection. */
final class TpccValues {
  private static final String[] LAST = {
      "BAR", "OUGHT", "ABLE", "PRI", "PRES", "ESE", "ANTI", "CALLY", "ATION", "EING"
  };
  private final SplittableRandom random;

  TpccValues(long seed) {
    random = new SplittableRandom(seed);
  }

  int number(int minimum, int maximum) {
    return random.nextInt(minimum, maximum + 1);
  }

  int nurand(int a, int minimum, int maximum, int c) {
    return (((number(0, a) | number(minimum, maximum)) + c)
        % (maximum - minimum + 1)) + minimum;
  }

  String lastName(int value) {
    int bounded = Math.floorMod(value, 1_000);
    return LAST[bounded / 100] + LAST[(bounded / 10) % 10] + LAST[bounded % 10];
  }

  String alpha(int minimum, int maximum) {
    int length = number(minimum, maximum);
    StringBuilder value = new StringBuilder(length);
    for (int index = 0; index < length; index++) value.append((char) ('a' + number(0, 25)));
    return value.toString();
  }

  String numeric(int length) {
    StringBuilder value = new StringBuilder(length);
    for (int index = 0; index < length; index++) value.append((char) ('0' + number(0, 9)));
    return value.toString();
  }

  String originalData(int minimum, int maximum, boolean original) {
    String value = alpha(minimum, maximum);
    if (!original) return value;
    int offset = number(0, value.length() - 8);
    return value.substring(0, offset) + "ORIGINAL" + value.substring(offset + 8);
  }

  BigDecimal money(int cents) {
    return BigDecimal.valueOf(cents, 2);
  }
}
