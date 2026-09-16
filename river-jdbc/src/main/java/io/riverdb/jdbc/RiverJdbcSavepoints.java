package io.riverdb.jdbc;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.Arrays;

/** Owns a connection's ordered savepoint handles and monotonic identity space. */
final class RiverJdbcSavepoints {
  private final RiverJdbcConnection connection;
  private RiverJdbcSavepoint[] values = new RiverJdbcSavepoint[0];
  private int count;
  private int nextId = 1;

  RiverJdbcSavepoints(RiverJdbcConnection owner) {
    connection = owner;
  }

  void reserve() throws SQLException {
    if (nextId <= 0 || count == Integer.MAX_VALUE) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "create savepoint");
    }
    if (count < values.length) return;
    int capacity = BoundedArrayGrowth.capacity(
        values.length, count + 1, Integer.MAX_VALUE, 4);
    if (capacity < 0) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "create savepoint");
    }
    try {
      values = Arrays.copyOf(values, capacity);
    } catch (OutOfMemoryError failure) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "create savepoint");
    }
  }

  int claimId() { return nextId++; }
  void add(RiverJdbcSavepoint value) { values[count++] = value; }
  RiverJdbcSavepoint get(int index) { return values[index]; }

  int require(Savepoint target, boolean transactionActive, String operation)
      throws SQLException {
    if (!(target instanceof RiverJdbcSavepoint candidate)
        || !candidate.isOwnedBy(connection) || !transactionActive) {
      throw JdbcExceptions.failure(StatusCode.CONFLICT, operation);
    }
    for (int index = count - 1; index >= 0; index--) {
      if (values[index] == candidate) return index;
    }
    throw JdbcExceptions.failure(StatusCode.CONFLICT, operation);
  }

  void completeFrom(int first) {
    for (int index = count - 1; index >= first; index--) {
      values[index].complete();
      values[index] = null;
    }
    count = first;
  }
}
