package io.riverdb.bench.tpcc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Standard lower-median customer selection for a non-unique last name. */
final class TpccCustomerLookup implements AutoCloseable {
  private final PreparedStatement count;
  private final PreparedStatement ordered;

  TpccCustomerLookup(java.sql.Connection connection) throws SQLException {
    count = connection.prepareStatement(
        "SELECT COUNT(*) FROM customer WHERE c_w_id=? AND c_d_id=? AND c_last=?");
    ordered = connection.prepareStatement(
        "SELECT c_id FROM customer WHERE c_w_id=? AND c_d_id=? AND c_last=? ORDER BY c_first");
  }

  int find(int warehouse, int district, String last) throws SQLException {
    bind(count, warehouse, district, last);
    int matches;
    try (ResultSet rows = count.executeQuery()) {
      if (!rows.next()) throw new SQLException("customer-name count returned no row");
      matches = rows.getInt(1);
    }
    if (matches == 0) throw new SQLException("customer last name has no match: " + last);
    bind(ordered, warehouse, district, last);
    int target = (matches - 1) / 2;
    try (ResultSet rows = ordered.executeQuery()) {
      for (int index = 0; index <= target; index++) {
        if (!rows.next()) throw new SQLException("customer-name result shortened");
      }
      return rows.getInt(1);
    }
  }

  @Override
  public void close() throws SQLException {
    count.close();
    ordered.close();
  }

  private static void bind(PreparedStatement statement, int warehouse, int district, String last)
      throws SQLException {
    statement.setInt(1, warehouse);
    statement.setInt(2, district);
    statement.setString(3, last);
  }
}
