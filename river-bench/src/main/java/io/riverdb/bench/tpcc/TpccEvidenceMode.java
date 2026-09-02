package io.riverdb.bench.tpcc;

/** Selects the short engineering diagnostic or the normative Alpha3 sample contract. */
enum TpccEvidenceMode {
  DIAGNOSTIC,
  ALPHA3;

  static TpccEvidenceMode parse(String value) {
    return switch (value.toLowerCase(java.util.Locale.ROOT)) {
      case "diagnostic" -> DIAGNOSTIC;
      case "alpha3" -> ALPHA3;
      default -> throw new IllegalArgumentException("unknown evidence mode: " + value);
    };
  }
}
