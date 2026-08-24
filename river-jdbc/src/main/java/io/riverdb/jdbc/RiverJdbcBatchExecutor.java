package io.riverdb.jdbc;

import io.riverdb.engine.api.ParameterSet;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.Arrays;

/** Executes the bounded batch owned by one JDBC statement. */
final class RiverJdbcBatchExecutor {
  private RiverJdbcBatchExecutor() { }

  static int[] execute(RiverJdbcStatement statement) throws SQLException {
    int entries = statement.batchCount;
    int[] updates = new int[entries];
    statement.batchCount = 0;
    for (int index = 0; index < entries; index++) {
      String sql = statement.batch[index];
      statement.batch[index] = null;
      try {
        ParameterSet parameters = statement.batchParameters == null
            ? null : statement.batchParameters[index];
        if (statement.batchParameters != null) {
          statement.batchParameters[index] = null;
        }
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

  private static void releaseRemaining(RiverJdbcStatement statement, int start, int entries) {
    for (int index = start; index < entries; index++) {
      statement.batch[index] = null;
      statement.releaseBatchParameters(index);
    }
  }
}
