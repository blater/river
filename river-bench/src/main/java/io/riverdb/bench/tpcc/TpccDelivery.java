package io.riverdb.bench.tpcc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Delivery processes the oldest pending order in each of ten districts atomically. */
final class TpccDelivery implements AutoCloseable {
  private final Connection connection;
  private final int districts;
  private final PreparedStatement oldest;
  private final PreparedStatement remove;
  private final PreparedStatement orderCustomer;
  private final PreparedStatement setCarrier;
  private final PreparedStatement total;
  private final PreparedStatement deliverLines;
  private final PreparedStatement updateCustomer;

  TpccDelivery(Connection owner, int districtCount) throws SQLException {
    connection = owner;
    districts = districtCount;
    oldest = owner.prepareStatement("SELECT no_o_id FROM new_order WHERE no_w_id=? AND no_d_id=? ORDER BY no_o_id LIMIT 1 FOR UPDATE");
    remove = owner.prepareStatement("DELETE FROM new_order WHERE no_w_id=? AND no_d_id=? AND no_o_id=?");
    orderCustomer = owner.prepareStatement("SELECT o_c_id FROM orders WHERE o_w_id=? AND o_d_id=? AND o_id=?");
    setCarrier = owner.prepareStatement("UPDATE orders SET o_carrier_id=? WHERE o_w_id=? AND o_d_id=? AND o_id=?");
    total = owner.prepareStatement("SELECT SUM(ol_amount) FROM order_line WHERE ol_w_id=? AND ol_d_id=? AND ol_o_id=?");
    deliverLines = owner.prepareStatement("UPDATE order_line SET ol_delivery_d=? WHERE ol_w_id=? AND ol_d_id=? AND ol_o_id=?");
    updateCustomer = owner.prepareStatement("UPDATE customer SET c_balance=c_balance+?,c_delivery_cnt=c_delivery_cnt+1 WHERE c_w_id=? AND c_d_id=? AND c_id=?");
  }

  boolean execute(TpccInputs.Delivery input) throws SQLException {
    try {
      for (int district = 1; district <= districts; district++) {
        deliverDistrict(input, district);
      }
      connection.commit();
      return true;
    } catch (SQLException failure) {
      connection.rollback();
      throw failure;
    }
  }

  private void deliverDistrict(TpccInputs.Delivery input, int district) throws SQLException {
    TpccSql.bindDistrict(oldest, input.warehouse, district);
    int order;
    try (ResultSet rows = oldest.executeQuery()) {
      if (!rows.next()) return;
      order = rows.getInt(1);
    }
    TpccSql.bindOrder(remove, input.warehouse, district, order);
    TpccSql.changedOne(remove, "delivery.remove-new-order");
    TpccSql.bindOrder(orderCustomer, input.warehouse, district, order);
    int customer;
    try (ResultSet rows = orderCustomer.executeQuery()) {
      customer = TpccSql.requiredInt(rows, 1, "delivery order lookup");
    }
    setCarrier.setInt(1, input.carrier);
    setCarrier.setInt(2, input.warehouse);
    setCarrier.setInt(3, district);
    setCarrier.setInt(4, order);
    TpccSql.changedOne(setCarrier, "delivery.set-carrier");
    TpccSql.bindOrder(total, input.warehouse, district, order);
    BigDecimal amount;
    try (ResultSet rows = total.executeQuery()) {
      if (!rows.next() || (amount = rows.getBigDecimal(1)) == null) {
        throw new SQLException("delivery order has no lines");
      }
    }
    deliverLines.setTimestamp(1, input.date);
    deliverLines.setInt(2, input.warehouse);
    deliverLines.setInt(3, district);
    deliverLines.setInt(4, order);
    int changed = deliverLines.executeUpdate();
    if (changed < 5 || changed > 15) throw new SQLException("delivery changed " + changed + " lines");
    updateCustomer.setBigDecimal(1, amount);
    updateCustomer.setInt(2, input.warehouse);
    updateCustomer.setInt(3, district);
    updateCustomer.setInt(4, customer);
    TpccSql.changedOne(updateCustomer, "delivery.update-customer");
  }

  @Override
  public void close() throws SQLException {
    oldest.close(); remove.close(); orderCustomer.close(); setCarrier.close(); total.close();
    deliverLines.close(); updateCustomer.close();
  }
}
