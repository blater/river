package io.riverdb.bench.tpcc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Deterministic material rollback and staged-mutation retry evidence. */
final class TpccAcceptanceProbes {
  record Result(int rollbacks, int retries) {}

  private TpccAcceptanceProbes() {}

  static Result run(TpccConfig config) throws SQLException {
    invalidItemRollback(config);
    stagedMutationRetry(config);
    return new Result(1, 1);
  }

  private static void invalidItemRollback(TpccConfig config) throws SQLException {
    int before;
    try (Connection connection = DriverManager.getConnection(config.url())) {
      before = districtNext(connection, 1);
      connection.setAutoCommit(false);
      TpccInputs.NewOrder input = new TpccInputs.NewOrder();
      input.generate(new TpccValues(config.seed() ^ 0x4142_4F52_54L), config, 1, 1);
      input.item[input.lines - 1] = config.itemCount() + 1;
      try (TpccNewOrder transaction = new TpccNewOrder(connection)) {
        if (transaction.execute(input)) throw new SQLException("invalid item unexpectedly committed");
      }
      if (districtNext(connection, 1) != before) {
        throw new SQLException("invalid-item rollback leaked district mutation");
      }
    }
    verifyDistrictLockReleased(config.url(), 1);
  }

  private static void stagedMutationRetry(TpccConfig config) throws SQLException {
    try (Connection connection = DriverManager.getConnection(config.url());
        PreparedStatement update = connection.prepareStatement(
            "UPDATE warehouse SET w_ytd=w_ytd+? WHERE w_id=1")) {
      connection.setAutoCommit(false);
      BigDecimal baseline = warehouseYtd(connection);
      TpccAttempt attempt = new TpccAttempt() {
        private boolean inject = true;
        @Override
        public boolean execute() throws SQLException {
          if (inject) {
            inject = false;
            update.setBigDecimal(1, BigDecimal.ONE);
            TpccSql.changedOne(update, "probe.concurrent-update");
            connection.rollback();
            throw new SQLException("injected after staged mutation", "40001");
          }
          if (warehouseYtd(connection).compareTo(baseline) != 0) {
            throw new SQLException("retry observed leaked staged mutation");
          }
          update.setBigDecimal(1, BigDecimal.ZERO);
          TpccSql.changedOne(update, "probe.concurrent-update");
          connection.commit();
          return true;
        }
      };
      long deadline = System.nanoTime() + 5_000_000_000L;
      TpccRetry.Result result = TpccRetry.execute(attempt, config, deadline);
      if (result.retries() != 1 || warehouseYtd(connection).compareTo(baseline) != 0) {
        throw new SQLException("staged-mutation retry evidence incomplete");
      }
    }
    verifyWarehouseLockReleased(config.url());
  }

  private static int districtNext(Connection connection, int district) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=?")) {
      statement.setInt(1, district);
      try (ResultSet rows = statement.executeQuery()) {
        return TpccSql.requiredInt(rows, 1, "probe district lookup");
      }
    }
  }

  private static BigDecimal warehouseYtd(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT w_ytd FROM warehouse WHERE w_id=1"); ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) throw new SQLException("probe warehouse missing");
      return rows.getBigDecimal(1);
    }
  }

  private static void verifyDistrictLockReleased(String url, int district) throws SQLException {
    try (Connection connection = DriverManager.getConnection(url);
        PreparedStatement statement = connection.prepareStatement(
            "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=? FOR UPDATE")) {
      connection.setAutoCommit(false);
      statement.setInt(1, district);
      try (ResultSet rows = statement.executeQuery()) {
        TpccSql.requiredInt(rows, 1, "post-rollback district lock");
      }
      connection.rollback();
    }
  }

  private static void verifyWarehouseLockReleased(String url) throws SQLException {
    try (Connection connection = DriverManager.getConnection(url);
        PreparedStatement statement = connection.prepareStatement(
            "SELECT w_ytd FROM warehouse WHERE w_id=1 FOR UPDATE")) {
      connection.setAutoCommit(false);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw new SQLException("post-retry warehouse missing");
      }
      connection.rollback();
    }
  }
}
