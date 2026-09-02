package io.riverdb.bench.tpcc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

final class TpccSql {
  private TpccSql() {}

  static void changedOne(PreparedStatement statement, String operation) throws SQLException {
    int rows;
    try {
      rows = statement.executeUpdate();
    } catch (SQLException failure) {
      throw new SQLException(
          operation + ": " + failure.getMessage(),
          failure.getSQLState(), failure.getErrorCode(), failure);
    }
    if (rows != 1) {
      throw new SQLException(operation + ": expected one changed row, got " + rows);
    }
  }

  static void bindDistrict(
      PreparedStatement statement, int warehouse, int district) throws SQLException {
    statement.setInt(1, warehouse);
    statement.setInt(2, district);
  }

  static void bindOrder(
      PreparedStatement statement, int warehouse, int district, int order) throws SQLException {
    bindDistrict(statement, warehouse, district);
    statement.setInt(3, order);
  }

  static int requiredInt(ResultSet rows, int column, String operation) throws SQLException {
    requireRow(rows, operation);
    return rows.getInt(column);
  }

  static void requireRow(ResultSet rows, String operation) throws SQLException {
    if (!rows.next()) throw new SQLException(operation + " returned no row");
  }
}
