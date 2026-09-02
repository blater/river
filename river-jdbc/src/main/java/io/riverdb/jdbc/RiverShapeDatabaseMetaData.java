package io.riverdb.jdbc;

import io.riverdb.base.sql.SqlShapeLimits;
import java.sql.SQLException;

/** Semantic SQL-shape limits shared by River's JDBC metadata surface. */
abstract class RiverShapeDatabaseMetaData extends AbstractDatabaseMetaData {
  private final RiverJdbcConnection connection;

  RiverShapeDatabaseMetaData(RiverJdbcConnection jdbcConnection) {
    connection = jdbcConnection;
  }

  @Override
  public int getMaxColumnsInSelect() throws SQLException {
    connection.requireOpen();
    return SqlShapeLimits.MAX_RESULT_COLUMNS;
  }

  @Override
  public int getMaxColumnsInTable() throws SQLException {
    connection.requireOpen();
    return SqlShapeLimits.MAX_TABLE_COLUMNS;
  }

  @Override
  public int getMaxColumnsInIndex() throws SQLException {
    connection.requireOpen();
    return SqlShapeLimits.MAX_KEY_PARTS;
  }
}
