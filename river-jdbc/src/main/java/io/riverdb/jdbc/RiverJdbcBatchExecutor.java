package io.riverdb.jdbc;

import io.riverdb.engine.api.ParameterSet;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.Arrays;

/** Executes the bounded batch owned by one JDBC statement. */
final class RiverJdbcBatchExecutor {
  private RiverJdbcBatchExecutor() { }

  static int[] execute(RiverJdbcStatement statement) throws SQLException {
    int entries = statement.batch.count();
    int[] updates = new int[entries];
    statement.batch.beginExecution();
    for (int index = 0; index < entries; index++) {
      String sql = statement.batch.takeSql(index);
      try {
        ParameterSet parameters = statement.batch.takeParameters(index);
        try {
          updates[index] = statement.executeUpdateSql(sql, parameters, false);
        } finally {
          if (parameters != null) {
            parameters.reset();
          }
        }
      } catch (SQLException failure) {
        releaseRemaining(statement, index + 1, entries);
        throw new BatchUpdateException(
            "River batch failed at entry " + index,
            failure.getSQLState(),
            failure.getErrorCode(),
            Arrays.copyOf(updates, index),
            failure);
      }
    }
    return updates;
  }

  static int[] executePrepared(RiverJdbcStatement statement, long handle)
      throws SQLException {
    int entries = statement.batch.count();
    int[] updates = new int[entries];
    statement.batch.beginExecution();
    for (int index = 0; index < entries; index++) {
      ParameterSet parameters = statement.batch.takeParameters(index);
      try {
        updates[index] = statement.executePreparedUpdate(handle, parameters, false);
      } catch (SQLException failure) {
        parameters.reset();
        releaseRemaining(statement, index + 1, entries);
        throw new BatchUpdateException(
            "River prepared batch failed at entry " + index,
            failure.getSQLState(), failure.getErrorCode(), Arrays.copyOf(updates, index), failure);
      }
      parameters.reset();
    }
    return updates;
  }

  private static void releaseRemaining(RiverJdbcStatement statement, int start, int entries) {
    for (int index = start; index < entries; index++) {
      statement.batch.release(index);
    }
  }
}
