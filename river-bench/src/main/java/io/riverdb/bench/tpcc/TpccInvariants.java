package io.riverdb.bench.tpcc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Bounded load cardinality and cross-table business consistency checks. */
final class TpccInvariants {
  private TpccInvariants() {}

  static void verifyLoaded(Connection connection, TpccConfig config) throws SQLException {
    requireCount(connection, "warehouse", config.warehouses());
    requireCount(connection, "district", (long) config.warehouses() * config.districts());
    requireCount(connection, "item", config.itemCount());
    requireCount(connection, "stock", (long) config.warehouses() * config.itemCount());
    long customers = (long) config.warehouses() * config.districts() * config.customersPerDistrict();
    requireCount(connection, "customer", customers);
    requireCount(connection, "history", customers);
    requireCount(connection, "orders", (long) config.warehouses()
        * config.districts() * config.ordersPerDistrict());
    requireCount(connection, "new_order", (long) config.warehouses() * config.districts()
        * (config.ordersPerDistrict() - config.firstUndeliveredOrder() + 1));
    requireCount(connection, "order_line", initialLines(config));
    verifyBusiness(connection, config);
  }

  static void verifyBusiness(Connection connection, TpccConfig config) throws SQLException {
    requireCount(connection, "warehouse", config.warehouses());
    requireCount(connection, "district", (long) config.warehouses() * config.districts());
    requireCount(connection, "item", config.itemCount());
    requireCount(connection, "stock", (long) config.warehouses() * config.itemCount());
    requireCount(connection, "customer",
        (long) config.warehouses() * config.districts() * config.customersPerDistrict());
    long headerLines = scalarLong(connection, "SELECT SUM(o_ol_cnt) FROM orders");
    long physicalLines = scalarLong(connection, "SELECT COUNT(*) FROM order_line");
    require(headerLines == physicalLines, "orders/order_line cardinality mismatch");
    long pending = scalarLong(connection, "SELECT COUNT(*) FROM new_order");
    long nullCarriers = scalarLong(connection, "SELECT COUNT(*) FROM orders WHERE o_carrier_id IS NULL");
    require(pending == nullCarriers, "new_order/null-carrier mismatch");
    for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
      BigDecimal warehouseYtd = scalarDecimal(connection,
          "SELECT w_ytd FROM warehouse WHERE w_id=" + warehouse);
      BigDecimal districtYtd = scalarDecimal(connection,
          "SELECT SUM(d_ytd) FROM district WHERE d_w_id=" + warehouse);
      BigDecimal history = scalarDecimal(connection,
          "SELECT SUM(h_amount) FROM history WHERE h_w_id=" + warehouse);
      require(warehouseYtd.compareTo(districtYtd) == 0,
          "warehouse/district YTD mismatch for warehouse " + warehouse);
      require(warehouseYtd.compareTo(history) == 0,
          "warehouse/history YTD mismatch for warehouse " + warehouse);
      for (int district = 1; district <= config.districts(); district++) {
        verifyDistrict(connection, warehouse, district);
      }
    }
    long invalidStock = scalarLong(connection,
        "SELECT COUNT(*) FROM stock WHERE s_quantity<10 OR s_quantity>100");
    require(invalidStock == 0, "stock quantity escaped the standard range");
  }

  private static void verifyDistrict(Connection connection, int warehouse, int district)
      throws SQLException {
    long next = scalarLong(connection, "SELECT d_next_o_id FROM district WHERE d_w_id="
        + warehouse + " AND d_id=" + district);
    long maximum = scalarLong(connection, "SELECT MAX(o_id) FROM orders WHERE o_w_id="
        + warehouse + " AND o_d_id=" + district);
    String identity = "warehouse " + warehouse + " district " + district;
    require(next == maximum + 1, "district next order mismatch for " + identity);
    String suffix = " FROM new_order WHERE no_w_id=" + warehouse + " AND no_d_id=" + district;
    long count = scalarLong(connection, "SELECT COUNT(*)" + suffix);
    if (count > 0) {
      long minimum = scalarLong(connection, "SELECT MIN(no_o_id)" + suffix);
      long pendingMaximum = scalarLong(connection, "SELECT MAX(no_o_id)" + suffix);
      require(pendingMaximum - minimum + 1 == count,
          "new-order range is not contiguous for " + identity);
    }
  }

  private static void requireCount(Connection connection, String table, long expected)
      throws SQLException {
    long actual = scalarLong(connection, "SELECT COUNT(*) FROM " + table);
    require(actual == expected, table + " expected " + expected + " rows, found " + actual);
  }

  private static long initialLines(TpccConfig config) {
    long count = 0;
    for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
      for (int district = 1; district <= config.districts(); district++) {
        for (int order = 1; order <= config.ordersPerDistrict(); order++) {
          count += TpccLoader.initialLineCount(config, warehouse, district, order);
        }
      }
    }
    return count;
  }

  private static long scalarLong(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) throw new SQLException("invariant returned no row: " + sql);
      long value = rows.getLong(1);
      if (rows.next()) throw new SQLException("invariant returned extra row: " + sql);
      return value;
    }
  }

  private static BigDecimal scalarDecimal(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) throw new SQLException("invariant returned no row: " + sql);
      BigDecimal value = rows.getBigDecimal(1);
      if (value == null || rows.next()) throw new SQLException("invalid invariant scalar: " + sql);
      return value;
    }
  }

  private static void require(boolean condition, String message) throws SQLException {
    if (!condition) throw new SQLException("TPC-C invariant failed: " + message);
  }
}
