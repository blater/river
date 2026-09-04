package io.riverdb.jdbc;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JDBC semantic gate for the standard TPC-C relational and transaction shapes. */
final class RiverTpccJdbcAcceptanceTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x545043434A444243L, 0x4143434550543031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final String WAREHOUSE_DDL =
      "CREATE TABLE warehouse (w_id SMALLINT PRIMARY KEY,w_name VARCHAR(10) NOT NULL,"
          + "w_street_1 VARCHAR(20) NOT NULL,w_street_2 VARCHAR(20) NOT NULL,"
          + "w_city VARCHAR(20) NOT NULL,w_state CHAR(2) NOT NULL,w_zip CHAR(9) NOT NULL,"
          + "w_tax DECIMAL(4,4) NOT NULL,w_ytd DECIMAL(12,2) NOT NULL)";

  @Test
  void loadsWarehouseRowsThroughPreparedJdbcBatch(@TempDir Path root) throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(
        databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    try (Connection connection = DriverManager.getConnection(url(server));
        Statement ddl = connection.createStatement()) {
      assertEquals(0, ddl.executeUpdate(WAREHOUSE_DDL));
      connection.setAutoCommit(false);
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO warehouse VALUES (?,?,?,?,?,?,?,?,?)")) {
        bindWarehouse(insert, 1, "first");
        insert.addBatch();
        bindWarehouse(insert, 2, "second");
        insert.addBatch();
        assertArrayEquals(new int[] {1, 1}, insert.executeBatch());
      }
      connection.commit();
      try (ResultSet rows = ddl.executeQuery("SELECT w_id FROM warehouse ORDER BY w_id")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1));
        assertFalse(rows.next());
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void executesFiveTransactionFamiliesOverCompositeAndKeylessSchema(@TempDir Path root)
      throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(
        databaseRequest(16), root, DATABASE, GENERATION, 16, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    try (Connection connection = DriverManager.getConnection(url(server))) {
      createSchema(connection);
      seed(connection);
      newOrder(connection);
      newOrderIntentionalRollback(connection);
      paymentByLastName(connection);
      orderStatus(connection);
      delivery(connection);
      stockLevel(connection);
      assertBusinessInvariants(connection);
      try (Statement statement = connection.createStatement()) {
        assertEquals(0, statement.executeUpdate("CHECKPOINT"));
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
    opened.reset();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(
            databaseRequest(16), root, DATABASE, GENERATION, 16, opened));
    database = opened.database();
    server = start(database);
    try (Connection connection = DriverManager.getConnection(url(server))) {
      assertBusinessInvariants(connection);
      stockLevel(connection);
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void lockWaitTimeoutDoesNotPoisonTheExplicitJdbcTransaction(@TempDir Path root)
      throws Exception {
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.tx.lock-wait-timeout=20ms\n",
        StandardCharsets.UTF_8);
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(
        databaseRequest(16), root, DATABASE, GENERATION, 16, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    try (Connection first = DriverManager.getConnection(url(server));
        Connection second = DriverManager.getConnection(url(server))) {
      createSchema(first);
      seed(first);
      first.setAutoCommit(false);
      second.setAutoCommit(false);
      assertEquals(2, lockedNextOrder(first));
      SQLException timeout = assertThrows(SQLException.class, () -> lockedNextOrder(second));
      assertEquals("HYT00", timeout.getSQLState());
      try (PreparedStatement disjoint = second.prepareStatement(
          "UPDATE customer SET c_delivery_cnt=c_delivery_cnt+1 "
              + "WHERE c_w_id=? AND c_d_id=? AND c_id=?")) {
        bindTriple(disjoint, 1, 1, 2);
        assertEquals(1, disjoint.executeUpdate());
      }
      second.rollback();
      first.commit();
      assertEquals(2, lockedNextOrder(second));
      second.rollback();
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void opposingCompositeKeyLocksReportOneDeadlockAndGrantTheSurvivor(@TempDir Path root)
      throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(
        databaseRequest(16), root, DATABASE, GENERATION, 16, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    try (Connection first = DriverManager.getConnection(url(server));
        Connection second = DriverManager.getConnection(url(server))) {
      createSchema(first);
      seed(first);
      RiverTransactionDiagnostics firstDiagnostics =
          first.unwrap(RiverTransactionDiagnostics.class);
      RiverTransactionDiagnostics secondDiagnostics =
          second.unwrap(RiverTransactionDiagnostics.class);
      firstDiagnostics.beginDiagnosticAttempt(101, 1);
      secondDiagnostics.beginDiagnosticAttempt(202, 1);
      try (Statement statement = first.createStatement()) {
        assertEquals(1, statement.executeUpdate(
            "INSERT INTO district VALUES "
                + "(1,2,'district','one','two','london','LN','123456789',"
                + "0.0500,30000.00,2)"));
      }
      try (PreparedStatement firstLock = first.prepareStatement(
              "SELECT d_next_o_id FROM district WHERE d_w_id=? AND d_id=? FOR UPDATE");
          PreparedStatement secondLock = second.prepareStatement(
              "SELECT d_next_o_id FROM district WHERE d_w_id=? AND d_id=? FOR UPDATE")) {
        first.setAutoCommit(false);
        second.setAutoCommit(false);
        lockDistrict(firstLock, 1);
        lockDistrict(secondLock, 2);
        boolean firstDeadlock;
        boolean secondDeadlock;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
          Future<Boolean> firstResult = executor.submit(
              () -> opposingDistrictLock(first, firstLock, 2));
          Future<Boolean> secondResult = executor.submit(
              () -> opposingDistrictLock(second, secondLock, 1));
          firstDeadlock = result(firstResult);
          secondDeadlock = result(secondResult);
        } finally {
          executor.shutdownNow();
        }
        assertTrue(firstDeadlock ^ secondDeadlock);
        lockDistrict(firstLock, 1);
        lockDistrict(firstLock, 2);
        first.rollback();
        bindPair(firstLock, 1, 99);
        try (ResultSet absent = firstLock.executeQuery()) {
          assertFalse(absent.next());
        }
        first.rollback();
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      ddl(statement, WAREHOUSE_DDL);
      ddl(statement,
          "CREATE TABLE district (d_w_id SMALLINT NOT NULL,d_id SMALLINT NOT NULL,"
              + "d_name VARCHAR(10) NOT NULL,d_street_1 VARCHAR(20) NOT NULL,"
              + "d_street_2 VARCHAR(20) NOT NULL,d_city VARCHAR(20) NOT NULL,"
              + "d_state CHAR(2) NOT NULL,d_zip CHAR(9) NOT NULL,"
              + "d_tax DECIMAL(4,4) NOT NULL,"
              + "d_ytd DECIMAL(12,2) NOT NULL,d_next_o_id INTEGER NOT NULL,"
              + "PRIMARY KEY(d_w_id,d_id),"
              + "FOREIGN KEY(d_w_id) REFERENCES warehouse(w_id))");
      ddl(statement,
          "CREATE TABLE customer (c_w_id SMALLINT NOT NULL,c_d_id SMALLINT NOT NULL,"
              + "c_id INTEGER NOT NULL,c_first VARCHAR(16) NOT NULL,"
              + "c_middle CHAR(2) NOT NULL,c_last VARCHAR(16) NOT NULL,"
              + "c_street_1 VARCHAR(20) NOT NULL,c_street_2 VARCHAR(20) NOT NULL,"
              + "c_city VARCHAR(20) NOT NULL,c_state CHAR(2) NOT NULL,c_zip CHAR(9) NOT NULL,"
              + "c_phone CHAR(16) NOT NULL,c_since TIMESTAMP(6) NOT NULL,"
              + "c_credit CHAR(2) NOT NULL,c_discount DECIMAL(4,4) NOT NULL,"
              + "c_credit_lim DECIMAL(12,2) NOT NULL,"
              + "c_balance DECIMAL(12,2) NOT NULL,c_ytd_payment DECIMAL(12,2) NOT NULL,"
              + "c_payment_cnt SMALLINT NOT NULL,c_delivery_cnt SMALLINT NOT NULL,"
              + "c_data VARCHAR(500) NOT NULL,PRIMARY KEY(c_w_id,c_d_id,c_id),"
              + "FOREIGN KEY(c_w_id,c_d_id) REFERENCES district(d_w_id,d_id))");
      ddl(statement,
          "CREATE INDEX customer_name ON customer(c_w_id,c_d_id,c_last,c_first)");
      ddl(statement,
          "CREATE TABLE history (h_c_id INTEGER NOT NULL,h_c_d_id SMALLINT NOT NULL,"
              + "h_c_w_id SMALLINT NOT NULL,h_d_id SMALLINT NOT NULL,h_w_id SMALLINT NOT NULL,"
              + "h_date TIMESTAMP(6) NOT NULL,h_amount DECIMAL(6,2) NOT NULL,"
              + "h_data VARCHAR(24) NOT NULL,"
              + "FOREIGN KEY(h_c_w_id,h_c_d_id,h_c_id) "
              + "REFERENCES customer(c_w_id,c_d_id,c_id),"
              + "FOREIGN KEY(h_w_id,h_d_id) REFERENCES district(d_w_id,d_id))");
      ddl(statement,
          "CREATE TABLE item (i_id INTEGER PRIMARY KEY,i_im_id INTEGER NOT NULL,"
              + "i_name VARCHAR(24) NOT NULL,"
              + "i_price DECIMAL(5,2) NOT NULL,i_data VARCHAR(50) NOT NULL)");
      ddl(statement,
          "CREATE TABLE stock (s_w_id SMALLINT NOT NULL,s_i_id INTEGER NOT NULL,"
              + "s_quantity SMALLINT NOT NULL,s_ytd INTEGER NOT NULL,"
              + "s_order_cnt SMALLINT NOT NULL,s_remote_cnt SMALLINT NOT NULL,"
              + "s_dist_01 CHAR(24) NOT NULL,s_dist_02 CHAR(24) NOT NULL,"
              + "s_dist_03 CHAR(24) NOT NULL,s_dist_04 CHAR(24) NOT NULL,"
              + "s_dist_05 CHAR(24) NOT NULL,s_dist_06 CHAR(24) NOT NULL,"
              + "s_dist_07 CHAR(24) NOT NULL,s_dist_08 CHAR(24) NOT NULL,"
              + "s_dist_09 CHAR(24) NOT NULL,s_dist_10 CHAR(24) NOT NULL,"
              + "s_data VARCHAR(50) NOT NULL,PRIMARY KEY(s_w_id,s_i_id),"
              + "FOREIGN KEY(s_w_id) REFERENCES warehouse(w_id),"
              + "FOREIGN KEY(s_i_id) REFERENCES item(i_id))");
      ddl(statement,
          "CREATE TABLE orders (o_w_id SMALLINT NOT NULL,o_d_id SMALLINT NOT NULL,"
              + "o_id INTEGER NOT NULL,o_c_id INTEGER NOT NULL,o_entry_d TIMESTAMP(6) NOT NULL,"
              + "o_carrier_id SMALLINT,o_ol_cnt SMALLINT NOT NULL,o_all_local SMALLINT NOT NULL,"
              + "PRIMARY KEY(o_w_id,o_d_id,o_id),"
              + "FOREIGN KEY(o_w_id,o_d_id,o_c_id) REFERENCES customer(c_w_id,c_d_id,c_id))");
      ddl(statement, "CREATE INDEX orders_customer ON orders(o_w_id,o_d_id,o_c_id,o_id)");
      ddl(statement,
          "CREATE TABLE new_order (no_w_id SMALLINT NOT NULL,no_d_id SMALLINT NOT NULL,"
              + "no_o_id INTEGER NOT NULL,PRIMARY KEY(no_w_id,no_d_id,no_o_id),"
              + "FOREIGN KEY(no_w_id,no_d_id,no_o_id) REFERENCES orders(o_w_id,o_d_id,o_id))");
      ddl(statement,
          "CREATE TABLE order_line (ol_w_id SMALLINT NOT NULL,ol_d_id SMALLINT NOT NULL,"
              + "ol_o_id INTEGER NOT NULL,ol_number SMALLINT NOT NULL,ol_i_id INTEGER NOT NULL,"
              + "ol_supply_w_id SMALLINT NOT NULL,ol_delivery_d TIMESTAMP(6),"
              + "ol_quantity SMALLINT NOT NULL,ol_amount DECIMAL(6,2) NOT NULL,"
              + "ol_dist_info CHAR(24) NOT NULL,"
              + "PRIMARY KEY(ol_w_id,ol_d_id,ol_o_id,ol_number),"
              + "FOREIGN KEY(ol_w_id,ol_d_id,ol_o_id) REFERENCES orders(o_w_id,o_d_id,o_id),"
              + "FOREIGN KEY(ol_supply_w_id,ol_i_id) REFERENCES stock(s_w_id,s_i_id))");
      ddl(statement, "CREATE INDEX order_line_item ON order_line(ol_w_id,ol_d_id,ol_i_id,ol_o_id)");
    }
  }

  private static void seed(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      assertEquals(1, statement.executeUpdate(
          "INSERT INTO warehouse VALUES "
              + "(1,'warehouse','one','two','london','LN','123456789',0.1000,300000.00)"));
      assertEquals(1, statement.executeUpdate(
          "INSERT INTO district VALUES "
              + "(1,1,'district','one','two','london','LN','123456789',0.0500,30000.00,2)"));
      assertEquals(2, statement.executeUpdate(
          "INSERT INTO customer VALUES "
              + "(1,1,1,'ALICE','OE','SMITH','one','two','london','LN','123456789',"
              + "'1234567890123456',TIMESTAMP '2026-08-27 10:00:00','GC',0.1000,"
              + "50000.00,0.00,0.00,0,0,''),"
              + "(1,1,2,'BOB','OE','SMITH','one','two','london','LN','123456789',"
              + "'1234567890123456',TIMESTAMP '2026-08-27 10:00:00','GC',0.0500,"
              + "50000.00,0.00,0.00,0,0,'')"));
      assertEquals(1, statement.executeUpdate(
          "INSERT INTO item VALUES (1,1,'widget',10.00,'ORIGINAL')"));
      assertEquals(1, statement.executeUpdate(
          "INSERT INTO stock VALUES (1,1,100,0,0,0,"
              + "'district-one-info-line','district-two-info-line',"
              + "'district-03-info-line','district-04-info-line',"
              + "'district-05-info-line','district-06-info-line',"
              + "'district-07-info-line','district-08-info-line',"
              + "'district-09-info-line','district-10-info-line','ORIGINAL')"));
      assertEquals(1, statement.executeUpdate(
          "INSERT INTO orders VALUES (1,1,1,1,TIMESTAMP '2026-08-27 10:00:00',NULL,1,1)"));
      assertEquals(1, statement.executeUpdate("INSERT INTO new_order VALUES (1,1,1)"));
      assertEquals(1, statement.executeUpdate(
          "INSERT INTO order_line VALUES "
              + "(1,1,1,1,1,1,NULL,5,10.00,'district-one-info-line')"));
      assertEquals(2, statement.executeUpdate(
          "INSERT INTO history VALUES "
              + "(1,1,1,1,1,TIMESTAMP '2026-08-27 10:00:00',1.00,'seed'),"
              + "(1,1,1,1,1,TIMESTAMP '2026-08-27 10:00:00',1.00,'seed')"));
    }
  }

  private static void newOrder(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try (PreparedStatement district = connection.prepareStatement(
            "SELECT d_next_o_id,d_tax FROM district WHERE d_w_id=? AND d_id=? FOR UPDATE");
        PreparedStatement advance = connection.prepareStatement(
            "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=? AND d_id=?");
        PreparedStatement stock = connection.prepareStatement(
            "SELECT s_quantity,s_ytd,s_order_cnt,s_remote_cnt FROM stock "
                + "WHERE s_w_id=? AND s_i_id=? FOR UPDATE");
        PreparedStatement updateStock = connection.prepareStatement(
            "UPDATE stock SET s_quantity=?,s_ytd=s_ytd+?,s_order_cnt=s_order_cnt+1 "
                + "WHERE s_w_id=? AND s_i_id=?");
        PreparedStatement order = connection.prepareStatement(
            "INSERT INTO orders VALUES (?,?,?,?,?,NULL,?,?)");
        PreparedStatement pending = connection.prepareStatement(
            "INSERT INTO new_order VALUES (?,?,?)");
        PreparedStatement line = connection.prepareStatement(
            "INSERT INTO order_line VALUES (?,?,?,?,?,?,NULL,?,?,?)")) {
      bindPair(district, 1, 1);
      try (ResultSet rows = district.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1));
        assertEquals(new BigDecimal("0.0500"), rows.getBigDecimal(2));
        assertFalse(rows.next());
      }
      bindPair(advance, 1, 1);
      assertEquals(1, advance.executeUpdate());
      bindPair(stock, 1, 1);
      try (ResultSet rows = stock.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(100, rows.getInt(1));
        assertFalse(rows.next());
      }
      updateStock.setInt(1, 95);
      updateStock.setInt(2, 5);
      updateStock.setInt(3, 1);
      updateStock.setInt(4, 1);
      assertEquals(1, updateStock.executeUpdate());
      order.setInt(1, 1);
      order.setInt(2, 1);
      order.setInt(3, 2);
      order.setInt(4, 1);
      order.setTimestamp(5, Timestamp.valueOf("2026-08-27 11:00:00"));
      order.setInt(6, 1);
      order.setInt(7, 1);
      assertEquals(1, order.executeUpdate());
      bindTriple(pending, 1, 1, 2);
      assertEquals(1, pending.executeUpdate());
      line.setInt(1, 1);
      line.setInt(2, 1);
      line.setInt(3, 2);
      line.setInt(4, 1);
      line.setInt(5, 1);
      line.setInt(6, 1);
      line.setInt(7, 5);
      line.setBigDecimal(8, new BigDecimal("50.00"));
      line.setString(9, "district-two-info-line");
      assertEquals(1, line.executeUpdate());
      connection.commit();
    } catch (SQLException failure) {
      connection.rollback();
      throw failure;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  private static void paymentByLastName(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try (PreparedStatement customers = connection.prepareStatement(
            "SELECT c_id,c_first FROM customer "
                + "WHERE c_w_id=? AND c_d_id=? AND c_last=? ORDER BY c_first");
        PreparedStatement warehouse = connection.prepareStatement(
            "UPDATE warehouse SET w_ytd=w_ytd+? WHERE w_id=?");
        PreparedStatement district = connection.prepareStatement(
            "UPDATE district SET d_ytd=d_ytd+? WHERE d_w_id=? AND d_id=?");
        PreparedStatement customer = connection.prepareStatement(
            "UPDATE customer SET c_balance=c_balance-?,"
                + "c_ytd_payment=c_ytd_payment+?,c_payment_cnt=c_payment_cnt+1 "
                + "WHERE c_w_id=? AND c_d_id=? AND c_id=?");
        PreparedStatement history = connection.prepareStatement(
            "INSERT INTO history VALUES (?,?,?,?,?,?,?,?)")) {
      customers.setInt(1, 1);
      customers.setInt(2, 1);
      customers.setString(3, "SMITH");
      int selected = 0;
      try (ResultSet rows = customers.executeQuery()) {
        assertTrue(rows.next());
        selected = rows.getInt(1);
        assertEquals("ALICE", rows.getString(2));
        assertTrue(rows.next());
        assertFalse(rows.next());
      }
      warehouse.setBigDecimal(1, new BigDecimal("5.00"));
      warehouse.setInt(2, 1);
      assertEquals(1, warehouse.executeUpdate());
      district.setBigDecimal(1, new BigDecimal("5.00"));
      district.setInt(2, 1);
      district.setInt(3, 1);
      assertEquals(1, district.executeUpdate());
      customer.setBigDecimal(1, new BigDecimal("5.00"));
      customer.setBigDecimal(2, new BigDecimal("5.00"));
      customer.setInt(3, 1);
      customer.setInt(4, 1);
      customer.setInt(5, selected);
      assertEquals(1, customer.executeUpdate());
      history.setInt(1, selected);
      history.setInt(2, 1);
      history.setInt(3, 1);
      history.setInt(4, 1);
      history.setInt(5, 1);
      history.setTimestamp(6, Timestamp.valueOf("2026-08-27 12:00:00"));
      history.setBigDecimal(7, new BigDecimal("5.00"));
      history.setString(8, "warehouse district");
      assertEquals(1, history.executeUpdate());
      connection.commit();
    } catch (SQLException failure) {
      connection.rollback();
      throw failure;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  private static void newOrderIntentionalRollback(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    boolean rejected = false;
    try (Statement statement = connection.createStatement()) {
      assertEquals(1, statement.executeUpdate(
          "UPDATE district SET d_next_o_id=d_next_o_id+1 WHERE d_w_id=1 AND d_id=1"));
      assertEquals(1, statement.executeUpdate(
          "INSERT INTO orders VALUES "
              + "(1,1,3,1,TIMESTAMP '2026-08-27 11:30:00',NULL,1,1)"));
      assertEquals(1, statement.executeUpdate("INSERT INTO new_order VALUES (1,1,3)"));
      try {
        statement.executeUpdate(
            "INSERT INTO order_line VALUES "
                + "(1,1,3,1,999,1,NULL,5,50.00,'invalid-item-info-line')");
      } catch (SQLException expected) {
        assertEquals("23503", expected.getSQLState());
        rejected = true;
      }
      assertTrue(rejected);
      connection.rollback();
    } finally {
      connection.setAutoCommit(true);
    }
    try (Statement statement = connection.createStatement()) {
      assertScalar(statement, "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=1", 3);
      assertScalar(statement, "SELECT COUNT(*) FROM orders WHERE o_id=3", 0);
      assertScalar(statement, "SELECT COUNT(*) FROM new_order WHERE no_o_id=3", 0);
    }
  }

  private static void orderStatus(Connection connection) throws SQLException {
    try (PreparedStatement customer = connection.prepareStatement(
            "SELECT c_id,c_balance FROM customer "
                + "WHERE c_w_id=? AND c_d_id=? AND c_last=? ORDER BY c_first");
        PreparedStatement order = connection.prepareStatement(
            "SELECT o_id,o_entry_d,o_carrier_id FROM orders "
                + "WHERE o_w_id=? AND o_d_id=? AND o_c_id=? ORDER BY o_id DESC LIMIT 1");
        PreparedStatement lines = connection.prepareStatement(
            "SELECT ol_i_id,ol_supply_w_id,ol_quantity,ol_amount,ol_delivery_d "
                + "FROM order_line WHERE ol_w_id=? AND ol_d_id=? AND ol_o_id=? "
                + "ORDER BY ol_number")) {
      customer.setInt(1, 1);
      customer.setInt(2, 1);
      customer.setString(3, "SMITH");
      int customerId;
      try (ResultSet rows = customer.executeQuery()) {
        assertTrue(rows.next());
        customerId = rows.getInt(1);
      }
      bindTriple(order, 1, 1, customerId);
      int orderId;
      try (ResultSet rows = order.executeQuery()) {
        assertTrue(rows.next());
        orderId = rows.getInt(1);
        assertEquals(2, orderId);
        assertFalse(rows.next());
      }
      bindTriple(lines, 1, 1, orderId);
      try (ResultSet rows = lines.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
        assertFalse(rows.next());
      }
    }
  }

  private static void delivery(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try (PreparedStatement oldest = connection.prepareStatement(
            "SELECT no_o_id FROM new_order WHERE no_w_id=? AND no_d_id=? "
                + "ORDER BY no_o_id LIMIT 1 FOR UPDATE");
        PreparedStatement remove = connection.prepareStatement(
            "DELETE FROM new_order WHERE no_w_id=? AND no_d_id=? AND no_o_id=?");
        PreparedStatement order = connection.prepareStatement(
            "SELECT o_c_id FROM orders WHERE o_w_id=? AND o_d_id=? AND o_id=?");
        PreparedStatement carrier = connection.prepareStatement(
            "UPDATE orders SET o_carrier_id=? WHERE o_w_id=? AND o_d_id=? AND o_id=?");
        PreparedStatement total = connection.prepareStatement(
            "SELECT SUM(ol_amount) FROM order_line "
                + "WHERE ol_w_id=? AND ol_d_id=? AND ol_o_id=?");
        PreparedStatement delivered = connection.prepareStatement(
            "UPDATE order_line SET ol_delivery_d=? "
                + "WHERE ol_w_id=? AND ol_d_id=? AND ol_o_id=?");
        PreparedStatement customer = connection.prepareStatement(
            "UPDATE customer SET c_balance=c_balance+?,c_delivery_cnt=c_delivery_cnt+1 "
                + "WHERE c_w_id=? AND c_d_id=? AND c_id=?")) {
      bindPair(oldest, 1, 1);
      int orderId;
      try (ResultSet rows = oldest.executeQuery()) {
        assertTrue(rows.next());
        orderId = rows.getInt(1);
        assertEquals(1, orderId);
      }
      bindTriple(remove, 1, 1, orderId);
      assertEquals(1, remove.executeUpdate());
      bindTriple(order, 1, 1, orderId);
      int customerId;
      try (ResultSet rows = order.executeQuery()) {
        assertTrue(rows.next());
        customerId = rows.getInt(1);
      }
      carrier.setInt(1, 7);
      carrier.setInt(2, 1);
      carrier.setInt(3, 1);
      carrier.setInt(4, orderId);
      assertEquals(1, carrier.executeUpdate());
      bindTriple(total, 1, 1, orderId);
      BigDecimal amount;
      try (ResultSet rows = total.executeQuery()) {
        assertTrue(rows.next());
        amount = rows.getBigDecimal(1);
        assertEquals(new BigDecimal("10.00"), amount);
      }
      delivered.setTimestamp(1, Timestamp.valueOf("2026-08-27 13:00:00"));
      delivered.setInt(2, 1);
      delivered.setInt(3, 1);
      delivered.setInt(4, orderId);
      assertEquals(1, delivered.executeUpdate());
      customer.setBigDecimal(1, amount);
      customer.setInt(2, 1);
      customer.setInt(3, 1);
      customer.setInt(4, customerId);
      assertEquals(1, customer.executeUpdate());
      connection.commit();
    } catch (SQLException failure) {
      connection.rollback();
      throw failure;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  private static void stockLevel(Connection connection) throws SQLException {
    try (PreparedStatement next = connection.prepareStatement(
            "SELECT d_next_o_id FROM district WHERE d_w_id=? AND d_id=?");
        PreparedStatement low = connection.prepareStatement(
            "SELECT COUNT(DISTINCT s.s_i_id) FROM order_line ol "
                + "INNER JOIN stock s ON s.s_w_id=ol.ol_supply_w_id AND s.s_i_id=ol.ol_i_id "
                + "WHERE ol.ol_w_id=? AND ol.ol_d_id=? AND ol.ol_o_id>=? "
                + "AND ol.ol_o_id<? AND s.s_quantity<?")) {
      bindPair(next, 1, 1);
      int nextOrder;
      try (ResultSet rows = next.executeQuery()) {
        assertTrue(rows.next());
        nextOrder = rows.getInt(1);
      }
      low.setInt(1, 1);
      low.setInt(2, 1);
      low.setInt(3, Math.max(1, nextOrder - 20));
      low.setInt(4, nextOrder);
      low.setInt(5, 96);
      try (ResultSet rows = low.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
        boolean extra = rows.next();
        assertFalse(extra, extra ? "unexpected second aggregate row " + rows.getInt(1) : "");
      }
    }
  }

  private static void assertBusinessInvariants(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      assertScalar(statement, "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=1", 3);
      assertScalar(statement, "SELECT s_quantity FROM stock WHERE s_w_id=1 AND s_i_id=1", 95);
      assertScalar(statement, "SELECT COUNT(*) FROM history", 3);
      assertScalar(statement, "SELECT COUNT(*) FROM new_order", 1);
      assertScalar(statement, "SELECT COUNT(*) FROM orders", 2);
      assertScalar(statement, "SELECT COUNT(*) FROM order_line", 2);
    }
  }

  private static void assertScalar(Statement statement, String sql, int expected)
      throws SQLException {
    try (ResultSet rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      assertEquals(expected, rows.getInt(1));
      assertFalse(rows.next());
    }
  }

  private static int lockedNextOrder(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
            "SELECT d_next_o_id FROM district WHERE d_w_id=? AND d_id=? FOR UPDATE")) {
      bindPair(statement, 1, 1);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        int next = rows.getInt(1);
        assertFalse(rows.next());
        return next;
      }
    }
  }

  private static boolean opposingDistrictLock(
      Connection connection, PreparedStatement statement, int district) throws SQLException {
    try {
      lockDistrict(statement, district);
      connection.rollback();
      return false;
    } catch (SQLException failure) {
      connection.rollback();
      if (!"40001".equals(failure.getSQLState())) throw failure;
      return true;
    }
  }

  private static boolean result(Future<Boolean> result) throws Exception {
    try {
      return result.get();
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception exception) throw exception;
      throw failure;
    }
  }

  private static void lockDistrict(PreparedStatement statement, int district)
      throws SQLException {
    bindPair(statement, 1, district);
    try (ResultSet rows = statement.executeQuery()) {
      assertTrue(rows.next());
      assertFalse(rows.next());
    }
  }

  private static void ddl(Statement statement, String sql) throws SQLException {
    assertEquals(0, statement.executeUpdate(sql));
  }

  private static void bindPair(PreparedStatement statement, int first, int second)
      throws SQLException {
    statement.setInt(1, first);
    statement.setInt(2, second);
  }

  private static void bindTriple(
      PreparedStatement statement, int first, int second, int third) throws SQLException {
    bindPair(statement, first, second);
    statement.setInt(3, third);
  }

  private static void bindWarehouse(
      PreparedStatement statement, int id, String name) throws SQLException {
    statement.setShort(1, (short) id);
    statement.setString(2, name);
    statement.setString(3, "one");
    statement.setString(4, "two");
    statement.setString(5, "london");
    statement.setString(6, "LN");
    statement.setString(7, "123456789");
    statement.setBigDecimal(8, new BigDecimal("0.1000"));
    statement.setBigDecimal(9, new BigDecimal("300000.00"));
  }

  private static LoopbackRiverServer start(RiverDatabase database) {
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
    assertEquals(StatusCode.OK, LoopbackRiverServer.start(database, 0, result));
    return result.server();
  }

  private static String url(LoopbackRiverServer server) {
    return RiverDriver.URL_PREFIX + server.port();
  }
}
