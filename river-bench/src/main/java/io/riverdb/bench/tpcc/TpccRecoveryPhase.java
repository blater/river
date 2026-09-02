package io.riverdb.bench.tpcc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Verifies externally restarted River against the phase-one identity. */
final class TpccRecoveryPhase {
  private TpccRecoveryPhase() {}

  static void execute(TpccConfig config) throws Exception {
    TpccArtifact.Recovery recovery = TpccArtifact.read(config);
    TpccDatabaseIdentity identity;
    try (Connection connection = DriverManager.getConnection(config.url())) {
      TpccInvariants.verifyBusiness(connection, config);
      identity = TpccDatabaseIdentity.capture(connection, config);
      if (!identity.digest().equals(recovery.databaseDigest())) {
        throw new SQLException("recovered database identity differs from checkpoint artifact");
      }
      sample(connection, config);
    }
    TpccArtifact.markRecovered(config, recovery, identity.digest());
    System.out.println("phase=recovery-verify passed run_id=" + recovery.runId());
  }

  private static void sample(Connection connection, TpccConfig config) throws SQLException {
    connection.setAutoCommit(false);
    TpccValues values = new TpccValues(config.seed() ^ 0x5245_4F50_454EL);
    try (TpccSession session = new TpccSession(connection, config, 1)) {
      TpccInputs.StockLevel input = new TpccInputs.StockLevel();
      input.generate(values, 1, 1);
      TpccRetry.execute(() -> session.stockLevel.execute(input), config,
          System.nanoTime() + 30_000_000_000L);
    }
  }
}
