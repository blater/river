package io.riverdb.jdbc;

import java.sql.SQLException;
import java.sql.Savepoint;

/** Connection-owned handle for the one active savepoint supported by River. */
final class RiverJdbcSavepoint implements Savepoint {
  private final RiverJdbcConnection connection;
  private final int id;
  private final String name;
  private final String sqlName;
  private boolean active = true;

  RiverJdbcSavepoint(
      RiverJdbcConnection owner,
      int savepointId,
      String externalName,
      String internalName) {
    connection = owner;
    id = savepointId;
    name = externalName;
    sqlName = internalName;
  }

  @Override
  public int getSavepointId() throws SQLException {
    requireActive();
    if (name != null) {
      throw JdbcExceptions.invalid("named savepoint has no numeric id");
    }
    return id;
  }

  @Override
  public String getSavepointName() throws SQLException {
    requireActive();
    if (name == null) {
      throw JdbcExceptions.invalid("unnamed savepoint has no name");
    }
    return name;
  }

  boolean isOwnedBy(RiverJdbcConnection owner) {
    return active && connection == owner;
  }

  String sqlName() {
    return sqlName;
  }

  void complete() {
    active = false;
  }

  private void requireActive() throws SQLException {
    if (!active) {
      throw JdbcExceptions.invalid("savepoint is no longer active");
    }
  }
}
