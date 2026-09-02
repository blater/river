package io.riverdb.bench.tpcc;

public enum TpccPhase {
  LOAD_RUN_CHECKPOINT,
  RECOVERY_VERIFY;

  static TpccPhase parse(String value) {
    return switch (value) {
      case "load-run-checkpoint" -> LOAD_RUN_CHECKPOINT;
      case "recovery-verify" -> RECOVERY_VERIFY;
      default -> throw new IllegalArgumentException("unknown TPC-C phase: " + value);
    };
  }
}
