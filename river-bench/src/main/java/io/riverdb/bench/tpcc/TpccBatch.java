package io.riverdb.bench.tpcc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** Bounded JDBC batch whose commit boundary is explicit and reusable. */
final class TpccBatch implements AutoCloseable {
  private final Connection connection;
  private final PreparedStatement statement;
  private final int capacity;
  private int pending;
  private long committed;

  TpccBatch(Connection owner, String sql, int rows) throws SQLException {
    connection = owner;
    capacity = rows;
    statement = owner.prepareStatement(sql);
    owner.setAutoCommit(false);
  }

  PreparedStatement statement() {
    return statement;
  }

  void add() throws SQLException {
    statement.addBatch();
    if (++pending == capacity) flush();
  }

  void flush() throws SQLException {
    if (pending == 0) return;
    int[] changes = statement.executeBatch();
    for (int change : changes) {
      if (change != 1 && change != Statement.SUCCESS_NO_INFO) {
        throw new SQLException("load batch affected " + change + " rows");
      }
    }
    connection.commit();
    committed += pending;
    pending = 0;
  }

  long committed() {
    return committed;
  }

  @Override
  public void close() throws SQLException {
    SQLException failure = null;
    try {
      flush();
    } catch (SQLException exception) {
      failure = exception;
      connection.rollback();
    }
    statement.close();
    connection.setAutoCommit(true);
    if (failure != null) throw failure;
  }
}
