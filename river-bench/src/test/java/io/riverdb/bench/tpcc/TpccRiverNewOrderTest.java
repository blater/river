package io.riverdb.bench.tpcc;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
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
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.jdbc.RiverConnectionMetrics;
import io.riverdb.jdbc.RiverTransactionPrograms;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TpccRiverNewOrderTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(8_301, 8_303);
  private static final int ITEMS = 15;

  @Test
  void acquiresNewOrderSourceBeforeReadingCustomer() throws Exception {
    ProgramRecorder programs = new ProgramRecorder();
    Connection connection = (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
        (ignored, method, arguments) -> method.getName().equals("unwrap") ? programs : null);

    try (TpccRiverNewOrder transaction = new TpccRiverNewOrder(connection, 1, ITEMS)) {
      assertEquals(List.of(
          "warehouse", "district", "advance-district",
          "reserve-new-order", "insert-order", "insert-new-order", "customer"),
          programs.firstHeader);
      assertEquals(0, transaction.failureCount(0));
    }
  }

  @Test
  void canonicalStockOrderPreservesOriginalLineMetadata() throws Exception {
    TpccInputs.NewOrder input = input(1, 5, 1);
    input.item[0] = 5;
    input.item[1] = 2;
    input.item[2] = 4;
    input.item[3] = 1;
    input.item[4] = 3;
    input.supplyWarehouse[0] = 1;
    input.supplyWarehouse[1] = 2;
    input.supplyWarehouse[2] = 1;
    input.supplyWarehouse[3] = 1;
    input.supplyWarehouse[4] = 2;
    input.quantity[0] = 50;
    input.quantity[1] = 20;
    input.quantity[2] = 40;
    input.quantity[3] = 10;
    input.quantity[4] = 30;
    TpccRiverNewOrderArguments binder = new TpccRiverNewOrderArguments(ITEMS);
    TransactionProgramArguments arguments = binder.bind(input);

    assertLine(arguments, 0, 1, 1, 10, 4);
    assertLine(arguments, 1, 1, 4, 40, 3);
    assertLine(arguments, 2, 1, 5, 50, 1);
    assertLine(arguments, 3, 2, 2, 20, 2);
    assertLine(arguments, 4, 2, 3, 30, 5);
    binder.release();
  }

  @Test
  void invalidSentinelRemainsLastAfterCanonicalOrdering() throws Exception {
    TpccInputs.NewOrder input = input(1, 5, 1);
    input.item[0] = 5;
    input.item[1] = 2;
    input.item[2] = 4;
    input.item[3] = 1;
    input.item[4] = ITEMS + 1;
    input.supplyWarehouse[0] = 2;
    input.supplyWarehouse[1] = 2;
    input.supplyWarehouse[2] = 2;
    input.supplyWarehouse[3] = 2;
    input.supplyWarehouse[4] = 1;
    TpccRiverNewOrderArguments binder = new TpccRiverNewOrderArguments(ITEMS);
    TransactionProgramArguments arguments = binder.bind(input);

    assertLine(arguments, 0, 2, 1, 5, 4);
    assertLine(arguments, 1, 2, 2, 5, 2);
    assertLine(arguments, 2, 2, 4, 5, 3);
    assertLine(arguments, 3, 2, 5, 5, 1);
    assertLine(arguments, 4, 1, ITEMS + 1, 5, 5);
    binder.release();
  }

  @Test
  void executesEveryTpccLineCountThroughOneRequest(@TempDir Path root) throws Exception {
    try (Fixture fixture = Fixture.open(root)) {
      try (TpccRiverNewOrder transaction = new TpccRiverNewOrder(fixture.connection, 1, ITEMS)) {
        int expectedLines = 0;
        for (int lines = 5; lines <= 15; lines++) {
          TpccInputs.NewOrder input = input(1, lines, 1);
          long requests = fixture.metrics.completedRequests();
          assertTrue(transaction.execute(input));
          assertEquals(requests + 1, fixture.metrics.completedRequests());
          expectedLines += lines;
        }
        assertEquals(111, scalar(fixture.connection,
            "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=1"));
        assertEquals(11, scalar(fixture.connection, "SELECT COUNT(*) FROM orders"));
        assertEquals(expectedLines, scalar(fixture.connection, "SELECT COUNT(*) FROM order_line"));
      }
    }
  }

  @Test
  void appliesQuantityBranchesAndRemoteSupply(@TempDir Path root) throws Exception {
    try (Fixture fixture = Fixture.open(root)) {
      try (TpccRiverNewOrder transaction = new TpccRiverNewOrder(fixture.connection, 1, ITEMS)) {
        TpccInputs.NewOrder input = input(1, 5, 1);
        input.quantity[0] = 5;
        input.quantity[1] = 5;
        input.supplyWarehouse[4] = 2;

        assertTrue(transaction.execute(input));

        assertEquals(15, stockValue(fixture.connection, 1, 1, "s_quantity"));
        assertEquals(100, stockValue(fixture.connection, 1, 2, "s_quantity"));
        assertEquals(1, stockValue(fixture.connection, 2, 5, "s_remote_cnt"));
        assertEquals("R01", text(fixture.connection,
            "SELECT ol_dist_info FROM order_line WHERE ol_w_id=1 AND ol_d_id=1 "
                + "AND ol_o_id=100 AND ol_number=5"));
      }
    }
  }

  @Test
  void usesDistrictTenDistributionAndRollsBackInvalidItem(@TempDir Path root) throws Exception {
    try (Fixture fixture = Fixture.open(root)) {
      try (TpccRiverNewOrder districtTen =
          new TpccRiverNewOrder(fixture.connection, 10, ITEMS)) {
        assertTrue(districtTen.execute(input(10, 5, 1)));
        assertEquals("H10", text(fixture.connection,
            "SELECT ol_dist_info FROM order_line WHERE ol_w_id=1 AND ol_d_id=10 "
                + "AND ol_o_id=200 AND ol_number=1"));
        TpccInputs.NewOrder invalid = input(10, 5, 1);
        invalid.item[4] = ITEMS + 1;
        int quantity = stockValue(fixture.connection, 1, 1, "s_quantity");
        long requests = fixture.metrics.completedRequests();

        assertFalse(districtTen.execute(invalid));
        assertEquals(requests + 1, fixture.metrics.completedRequests());
        assertEquals(201, scalar(fixture.connection,
            "SELECT d_next_o_id FROM district WHERE d_w_id=1 AND d_id=10"));
        assertEquals(1, scalar(fixture.connection,
            "SELECT COUNT(*) FROM orders WHERE o_w_id=1 AND o_d_id=10"));
        assertEquals(quantity, stockValue(fixture.connection, 1, 1, "s_quantity"));
      }
    }
  }

  @Test
  void releasesPreparedResourcesForAReplacementProgramSet(@TempDir Path root) throws Exception {
    try (Fixture fixture = Fixture.open(root)) {
      try (TpccRiverNewOrder first =
          new TpccRiverNewOrder(fixture.connection, 10, ITEMS)) {
        assertTrue(first.execute(input(10, 5, 1)));
      }
      try (TpccRiverNewOrder replacement =
          new TpccRiverNewOrder(fixture.connection, 1, ITEMS)) {
        assertTrue(replacement.execute(input(1, 5, 1)));
      }
    }
  }

  @Test
  void runsAtAnIdleManualCommitBoundaryAndRejectsAnActiveTransaction(
      @TempDir Path root) throws Exception {
    try (Fixture fixture = Fixture.open(root)) {
      fixture.connection.setAutoCommit(false);
      try (TpccRiverNewOrder transaction =
          new TpccRiverNewOrder(fixture.connection, 1, ITEMS);
          Statement statement = fixture.connection.createStatement()) {
        assertTrue(transaction.execute(input(1, 5, 1)));
        assertFalse(fixture.connection.getAutoCommit());

        assertEquals(1, statement.executeUpdate(
            "UPDATE warehouse SET w_tax=0.2000 WHERE w_id=1"));
        SQLException conflict = assertThrows(
            SQLException.class, () -> transaction.execute(input(1, 5, 1)));
        assertEquals(StatusCode.CONFLICT.stableCode(), conflict.getErrorCode());
        fixture.connection.rollback();

        assertTrue(transaction.execute(input(1, 5, 1)));
        assertFalse(fixture.connection.getAutoCommit());
      }
    }
  }

  private static TpccInputs.NewOrder input(int district, int lines, int firstItem) {
    TpccInputs.NewOrder input = new TpccInputs.NewOrder();
    input.warehouse = 1;
    input.district = district;
    input.customer = 1;
    input.lines = lines;
    input.entry = Timestamp.valueOf("2026-09-02 12:00:00");
    for (int line = 0; line < lines; line++) {
      input.item[line] = firstItem + line;
      input.quantity[line] = 5;
      input.supplyWarehouse[line] = 1;
    }
    return input;
  }

  private static void assertLine(
      TransactionProgramArguments arguments,
      int executionLine,
      int warehouse,
      int item,
      int quantity,
      int originalLineNumber) {
    assertEquals(item, arguments.valueAt(TpccRiverNewOrderLayout.item(executionLine)));
    assertEquals(quantity, arguments.valueAt(TpccRiverNewOrderLayout.quantity(executionLine)));
    assertEquals(
        warehouse,
        arguments.valueAt(TpccRiverNewOrderLayout.supplyWarehouse(executionLine)));
    assertEquals(
        originalLineNumber,
        arguments.valueAt(TpccRiverNewOrderLayout.lineNumber(executionLine)));
  }

  private static int stockValue(Connection connection, int warehouse, int item, String column)
      throws Exception {
    return scalar(connection, "SELECT " + column + " FROM stock WHERE s_w_id="
        + warehouse + " AND s_i_id=" + item);
  }

  private static int scalar(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      int value = rows.getInt(1);
      assertFalse(rows.next());
      return value;
    }
  }

  private static String text(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      String value = rows.getString(1);
      assertFalse(rows.next());
      return value;
    }
  }

  private static final class Fixture implements AutoCloseable {
    final RiverDatabase database;
    final LoopbackRiverServer server;
    final Connection connection;
    final RiverConnectionMetrics metrics;

    private Fixture(
        RiverDatabase owner, LoopbackRiverServer loopback, Connection jdbc) throws java.sql.SQLException {
      database = owner;
      server = loopback;
      connection = jdbc;
      metrics = jdbc.unwrap(RiverConnectionMetrics.class);
    }

    static Fixture open(Path root) throws java.sql.SQLException {
      DatabaseOpenResult opened = new DatabaseOpenResult();
      assertEquals(StatusCode.OK,
          EmbeddedRiver.create(
              databaseRequest(16), root, DATABASE, WalGeneration.of(1), 16, opened));
      LoopbackServerOpenResult serverResult = new LoopbackServerOpenResult();
      assertEquals(StatusCode.OK,
          LoopbackRiverServer.start(opened.database(), 0, serverResult));
      Connection connection = DriverManager.getConnection(
          "jdbc:river://localhost:" + serverResult.server().port());
      createSchema(connection);
      load(connection);
      return new Fixture(opened.database(), serverResult.server(), connection);
    }

    @Override
    public void close() throws java.sql.SQLException {
      connection.close();
      assertEquals(StatusCode.OK, server.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }

  private static final class ProgramRecorder implements RiverTransactionPrograms {
    private long nextStatement = 1;
    private long nextProgram = 1;
    private final Map<Long, String> statementNames = new HashMap<>();
    private List<String> firstHeader;

    @Override
    public long prepareStatement(String sql) {
      long handle = nextStatement++;
      statementNames.put(handle, statementName(sql));
      return handle;
    }

    @Override
    public void closeStatement(long handle) { }

    @Override
    public long prepareProgram(TransactionProgram program) {
      if (firstHeader == null) {
        firstHeader = new ArrayList<>(TpccRiverNewOrderLayout.HEADER_STEPS);
        for (int step = 0; step < TpccRiverNewOrderLayout.HEADER_STEPS; step++) {
          firstHeader.add(statementNames.get(program.preparedHandle(step)));
        }
      }
      return nextProgram++;
    }

    @Override
    public void executeProgram(
        long handle,
        TransactionProgramArguments arguments,
        TransactionProgramResult result) { }

    @Override
    public void closeProgram(long handle) { }

    private static String statementName(String sql) {
      if (sql.startsWith("SELECT w_tax")) return "warehouse";
      if (sql.startsWith("SELECT d_tax")) return "district";
      if (sql.startsWith("UPDATE district")) return "advance-district";
      if (sql.startsWith("SELECT c_discount")) return "customer";
      if (sql.startsWith("SELECT no_o_id")) return "reserve-new-order";
      if (sql.startsWith("INSERT INTO orders")) return "insert-order";
      if (sql.startsWith("INSERT INTO new_order")) return "insert-new-order";
      return "line-operation";
    }
  }

  private static void createSchema(Connection connection) throws java.sql.SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE warehouse (w_id SMALLINT PRIMARY KEY,"
          + "w_tax DECIMAL(4,4) NOT NULL)");
      statement.executeUpdate("CREATE TABLE district (d_w_id SMALLINT NOT NULL,"
          + "d_id SMALLINT NOT NULL,d_tax DECIMAL(4,4) NOT NULL,d_next_o_id INTEGER NOT NULL,"
          + "PRIMARY KEY(d_w_id,d_id))");
      statement.executeUpdate("CREATE TABLE customer (c_w_id SMALLINT NOT NULL,"
          + "c_d_id SMALLINT NOT NULL,c_id INTEGER NOT NULL,c_discount DECIMAL(4,4) NOT NULL,"
          + "c_last VARCHAR(16) NOT NULL,c_credit CHAR(2) NOT NULL,"
          + "PRIMARY KEY(c_w_id,c_d_id,c_id))");
      statement.executeUpdate("CREATE TABLE item (i_id INTEGER PRIMARY KEY,"
          + "i_price DECIMAL(5,2) NOT NULL)");
      statement.executeUpdate("CREATE TABLE stock (s_w_id SMALLINT NOT NULL,"
          + "s_i_id INTEGER NOT NULL,s_quantity SMALLINT NOT NULL,"
          + "s_dist_01 CHAR(24) NOT NULL,s_dist_02 CHAR(24) NOT NULL,"
          + "s_dist_03 CHAR(24) NOT NULL,s_dist_04 CHAR(24) NOT NULL,"
          + "s_dist_05 CHAR(24) NOT NULL,s_dist_06 CHAR(24) NOT NULL,"
          + "s_dist_07 CHAR(24) NOT NULL,s_dist_08 CHAR(24) NOT NULL,"
          + "s_dist_09 CHAR(24) NOT NULL,s_dist_10 CHAR(24) NOT NULL,"
          + "s_ytd INTEGER NOT NULL,s_order_cnt SMALLINT NOT NULL,"
          + "s_remote_cnt SMALLINT NOT NULL,PRIMARY KEY(s_w_id,s_i_id))");
      statement.executeUpdate("CREATE TABLE orders (o_w_id SMALLINT NOT NULL,"
          + "o_d_id SMALLINT NOT NULL,o_id INTEGER NOT NULL,o_c_id INTEGER NOT NULL,"
          + "o_entry_d TIMESTAMP(6) NOT NULL,o_carrier_id SMALLINT,o_ol_cnt SMALLINT NOT NULL,"
          + "o_all_local SMALLINT NOT NULL,PRIMARY KEY(o_w_id,o_d_id,o_id))");
      statement.executeUpdate("CREATE TABLE new_order (no_w_id SMALLINT NOT NULL,"
          + "no_d_id SMALLINT NOT NULL,no_o_id INTEGER NOT NULL,"
          + "PRIMARY KEY(no_w_id,no_d_id,no_o_id))");
      statement.executeUpdate("CREATE TABLE order_line (ol_w_id SMALLINT NOT NULL,"
          + "ol_d_id SMALLINT NOT NULL,ol_o_id INTEGER NOT NULL,ol_number SMALLINT NOT NULL,"
          + "ol_i_id INTEGER NOT NULL,ol_supply_w_id SMALLINT NOT NULL,"
          + "ol_delivery_d TIMESTAMP(6),ol_quantity SMALLINT NOT NULL,"
          + "ol_amount DECIMAL(6,2) NOT NULL,ol_dist_info CHAR(24) NOT NULL,"
          + "PRIMARY KEY(ol_w_id,ol_d_id,ol_o_id,ol_number))");
    }
  }

  private static void load(Connection connection) throws java.sql.SQLException {
    try (Statement statement = connection.createStatement()) {
      assertEquals(2, statement.executeUpdate("INSERT INTO warehouse VALUES "
          + "(1,0.1000),(2,0.1000)"));
      assertEquals(2, statement.executeUpdate("INSERT INTO district VALUES "
          + "(1,1,0.1000,100),(1,10,0.1000,200)"));
      assertEquals(2, statement.executeUpdate("INSERT INTO customer VALUES "
          + "(1,1,1,0.1000,'LAST','GC'),(1,10,1,0.1000,'LAST','GC')"));
    }
    try (PreparedStatement item = connection.prepareStatement("INSERT INTO item VALUES (?,1.00)");
        PreparedStatement stock = connection.prepareStatement(
            "INSERT INTO stock VALUES (?,?,?,'H01','H02','H03','H04','H05',"
                + "'H06','H07','H08','H09','H10',0,0,0)")) {
      for (int id = 1; id <= ITEMS; id++) {
        item.setInt(1, id);
        item.addBatch();
        for (int warehouse = 1; warehouse <= 2; warehouse++) {
          stock.setInt(1, warehouse);
          stock.setInt(2, id);
          stock.setInt(3, id == 2 ? 14 : 20);
          stock.addBatch();
        }
      }
      assertEquals(ITEMS, item.executeBatch().length);
      assertEquals(ITEMS * 2, stock.executeBatch().length);
    }
    try (Statement statement = connection.createStatement()) {
      for (int district = 1; district <= 10; district++) {
        String suffix = district < 10 ? "0" + district : "10";
        statement.executeUpdate("UPDATE stock SET s_dist_" + suffix + "='R" + suffix
            + "' WHERE s_w_id=2");
      }
    }
  }
}
