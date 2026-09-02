package io.riverdb.bench.tpcc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reusable prepared statements for Stock-Level with COUNT DISTINCT. */
final class TpccStockLevel implements AutoCloseable {
  private final Connection connection;
  private final PreparedStatement nextOrder;
  private final PreparedStatement lowStock;

  TpccStockLevel(Connection connection) throws SQLException {
    this.connection = connection;
    nextOrder = connection.prepareStatement("SELECT d_next_o_id FROM district WHERE d_w_id=? AND d_id=?");
    lowStock = connection.prepareStatement("SELECT COUNT(DISTINCT s.s_i_id) FROM order_line ol INNER JOIN stock s ON s.s_w_id=ol.ol_w_id AND s.s_i_id=ol.ol_i_id WHERE ol.ol_w_id=? AND ol.ol_d_id=? AND ol.ol_o_id>=? AND ol.ol_o_id<? AND s.s_quantity<?");
  }

  boolean execute(TpccInputs.StockLevel input) throws SQLException {
    try {
      TpccSql.bindDistrict(nextOrder, input.warehouse, input.district);
      int next;
      try (ResultSet rows = nextOrder.executeQuery()) {
        next = TpccSql.requiredInt(rows, 1, "stock-level district lookup");
      }
      lowStock.setInt(1, input.warehouse);
      lowStock.setInt(2, input.district);
      lowStock.setInt(3, Math.max(1, next - 20));
      lowStock.setInt(4, next);
      lowStock.setInt(5, input.threshold);
      try (ResultSet rows = lowStock.executeQuery()) {
        int count = TpccSql.requiredInt(rows, 1, "stock-level aggregate");
        if (count < 0) throw new SQLException("negative stock-level result");
        if (rows.next()) throw new SQLException("stock-level aggregate returned extra row");
      }
      connection.commit();
      return true;
    } catch (SQLException failure) {
      connection.rollback();
      throw failure;
    }
  }

  @Override
  public void close() throws SQLException {
    nextOrder.close(); lowStock.close();
  }
}
