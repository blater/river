package io.riverdb.bench.tpcc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;

/** Stable bounded fingerprint carried across the externally owned restart. */
record TpccDatabaseIdentity(String digest) {
  static TpccDatabaseIdentity capture(Connection connection, TpccConfig config)
      throws SQLException {
    StringBuilder state = new StringBuilder(512 + config.warehouses() * config.districts() * 16);
    append(connection, state, "SELECT COUNT(*) FROM warehouse");
    append(connection, state, "SELECT COUNT(*) FROM customer");
    append(connection, state, "SELECT COUNT(*) FROM history");
    append(connection, state, "SELECT COUNT(*) FROM orders");
    append(connection, state, "SELECT COUNT(*) FROM new_order");
    append(connection, state, "SELECT COUNT(*) FROM order_line");
    for (int warehouse = 1; warehouse <= config.warehouses(); warehouse++) {
      append(connection, state, "SELECT w_ytd FROM warehouse WHERE w_id=" + warehouse);
      for (int district = 1; district <= config.districts(); district++) {
        append(connection, state, "SELECT d_next_o_id FROM district WHERE d_w_id="
            + warehouse + " AND d_id=" + district);
      }
    }
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256")
          .digest(state.toString().getBytes(StandardCharsets.UTF_8));
      return new TpccDatabaseIdentity(HexFormat.of().formatHex(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void append(Connection connection, StringBuilder target, String sql)
      throws SQLException {
    try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) throw new SQLException("database identity query returned no row");
      target.append(rows.getString(1)).append('|');
      if (rows.next()) throw new SQLException("database identity query returned extra row");
    }
  }
}
