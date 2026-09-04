package io.riverdb.bench.tpcc;

import io.riverdb.jdbc.RiverTransactionDiagnostics;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Deterministically exercises opposing district locks and SQLSTATE 40001 cleanup. */
final class TpccConflictProbe {
  private static final long FIRST_ATTEMPT_TAG = 0x5052_4546_4C54_0001L;
  private static final long SECOND_ATTEMPT_TAG = 0x5052_4546_4C54_0002L;
  private static final long PREFLIGHT_METRICS_EPOCH = 3;
  private static final long INITIAL_LOCK_STEP = 1;
  private static final long OPPOSING_LOCK_STEP = 2;
  private static final String LOCK =
      "SELECT d_next_o_id FROM district WHERE d_w_id=? AND d_id=? FOR UPDATE";

  private TpccConflictProbe() {}

  static void run(String url) throws SQLException {
    try (Connection first = DriverManager.getConnection(url);
        Connection second = DriverManager.getConnection(url);
        PreparedStatement firstLock = first.prepareStatement(LOCK);
        PreparedStatement secondLock = second.prepareStatement(LOCK)) {
      RiverTransactionDiagnostics firstDiagnostics =
          first.unwrap(RiverTransactionDiagnostics.class);
      RiverTransactionDiagnostics secondDiagnostics =
          second.unwrap(RiverTransactionDiagnostics.class);
      firstDiagnostics.beginDiagnosticAttempt(FIRST_ATTEMPT_TAG, PREFLIGHT_METRICS_EPOCH);
      secondDiagnostics.beginDiagnosticAttempt(SECOND_ATTEMPT_TAG, PREFLIGHT_METRICS_EPOCH);
      first.setAutoCommit(false);
      second.setAutoCommit(false);
      firstDiagnostics.diagnosticStep(INITIAL_LOCK_STEP);
      lock(firstLock, 1);
      secondDiagnostics.diagnosticStep(INITIAL_LOCK_STEP);
      lock(secondLock, 2);
      boolean firstConflict;
      boolean secondConflict;
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<Boolean> firstResult = executor.submit(
            () -> opposingLock(first, firstLock, firstDiagnostics, 2));
        Future<Boolean> secondResult = executor.submit(
            () -> opposingLock(second, secondLock, secondDiagnostics, 1));
        firstConflict = result(firstResult);
        secondConflict = result(secondResult);
      } finally {
        executor.shutdownNow();
      }
      if (firstConflict == secondConflict) {
        throw new SQLException("opposing lock probe did not select exactly one deadlock victim");
      }
      lock(firstLock, 1);
      lock(firstLock, 2);
      first.rollback();
      System.out.println(
          "preflight_deadlock_probe=passed expected_victims=1 metrics_epoch="
              + PREFLIGHT_METRICS_EPOCH);
    }
  }

  private static boolean opposingLock(
      Connection connection,
      PreparedStatement statement,
      RiverTransactionDiagnostics diagnostics,
      int district) throws SQLException {
    try {
      diagnostics.diagnosticStep(OPPOSING_LOCK_STEP);
      lock(statement, district);
      connection.rollback();
      return false;
    } catch (SQLException failure) {
      connection.rollback();
      if (!"40001".equals(failure.getSQLState())) throw failure;
      return true;
    }
  }

  private static boolean result(Future<Boolean> result) throws SQLException {
    try {
      return result.get();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new SQLException("conflict probe interrupted", "57014", failure);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof SQLException sql) throw sql;
      throw new SQLException("conflict probe failed", cause);
    }
  }

  private static void lock(PreparedStatement statement, int district) throws SQLException {
    statement.setInt(1, 1);
    statement.setInt(2, district);
    try (ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) throw new SQLException("conflict probe district missing");
    }
  }
}
