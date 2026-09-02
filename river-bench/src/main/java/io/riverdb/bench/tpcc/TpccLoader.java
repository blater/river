package io.riverdb.bench.tpcc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.sql.Timestamp;

/** Streams the complete configured TPC-C initial data set in dependency order. */
final class TpccLoader {
  private static final long EPOCH_MILLIS = 1_787_824_000_000L;
  private final TpccConfig config;
  private final TpccValues values;

  TpccLoader(TpccConfig configuration) {
    config = configuration;
    values = new TpccValues(configuration.seed());
  }

  void load(Connection connection) throws SQLException {
    loadWarehouse(connection);
    loadDistricts(connection);
    loadItems(connection);
    loadStock(connection);
    loadCustomers(connection);
    loadHistory(connection);
    loadOrders(connection);
    loadNewOrders(connection);
    loadOrderLines(connection);
  }

  private void loadWarehouse(Connection connection) throws SQLException {
    try (TpccBatch batch = batch(connection, "warehouse", "INSERT INTO warehouse VALUES (?,?,?,?,?,?,?,?,?)")) {
      PreparedStatement row = batch.statement();
      for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
        row.setInt(1, warehouse);
        address(row, 2, "W" + warehouse);
        row.setBigDecimal(8, new BigDecimal("0.1000"));
        row.setBigDecimal(9, BigDecimal.valueOf(
            (long) config.districts() * config.customersPerDistrict() * 1_000L, 2));
        batch.add();
      }
    }
  }

  private void loadDistricts(Connection connection) throws SQLException {
    try (TpccBatch batch = batch(connection, "district", "INSERT INTO district VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
      PreparedStatement row = batch.statement();
      for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
        for (int district = 1; district <= config.districts(); district++) {
          row.setInt(1, warehouse);
          row.setInt(2, district);
          address(row, 3, "D" + district);
          row.setBigDecimal(9, new BigDecimal("0.1000"));
          row.setBigDecimal(10, BigDecimal.valueOf(
              (long) config.customersPerDistrict() * 1_000L, 2));
          row.setInt(11, config.ordersPerDistrict() + 1);
          batch.add();
        }
      }
    }
  }

  private void loadItems(Connection connection) throws SQLException {
    try (TpccBatch batch = batch(connection, "item", "INSERT INTO item VALUES (?,?,?,?,?)")) {
      PreparedStatement row = batch.statement();
      for (int item = 1; item <= config.itemCount(); item++) {
        row.setInt(1, item);
        row.setInt(2, values.number(1, 10_000));
        row.setString(3, values.alpha(14, 24));
        row.setBigDecimal(4, values.money(values.number(100, 10_000)));
        row.setString(5, values.originalData(26, 50, original(item, 17)));
        batch.add();
      }
    }
  }

  private void loadStock(Connection connection) throws SQLException {
    try (TpccBatch batch = batch(connection, "stock", "INSERT INTO stock VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      PreparedStatement row = batch.statement();
      for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
        for (int item = 1; item <= config.itemCount(); item++) {
          row.setInt(1, warehouse);
          row.setInt(2, item);
          row.setInt(3, values.number(10, 100));
          for (int district = 0; district < 10; district++) {
            row.setString(4 + district, values.alpha(24, 24));
          }
          row.setInt(14, 0);
          row.setInt(15, 0);
          row.setInt(16, 0);
          row.setString(17, values.originalData(26, 50, original(item, 43)));
          batch.add();
        }
      }
    }
  }

  private void loadCustomers(Connection connection) throws SQLException {
    String sql = "INSERT INTO customer VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    try (TpccBatch batch = batch(connection, "customer", sql)) {
      PreparedStatement row = batch.statement();
      for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
        for (int district = 1; district <= config.districts(); district++) {
          for (int customer = 1; customer <= config.customersPerDistrict(); customer++) {
            row.setInt(1, warehouse);
            row.setInt(2, district);
            row.setInt(3, customer);
            row.setString(4, values.alpha(8, 16));
            row.setString(5, "OE");
            row.setString(6, values.lastName(customer <= 1_000 ? customer - 1
                : values.nurand(255, 0, 999, TpccNurandConstants.STANDARD.loadLast())));
            row.setString(7, "street one");
            row.setString(8, "street two");
            row.setString(9, "city");
            row.setString(10, "ST");
            row.setString(11, "123456789");
            row.setString(12, values.numeric(16));
            row.setTimestamp(13, timestamp(entityOffset(warehouse) + district * 10_000L + customer));
            row.setString(14, original(customer, 71) ? "BC" : "GC");
            row.setBigDecimal(15, BigDecimal.valueOf(values.number(0, 5_000), 4));
            row.setBigDecimal(16, new BigDecimal("50000.00"));
            row.setBigDecimal(17, new BigDecimal("-10.00"));
            row.setBigDecimal(18, new BigDecimal("10.00"));
            row.setInt(19, 1);
            row.setInt(20, 0);
            row.setString(21, values.alpha(300, 500));
            batch.add();
          }
        }
      }
    }
  }

  private void loadHistory(Connection connection) throws SQLException {
    try (TpccBatch batch = batch(connection, "history", "INSERT INTO history VALUES (?,?,?,?,?,?,?,?)")) {
      PreparedStatement row = batch.statement();
      for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
        for (int district = 1; district <= config.districts(); district++) {
          for (int customer = 1; customer <= config.customersPerDistrict(); customer++) {
            row.setInt(1, customer);
            row.setInt(2, district);
            row.setInt(3, warehouse);
            row.setInt(4, district);
            row.setInt(5, warehouse);
            row.setTimestamp(6, timestamp(entityOffset(warehouse) + district * 10_000L + customer));
            row.setBigDecimal(7, new BigDecimal("10.00"));
            row.setString(8, values.alpha(12, 24));
            batch.add();
          }
        }
      }
    }
  }

  private void loadOrders(Connection connection) throws SQLException {
    try (TpccBatch batch = batch(connection, "orders", "INSERT INTO orders VALUES (?,?,?,?,?,?,?,?)")) {
      PreparedStatement row = batch.statement();
      int[] customers = new int[config.customersPerDistrict()];
      for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
        for (int district = 1; district <= config.districts(); district++) {
          permutation(customers);
          for (int order = 1; order <= config.ordersPerDistrict(); order++) {
            int lines = initialLineCount(warehouse, district, order);
            row.setInt(1, warehouse);
            row.setInt(2, district);
            row.setInt(3, order);
            row.setInt(4, customers[(order - 1) % customers.length]);
            row.setTimestamp(5, timestamp(entityOffset(warehouse) + district * 100_000L + order));
            if (order < config.firstUndeliveredOrder()) row.setInt(6, values.number(1, 10));
            else row.setNull(6, Types.SMALLINT);
            row.setInt(7, lines);
            row.setInt(8, 1);
            batch.add();
          }
        }
      }
    }
  }

  private void loadNewOrders(Connection connection) throws SQLException {
    try (TpccBatch batch = batch(connection, "new_order", "INSERT INTO new_order VALUES (?,?,?)")) {
      PreparedStatement row = batch.statement();
      for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
        for (int district = 1; district <= config.districts(); district++) {
          for (int order = config.firstUndeliveredOrder(); order <= config.ordersPerDistrict(); order++) {
            row.setInt(1, warehouse);
            row.setInt(2, district);
            row.setInt(3, order);
            batch.add();
          }
        }
      }
    }
  }

  private void loadOrderLines(Connection connection) throws SQLException {
    String sql = "INSERT INTO order_line VALUES (?,?,?,?,?,?,?,?,?,?)";
    try (TpccBatch batch = batch(connection, "order_line", sql)) {
      PreparedStatement row = batch.statement();
      for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
        for (int district = 1; district <= config.districts(); district++) {
          for (int order = 1; order <= config.ordersPerDistrict(); order++) {
            int count = initialLineCount(warehouse, district, order);
            for (int line = 1; line <= count; line++) {
              row.setInt(1, warehouse);
              row.setInt(2, district);
              row.setInt(3, order);
              row.setInt(4, line);
              row.setInt(5, values.number(1, config.itemCount()));
              row.setInt(6, warehouse);
              if (order < config.firstUndeliveredOrder()) {
                row.setTimestamp(7, timestamp(entityOffset(warehouse) + district * 100_000L + order));
              } else {
                row.setNull(7, Types.TIMESTAMP);
              }
              row.setInt(8, 5);
              row.setBigDecimal(9, order < config.firstUndeliveredOrder()
                  ? BigDecimal.ZERO.setScale(2) : values.money(values.number(1, 999_999)));
              row.setString(10, values.alpha(24, 24));
              batch.add();
            }
          }
        }
      }
    }
  }

  private TpccBatch batch(Connection connection, String table, String sql) throws SQLException {
    TpccLoaderShape.validate(table, sql);
    try {
      return new TpccBatch(connection, sql, config.batchRows());
    } catch (SQLException failure) {
      throw new SQLException("cannot start bounded load of " + table, failure);
    }
  }

  private void permutation(int[] customers) {
    for (int index = 0; index < customers.length; index++) customers[index] = index + 1;
    for (int index = customers.length - 1; index > 0; index--) {
      int other = values.number(0, index);
      int saved = customers[index];
      customers[index] = customers[other];
      customers[other] = saved;
    }
  }

  int initialLineCount(int warehouse, int district, int order) {
    return initialLineCount(config, warehouse, district, order);
  }

  static int initialLineCount(TpccConfig config, int warehouse, int district, int order) {
    long mixed = config.seed() ^ (warehouse - 1L) * 0xC2B2_AE35L
        ^ district * 0x9E37_79B9L ^ order * 0x85EB_CA6BL;
    return 5 + Math.floorMod((int) (mixed ^ mixed >>> 32), 11);
  }

  private static long entityOffset(int warehouse) {
    return (warehouse - 1L) * 1_000_000_000L;
  }

  private boolean original(int id, int salt) {
    return Math.floorMod(id * 31 + salt, 10) == 0;
  }

  private static Timestamp timestamp(long seconds) {
    return new Timestamp(EPOCH_MILLIS + seconds * 1_000L);
  }

  private static void address(PreparedStatement row, int offset, String name) throws SQLException {
    row.setString(offset, name);
    row.setString(offset + 1, "street one");
    row.setString(offset + 2, "street two");
    row.setString(offset + 3, "city");
    row.setString(offset + 4, "ST");
    row.setString(offset + 5, "123456789");
  }
}
