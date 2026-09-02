package io.riverdb.bench.tpcc;

public enum TpccScheduling {
  STANDARD,
  NO_WAIT_STRESS;

  static TpccScheduling parse(String value) {
    return switch (value) {
      case "standard" -> STANDARD;
      case "no-wait-stress" -> NO_WAIT_STRESS;
      default -> throw new IllegalArgumentException("unknown scheduling profile: " + value);
    };
  }
}
