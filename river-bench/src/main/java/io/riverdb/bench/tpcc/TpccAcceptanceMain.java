package io.riverdb.bench.tpcc;

/** JDBC-only TPC-C functional acceptance entry point. */
public final class TpccAcceptanceMain {
  private TpccAcceptanceMain() {}

  public static void main(String[] arguments) throws Exception {
    TpccConfig config = TpccConfig.parse(arguments);
    Class.forName("io.riverdb.jdbc.RiverDriver");
    TpccReport.configuration(config);
    System.out.println("phase_start=" + (config.phase() == TpccPhase.RECOVERY_VERIFY
        ? "recovery" : "load"));
    if (config.phase() == TpccPhase.RECOVERY_VERIFY) TpccRecoveryPhase.execute(config);
    else TpccRunPhase.execute(config);
  }
}
