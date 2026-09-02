package io.riverdb.bench.tpcc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reusable prepared statements for the complete New-Order transaction. */
final class TpccNewOrder implements AutoCloseable {
  private final Connection connection;
  private final PreparedStatement warehouse;
  private final PreparedStatement district;
  private final PreparedStatement advanceDistrict;
  private final PreparedStatement customer;
  private final PreparedStatement item;
  private final PreparedStatement stock;
  private final PreparedStatement updateStock;
  private final PreparedStatement insertOrder;
  private final PreparedStatement insertNewOrder;
  private final PreparedStatement insertLine;

  TpccNewOrder(Connection owner) throws SQLException {
    connection = owner;
    warehouse = owner.prepareStatement("SELECT w_tax FROM warehouse WHERE w_id=?");
    district = owner.prepareStatement("SELECT d_tax,d_next_o_id FROM district WHERE d_w_id=? AND d_id=? FOR UPDATE");
    advanceDistrict = owner.prepareStatement("UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=? AND d_id=?");
    customer = owner.prepareStatement("SELECT c_discount,c_last,c_credit FROM customer WHERE c_w_id=? AND c_d_id=? AND c_id=?");
    item = owner.prepareStatement("SELECT i_price,i_name,i_data FROM item WHERE i_id=?");
    stock = owner.prepareStatement("SELECT s_quantity,s_dist_01,s_dist_02,s_dist_03,s_dist_04,s_dist_05,s_dist_06,s_dist_07,s_dist_08,s_dist_09,s_dist_10,s_data FROM stock WHERE s_w_id=? AND s_i_id=? FOR UPDATE");
    updateStock = owner.prepareStatement("UPDATE stock SET s_quantity=?,s_ytd=s_ytd+?,s_order_cnt=s_order_cnt+1,s_remote_cnt=s_remote_cnt+? WHERE s_w_id=? AND s_i_id=?");
    insertOrder = owner.prepareStatement("INSERT INTO orders VALUES (?,?,?,?,?,NULL,?,?)");
    insertNewOrder = owner.prepareStatement("INSERT INTO new_order VALUES (?,?,?)");
    insertLine = owner.prepareStatement("INSERT INTO order_line VALUES (?,?,?,?,?,?,NULL,?,?,?)");
  }

  boolean execute(TpccInputs.NewOrder input) throws SQLException {
    try {
      requireWarehouse(input.warehouse);
      int order = lockDistrict(input.warehouse, input.district);
      requireCustomer(input.warehouse, input.district, input.customer);
      TpccSql.bindDistrict(advanceDistrict, input.warehouse, input.district);
      TpccSql.changedOne(advanceDistrict, "new-order.advance-district");
      insertHeader(input, order);
      for (int line = 0; line < input.lines; line++) {
        if (!insertItem(input, order, line)) {
          connection.rollback();
          return false;
        }
      }
      connection.commit();
      return true;
    } catch (SQLException failure) {
      connection.rollback();
      throw failure;
    }
  }

  private void requireWarehouse(int warehouseId) throws SQLException {
    warehouse.setInt(1, warehouseId);
    try (ResultSet rows = warehouse.executeQuery()) {
      TpccSql.requireRow(rows, "warehouse lookup");
    }
  }

  private int lockDistrict(int warehouseId, int districtId) throws SQLException {
    TpccSql.bindDistrict(district, warehouseId, districtId);
    try (ResultSet rows = district.executeQuery()) {
      return TpccSql.requiredInt(rows, 2, "district lookup");
    }
  }

  private void requireCustomer(int warehouseId, int districtId, int customerId)
      throws SQLException {
    customer.setInt(1, warehouseId);
    customer.setInt(2, districtId);
    customer.setInt(3, customerId);
    try (ResultSet rows = customer.executeQuery()) {
      TpccSql.requireRow(rows, "customer lookup");
    }
  }

  private void insertHeader(TpccInputs.NewOrder input, int order) throws SQLException {
    insertOrder.setInt(1, input.warehouse);
    insertOrder.setInt(2, input.district);
    insertOrder.setInt(3, order);
    insertOrder.setInt(4, input.customer);
    insertOrder.setTimestamp(5, input.entry);
    insertOrder.setInt(6, input.lines);
    insertOrder.setInt(7, allLocal(input) ? 1 : 0);
    TpccSql.changedOne(insertOrder, "new-order.insert-order");
    TpccSql.bindOrder(insertNewOrder, input.warehouse, input.district, order);
    TpccSql.changedOne(insertNewOrder, "new-order.insert-new-order");
  }

  private boolean insertItem(TpccInputs.NewOrder input, int order, int line) throws SQLException {
    item.setInt(1, input.item[line]);
    BigDecimal price;
    try (ResultSet rows = item.executeQuery()) {
      if (!rows.next()) return false;
      price = rows.getBigDecimal(1);
    }
    stock.setInt(1, input.supplyWarehouse[line]);
    stock.setInt(2, input.item[line]);
    int quantity;
    String distribution;
    try (ResultSet rows = stock.executeQuery()) {
      quantity = TpccSql.requiredInt(rows, 1, "stock lookup");
      distribution = rows.getString(input.district + 1);
    }
    int ordered = input.quantity[line];
    updateStock.setInt(1, quantity >= ordered + 10 ? quantity - ordered : quantity + 91 - ordered);
    updateStock.setInt(2, ordered);
    updateStock.setInt(3, input.supplyWarehouse[line] == input.warehouse ? 0 : 1);
    updateStock.setInt(4, input.supplyWarehouse[line]);
    updateStock.setInt(5, input.item[line]);
    TpccSql.changedOne(updateStock, "new-order.update-stock");
    insertLine.setInt(1, input.warehouse);
    insertLine.setInt(2, input.district);
    insertLine.setInt(3, order);
    insertLine.setInt(4, line + 1);
    insertLine.setInt(5, input.item[line]);
    insertLine.setInt(6, input.supplyWarehouse[line]);
    insertLine.setInt(7, ordered);
    insertLine.setBigDecimal(8, price.multiply(BigDecimal.valueOf(ordered)));
    insertLine.setString(9, distribution);
    TpccSql.changedOne(insertLine, "new-order.insert-line");
    return true;
  }

  private static boolean allLocal(TpccInputs.NewOrder input) {
    for (int line = 0; line < input.lines; line++) {
      if (input.supplyWarehouse[line] != input.warehouse) return false;
    }
    return true;
  }

  @Override
  public void close() throws SQLException {
    warehouse.close(); district.close(); advanceDistrict.close(); customer.close(); item.close();
    stock.close(); updateStock.close(); insertOrder.close(); insertNewOrder.close(); insertLine.close();
  }
}
