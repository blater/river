package io.riverdb.bench.tpcc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reusable prepared statements for Order-Status. */
final class TpccOrderStatus implements AutoCloseable {
  private final Connection connection;
  private final TpccCustomerLookup names;
  private final PreparedStatement customer;
  private final PreparedStatement latest;
  private final PreparedStatement lines;

  TpccOrderStatus(Connection connection) throws SQLException {
    this.connection = connection;
    names = new TpccCustomerLookup(connection);
    customer = connection.prepareStatement("SELECT c_balance,c_first,c_middle,c_last FROM customer WHERE c_w_id=? AND c_d_id=? AND c_id=?");
    latest = connection.prepareStatement("SELECT o_id,o_entry_d,o_carrier_id FROM orders WHERE o_w_id=? AND o_d_id=? AND o_c_id=? ORDER BY o_id DESC LIMIT 1");
    lines = connection.prepareStatement("SELECT ol_i_id,ol_supply_w_id,ol_quantity,ol_amount,ol_delivery_d FROM order_line WHERE ol_w_id=? AND ol_d_id=? AND ol_o_id=? ORDER BY ol_number");
  }

  boolean execute(TpccInputs.CustomerOrder input) throws SQLException {
    try {
      int customerId = input.customer == 0
          ? names.find(input.warehouse, input.district, input.last) : input.customer;
      customer.setInt(1, input.warehouse);
      customer.setInt(2, input.district);
      customer.setInt(3, customerId);
      try (ResultSet rows = customer.executeQuery()) {
        if (!rows.next()) throw new SQLException("order-status customer missing");
        rows.getBigDecimal(1);
      }
      latest.setInt(1, input.warehouse);
      latest.setInt(2, input.district);
      latest.setInt(3, customerId);
      int order;
      try (ResultSet rows = latest.executeQuery()) {
        order = TpccSql.requiredInt(rows, 1, "latest order lookup");
      }
      TpccSql.bindOrder(lines, input.warehouse, input.district, order);
      int count = 0;
      try (ResultSet rows = lines.executeQuery()) {
        while (rows.next()) count++;
      }
      if (count < 5 || count > 15) throw new SQLException("latest order has invalid line count " + count);
      connection.commit();
      return true;
    } catch (SQLException failure) {
      connection.rollback();
      throw failure;
    }
  }

  @Override
  public void close() throws SQLException {
    names.close(); customer.close(); latest.close(); lines.close();
  }
}
