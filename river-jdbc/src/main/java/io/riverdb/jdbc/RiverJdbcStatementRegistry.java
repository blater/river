package io.riverdb.jdbc;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import java.sql.SQLException;
import java.util.Arrays;

/** Dynamically retained connection-owned registry for live JDBC statements. */
final class RiverJdbcStatementRegistry {
  private RiverJdbcStatement[] statements = new RiverJdbcStatement[0];
  private int firstFree;

  void register(RiverJdbcStatement statement) throws SQLException {
    if (statement == null) throw JdbcExceptions.invalid("statement must not be null");
    for (int index = firstFree; index < statements.length; index++) {
      if (statements[index] == null) {
        statements[index] = statement;
        firstFree = index + 1;
        return;
      }
    }
    int slot = statements.length;
    if (slot == Integer.MAX_VALUE) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "open statement");
    }
    int capacity = BoundedArrayGrowth.capacity(
        statements.length, slot + 1, Integer.MAX_VALUE, 8);
    if (capacity < 0) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "open statement");
    }
    try {
      statements = Arrays.copyOf(statements, capacity);
      statements[slot] = statement;
      firstFree = slot + 1;
    } catch (OutOfMemoryError failure) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "open statement");
    }
  }

  void unregister(RiverJdbcStatement statement) {
    if (statement == null) return;
    for (int index = 0; index < statements.length; index++) {
      if (statements[index] == statement) {
        statements[index] = null;
        if (index < firstFree) firstFree = index;
        return;
      }
    }
  }

  void closeOpenResults() throws SQLException {
    SQLException failure = null;
    for (RiverJdbcStatement statement : statements) {
      if (statement == null) continue;
      try {
        statement.closeOpenResult();
      } catch (SQLException closeFailure) {
        if (failure == null) failure = closeFailure;
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  SQLException closeAll() {
    SQLException failure = null;
    for (int index = 0; index < statements.length; index++) {
      RiverJdbcStatement statement = statements[index];
      if (statement == null) continue;
      try {
        statement.close();
      } catch (SQLException closeFailure) {
        if (failure == null) failure = closeFailure;
      } finally {
        // A failed statement close must not retain the statement or consume a slot.
        if (statements[index] == statement) statements[index] = null;
      }
    }
    firstFree = 0;
    return failure;
  }
}
