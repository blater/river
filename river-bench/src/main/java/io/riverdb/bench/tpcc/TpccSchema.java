package io.riverdb.bench.tpcc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Standard TPC-C entities, composite constraints, and access indexes. */
final class TpccSchema {
  private static final String[] DDL = {
      "CREATE TABLE warehouse (w_id SMALLINT PRIMARY KEY,w_name VARCHAR(10) NOT NULL,w_street_1 VARCHAR(20) NOT NULL,w_street_2 VARCHAR(20) NOT NULL,w_city VARCHAR(20) NOT NULL,w_state CHAR(2) NOT NULL,w_zip CHAR(9) NOT NULL,w_tax DECIMAL(4,4) NOT NULL,w_ytd DECIMAL(12,2) NOT NULL)",
      "CREATE TABLE district (d_w_id SMALLINT NOT NULL,d_id SMALLINT NOT NULL,d_name VARCHAR(10) NOT NULL,d_street_1 VARCHAR(20) NOT NULL,d_street_2 VARCHAR(20) NOT NULL,d_city VARCHAR(20) NOT NULL,d_state CHAR(2) NOT NULL,d_zip CHAR(9) NOT NULL,d_tax DECIMAL(4,4) NOT NULL,d_ytd DECIMAL(12,2) NOT NULL,d_next_o_id INTEGER NOT NULL,PRIMARY KEY(d_w_id,d_id),FOREIGN KEY(d_w_id) REFERENCES warehouse(w_id))",
      "CREATE TABLE customer (c_w_id SMALLINT NOT NULL,c_d_id SMALLINT NOT NULL,c_id INTEGER NOT NULL,c_first VARCHAR(16) NOT NULL,c_middle CHAR(2) NOT NULL,c_last VARCHAR(16) NOT NULL,c_street_1 VARCHAR(20) NOT NULL,c_street_2 VARCHAR(20) NOT NULL,c_city VARCHAR(20) NOT NULL,c_state CHAR(2) NOT NULL,c_zip CHAR(9) NOT NULL,c_phone CHAR(16) NOT NULL,c_since TIMESTAMP(6) NOT NULL,c_credit CHAR(2) NOT NULL,c_discount DECIMAL(4,4) NOT NULL,c_credit_lim DECIMAL(12,2) NOT NULL,c_balance DECIMAL(12,2) NOT NULL,c_ytd_payment DECIMAL(12,2) NOT NULL,c_payment_cnt SMALLINT NOT NULL,c_delivery_cnt SMALLINT NOT NULL,c_data VARCHAR(500) NOT NULL,PRIMARY KEY(c_w_id,c_d_id,c_id),FOREIGN KEY(c_w_id,c_d_id) REFERENCES district(d_w_id,d_id))",
      "CREATE INDEX customer_name ON customer(c_w_id,c_d_id,c_last,c_first)",
      "CREATE TABLE history (h_c_id INTEGER NOT NULL,h_c_d_id SMALLINT NOT NULL,h_c_w_id SMALLINT NOT NULL,h_d_id SMALLINT NOT NULL,h_w_id SMALLINT NOT NULL,h_date TIMESTAMP(6) NOT NULL,h_amount DECIMAL(6,2) NOT NULL,h_data VARCHAR(24) NOT NULL,FOREIGN KEY(h_c_w_id,h_c_d_id,h_c_id) REFERENCES customer(c_w_id,c_d_id,c_id),FOREIGN KEY(h_w_id,h_d_id) REFERENCES district(d_w_id,d_id))",
      "CREATE TABLE item (i_id INTEGER PRIMARY KEY,i_im_id INTEGER NOT NULL,i_name VARCHAR(24) NOT NULL,i_price DECIMAL(5,2) NOT NULL,i_data VARCHAR(50) NOT NULL)",
      "CREATE TABLE stock (s_w_id SMALLINT NOT NULL,s_i_id INTEGER NOT NULL,s_quantity SMALLINT NOT NULL,s_dist_01 CHAR(24) NOT NULL,s_dist_02 CHAR(24) NOT NULL,s_dist_03 CHAR(24) NOT NULL,s_dist_04 CHAR(24) NOT NULL,s_dist_05 CHAR(24) NOT NULL,s_dist_06 CHAR(24) NOT NULL,s_dist_07 CHAR(24) NOT NULL,s_dist_08 CHAR(24) NOT NULL,s_dist_09 CHAR(24) NOT NULL,s_dist_10 CHAR(24) NOT NULL,s_ytd INTEGER NOT NULL,s_order_cnt SMALLINT NOT NULL,s_remote_cnt SMALLINT NOT NULL,s_data VARCHAR(50) NOT NULL,PRIMARY KEY(s_w_id,s_i_id),FOREIGN KEY(s_w_id) REFERENCES warehouse(w_id),FOREIGN KEY(s_i_id) REFERENCES item(i_id))",
      "CREATE TABLE orders (o_w_id SMALLINT NOT NULL,o_d_id SMALLINT NOT NULL,o_id INTEGER NOT NULL,o_c_id INTEGER NOT NULL,o_entry_d TIMESTAMP(6) NOT NULL,o_carrier_id SMALLINT,o_ol_cnt SMALLINT NOT NULL,o_all_local SMALLINT NOT NULL,PRIMARY KEY(o_w_id,o_d_id,o_id),FOREIGN KEY(o_w_id,o_d_id,o_c_id) REFERENCES customer(c_w_id,c_d_id,c_id))",
      "CREATE INDEX orders_customer ON orders(o_w_id,o_d_id,o_c_id,o_id)",
      "CREATE TABLE new_order (no_w_id SMALLINT NOT NULL,no_d_id SMALLINT NOT NULL,no_o_id INTEGER NOT NULL,PRIMARY KEY(no_w_id,no_d_id,no_o_id),FOREIGN KEY(no_w_id,no_d_id,no_o_id) REFERENCES orders(o_w_id,o_d_id,o_id))",
      "CREATE TABLE order_line (ol_w_id SMALLINT NOT NULL,ol_d_id SMALLINT NOT NULL,ol_o_id INTEGER NOT NULL,ol_number SMALLINT NOT NULL,ol_i_id INTEGER NOT NULL,ol_supply_w_id SMALLINT NOT NULL,ol_delivery_d TIMESTAMP(6),ol_quantity SMALLINT NOT NULL,ol_amount DECIMAL(6,2) NOT NULL,ol_dist_info CHAR(24) NOT NULL,PRIMARY KEY(ol_w_id,ol_d_id,ol_o_id,ol_number),FOREIGN KEY(ol_w_id,ol_d_id,ol_o_id) REFERENCES orders(o_w_id,o_d_id,o_id),FOREIGN KEY(ol_supply_w_id,ol_i_id) REFERENCES stock(s_w_id,s_i_id))",
      "CREATE INDEX order_line_item ON order_line(ol_w_id,ol_d_id,ol_i_id,ol_o_id)"
  };

  private TpccSchema() {}

  static void create(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (int index = 0; index < DDL.length; index++) {
        try {
          if (statement.executeUpdate(DDL[index]) != 0) throw new SQLException("DDL changed rows");
        } catch (SQLException failure) {
          throw new SQLException("fresh schema failed at statement " + (index + 1)
              + "; use a fresh database or --fresh-load=false", failure);
        }
      }
    }
  }
}
