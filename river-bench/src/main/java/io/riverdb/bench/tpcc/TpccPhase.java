package io.riverdb.bench.tpcc;

public enum TpccPhase {
  LOAD_RUN(false),
  LOAD_RUN_CHECKPOINT(true),
  RECOVERY_VERIFY(false);

  private final boolean checkpoint;

  TpccPhase(boolean checkpoint) {
    this.checkpoint = checkpoint;
  }

  boolean performsCheckpoint() {
    return checkpoint;
  }

  static TpccPhase parse(String value) {
    return switch (value) {
      case "load-run" -> LOAD_RUN;
      case "load-run-checkpoint" -> LOAD_RUN_CHECKPOINT;
      case "recovery-verify" -> RECOVERY_VERIFY;
      default -> throw new IllegalArgumentException("unknown TPC-C phase: " + value);
    };
  }
}
