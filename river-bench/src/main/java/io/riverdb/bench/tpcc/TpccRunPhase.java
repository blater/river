package io.riverdb.bench.tpcc;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Load, measured run, invariant, checkpoint, and artifact phase. */
final class TpccRunPhase {
  private TpccRunPhase() {}

  static void execute(TpccConfig config) throws Exception {
    if (Files.exists(config.artifact())) {
      throw new IllegalStateException("refusing to overwrite acceptance artifact " + config.artifact());
    }
    loadAndVerify(config);
    TpccConflictProbe.run(config.url());
    TpccAcceptanceProbes.Result probes = TpccAcceptanceProbes.run(config);
    TpccProcessObservation before = TpccProcessObservation.capture();
    TpccMetrics metrics = TpccTerminalRunner.run(config);
    TpccProcessObservation after = TpccProcessObservation.capture();
    TpccPromotionGates.verify(metrics, probes.rollbacks(), probes.retries());
    TpccDatabaseIdentity identity = checkpointAndIdentify(config);
    String runId = TpccArtifact.write(config, metrics, identity, before, after,
        probes.rollbacks(), probes.retries());
    TpccReport.results(config, metrics);
    System.out.println("run_id=" + runId + " artifact=" + config.artifact().toAbsolutePath());
    System.out.println("phase=load-run-checkpoint complete; close/reopen River, then run recovery-verify");
  }

  private static void loadAndVerify(TpccConfig config) throws SQLException {
    try (Connection connection = DriverManager.getConnection(config.url())) {
      if (config.freshLoad()) {
        long start = System.nanoTime();
        TpccSchema.create(connection);
        new TpccLoader(config).load(connection);
        System.out.println("load_seconds=" + TpccReport.secondsSince(start));
      }
      TpccInvariants.verifyLoaded(connection, config);
    }
    System.out.println("pre_run_invariants=passed");
  }

  private static TpccDatabaseIdentity checkpointAndIdentify(TpccConfig config)
      throws SQLException {
    try (Connection connection = DriverManager.getConnection(config.url());
        Statement statement = connection.createStatement()) {
      TpccInvariants.verifyBusiness(connection, config);
      if (statement.executeUpdate("CHECKPOINT") != 0) {
        throw new SQLException("checkpoint changed rows");
      }
      System.out.println("post_run_invariants=passed checkpoint=completed");
      return TpccDatabaseIdentity.capture(connection, config);
    }
  }
}
