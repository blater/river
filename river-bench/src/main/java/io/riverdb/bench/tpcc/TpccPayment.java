package io.riverdb.bench.tpcc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reusable prepared statements for Payment, including bad-credit history data. */
final class TpccPayment implements AutoCloseable {
  private final Connection connection;
  private final TpccCustomerLookup names;
  private final PreparedStatement warehouse;
  private final PreparedStatement district;
  private final PreparedStatement customer;
  private final PreparedStatement updateWarehouse;
  private final PreparedStatement updateDistrict;
  private final PreparedStatement updateCustomer;
  private final PreparedStatement updateBadCredit;
  private final PreparedStatement history;

  TpccPayment(Connection owner) throws SQLException {
    connection = owner;
    names = new TpccCustomerLookup(owner);
    warehouse = owner.prepareStatement("SELECT w_name FROM warehouse WHERE w_id=?");
    district = owner.prepareStatement("SELECT d_name FROM district WHERE d_w_id=? AND d_id=?");
    customer = owner.prepareStatement("SELECT c_credit,c_data FROM customer WHERE c_w_id=? AND c_d_id=? AND c_id=? FOR UPDATE");
    updateWarehouse = owner.prepareStatement("UPDATE warehouse SET w_ytd=w_ytd+? WHERE w_id=?");
    updateDistrict = owner.prepareStatement("UPDATE district SET d_ytd=d_ytd+? WHERE d_w_id=? AND d_id=?");
    updateCustomer = owner.prepareStatement("UPDATE customer SET c_balance=c_balance-?,c_ytd_payment=c_ytd_payment+?,c_payment_cnt=c_payment_cnt+1 WHERE c_w_id=? AND c_d_id=? AND c_id=?");
    updateBadCredit = owner.prepareStatement("UPDATE customer SET c_balance=c_balance-?,c_ytd_payment=c_ytd_payment+?,c_payment_cnt=c_payment_cnt+1,c_data=? WHERE c_w_id=? AND c_d_id=? AND c_id=?");
    history = owner.prepareStatement("INSERT INTO history VALUES (?,?,?,?,?,?,?,?)");
  }

  boolean execute(TpccInputs.Payment input) throws SQLException {
    try {
      updateWarehouseTotal(input);
      String warehouseName = warehouseName(input.warehouse);
      updateDistrictTotal(input);
      String districtName = districtName(input.warehouse, input.district);
      int customerId = input.customer == 0
          ? names.find(input.customerWarehouse, input.customerDistrict, input.last)
          : input.customer;
      updateCustomer(input, customerId);
      insertHistory(input, customerId, warehouseName + "    " + districtName);
      connection.commit();
      return true;
    } catch (SQLException failure) {
      connection.rollback();
      throw failure;
    }
  }

  private String warehouseName(int warehouseId) throws SQLException {
    warehouse.setInt(1, warehouseId);
    try (ResultSet rows = warehouse.executeQuery()) {
      if (!rows.next()) throw new SQLException("warehouse lookup returned no row");
      return rows.getString(1);
    }
  }

  private String districtName(int warehouseId, int districtId) throws SQLException {
    TpccSql.bindDistrict(district, warehouseId, districtId);
    try (ResultSet rows = district.executeQuery()) {
      if (!rows.next()) throw new SQLException("district lookup returned no row");
      return rows.getString(1);
    }
  }

  private void updateWarehouseTotal(TpccInputs.Payment input) throws SQLException {
    updateWarehouse.setBigDecimal(1, input.amount);
    updateWarehouse.setInt(2, input.warehouse);
    TpccSql.changedOne(updateWarehouse, "payment.update-warehouse");
  }

  private void updateDistrictTotal(TpccInputs.Payment input) throws SQLException {
    updateDistrict.setBigDecimal(1, input.amount);
    updateDistrict.setInt(2, input.warehouse);
    updateDistrict.setInt(3, input.district);
    TpccSql.changedOne(updateDistrict, "payment.update-district");
  }

  private void updateCustomer(TpccInputs.Payment input, int customerId) throws SQLException {
    customer.setInt(1, input.customerWarehouse);
    customer.setInt(2, input.customerDistrict);
    customer.setInt(3, customerId);
    String credit;
    String data;
    try (ResultSet rows = customer.executeQuery()) {
      if (!rows.next()) throw new SQLException("customer lookup returned no row");
      credit = rows.getString(1);
      data = rows.getString(2);
    }
    if ("BC".equals(credit)) {
      String prefix = customerId + " " + input.customerDistrict + " "
          + input.customerWarehouse + " " + input.district + " " + input.warehouse
          + " " + input.amount + " | ";
      bindCustomerUpdate(updateBadCredit, input);
      updateBadCredit.setString(3, (prefix + data).substring(0, Math.min(500, prefix.length() + data.length())));
      updateBadCredit.setInt(4, input.customerWarehouse);
      updateBadCredit.setInt(5, input.customerDistrict);
      updateBadCredit.setInt(6, customerId);
      TpccSql.changedOne(updateBadCredit, "payment.update-bad-credit-customer");
    } else {
      bindCustomerUpdate(updateCustomer, input);
      updateCustomer.setInt(3, input.customerWarehouse);
      updateCustomer.setInt(4, input.customerDistrict);
      updateCustomer.setInt(5, customerId);
      TpccSql.changedOne(updateCustomer, "payment.update-customer");
    }
  }

  private static void bindCustomerUpdate(
      PreparedStatement statement, TpccInputs.Payment input) throws SQLException {
    statement.setBigDecimal(1, input.amount);
    statement.setBigDecimal(2, input.amount);
  }

  private void insertHistory(TpccInputs.Payment input, int customerId, String data)
      throws SQLException {
    history.setInt(1, customerId);
    history.setInt(2, input.customerDistrict);
    history.setInt(3, input.customerWarehouse);
    history.setInt(4, input.district);
    history.setInt(5, input.warehouse);
    history.setTimestamp(6, input.date);
    history.setBigDecimal(7, input.amount);
    history.setString(8, data.substring(0, Math.min(24, data.length())));
    TpccSql.changedOne(history, "payment.insert-history");
  }

  @Override
  public void close() throws SQLException {
    names.close(); warehouse.close(); district.close(); customer.close(); updateWarehouse.close();
    updateDistrict.close(); updateCustomer.close(); updateBadCredit.close(); history.close();
  }
}
