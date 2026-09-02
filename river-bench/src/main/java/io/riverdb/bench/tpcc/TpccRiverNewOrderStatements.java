package io.riverdb.bench.tpcc;

import java.sql.SQLException;

/** Prepared SQL handles shared by all line-count variants of one New-Order adapter. */
final class TpccRiverNewOrderStatements {
  final long warehouse;
  final long district;
  final long advanceDistrict;
  final long customer;
  final long item;
  final long stock;
  final long updateStock;
  final long insertOrder;
  final long insertNewOrder;
  final long insertLine;

  TpccRiverNewOrderStatements(TpccRiverProgramResources resources, int districtId)
      throws SQLException {
    if (districtId < 1 || districtId > 10) {
      throw new SQLException("TPC-C district must be in [1,10]", "22003");
    }
    warehouse = resources.prepareStatement(
        "SELECT w_tax FROM warehouse WHERE w_id=?");
    district = resources.prepareStatement(
        "SELECT d_tax,d_next_o_id FROM district WHERE d_w_id=? AND d_id=? FOR UPDATE");
    advanceDistrict = resources.prepareStatement(
        "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=? AND d_id=?");
    customer = resources.prepareStatement(
        "SELECT c_discount,c_last,c_credit FROM customer "
            + "WHERE c_w_id=? AND c_d_id=? AND c_id=?");
    item = resources.prepareStatement(
        "SELECT i_price FROM item WHERE i_id=?");
    String districtColumn = districtId < 10 ? "0" + districtId : "10";
    stock = resources.prepareStatement(
        "SELECT s_quantity,s_dist_" + districtColumn
            + " FROM stock WHERE s_w_id=? AND s_i_id=? FOR UPDATE");
    updateStock = resources.prepareStatement(
        "UPDATE stock SET s_quantity=?,s_ytd=s_ytd+?,s_order_cnt=s_order_cnt+1,"
            + "s_remote_cnt=s_remote_cnt+? WHERE s_w_id=? AND s_i_id=?");
    insertOrder = resources.prepareStatement(
        "INSERT INTO orders VALUES (?,?,?,?,?,NULL,?,?)");
    insertNewOrder = resources.prepareStatement(
        "INSERT INTO new_order VALUES (?,?,?)");
    insertLine = resources.prepareStatement(
        "INSERT INTO order_line VALUES (?,?,?,?,?,?,NULL,?,?,?)");
  }
}
