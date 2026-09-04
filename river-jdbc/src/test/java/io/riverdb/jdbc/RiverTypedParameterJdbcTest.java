package io.riverdb.jdbc;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.LoopbackServerOpenResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverTypedParameterJdbcTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a44424350415241L, 0x4d45544552533031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void sendsAuthenticatedTypedValuesAndOwnsBatchSnapshots(@TempDir Path root)
      throws Exception {
    byte[] token = "river-typed-parameter-token".getBytes(StandardCharsets.UTF_8);
    TokenAuthenticatorOpenResult authenticated = new TokenAuthenticatorOpenResult();
    assertEquals(StatusCode.OK, TokenAuthenticator.create(
        token, token.length, authenticated));
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(
        databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackServerOpenResult listener = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.startAuthenticated(
            database,
            0,
            TestTlsContexts.server(),
            authenticated.authenticator(),
            root,
            LoopbackServerLimits.defaults(8),
            listener));
    LoopbackRiverServer server = listener.server();

    RiverDataSource source = new RiverDataSource();
    source.setPort(server.port());
    source.setAuthentication(TestTlsContexts.trustedClient(), token, token.length);
    Connection connection = source.getConnection();
    try (connection) {
      createSchema(connection);
      insertTypedRows(connection);
      assertWideTextParameterRoundTrip(connection);
      assertConcurrentPreparedStatements(connection);
      assertTypedValuesAndBorrowLifetime(connection);
      assertStrictStatusBoundaries(connection);
      assertBatchSnapshots(connection);
      assertOpenWarningLifecycle(connection);
    }
    assertClosedWarnings(connection);
    source.close();
    Arrays.fill(token, (byte) 0);
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void roundTripsSinglePageTextBoundaryThroughJdbc(@TempDir Path root)
      throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(
        databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackServerOpenResult listener = new LoopbackServerOpenResult();
    assertEquals(StatusCode.OK, LoopbackRiverServer.start(database, 0, listener));
    LoopbackRiverServer server = listener.server();
    try (Connection connection = java.sql.DriverManager.getConnection(
            RiverDriver.URL_PREFIX + server.port());
        Statement statement = connection.createStatement()) {
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE typed_page_text "
              + "(id BIGINT PRIMARY KEY, value VARCHAR(4041) NOT NULL,"
              + "flag_a BOOLEAN NOT NULL,flag_b BOOLEAN NOT NULL,flag_c BOOLEAN NOT NULL)"));
      assertSinglePageTextBoundaryRoundTrip(connection);
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE typed_parameters (id BIGINT PRIMARY KEY, flag BOOLEAN, "
              + "amount DECIMAL(8,3), label VARCHAR(32), day DATE, "
              + "clock TIME(6), observed TIMESTAMP(6), "
              + "captured TIMESTAMP(6) WITH TIME ZONE)"));
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE typed_batch (id BIGINT PRIMARY KEY, day DATE)"));
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE typed_concurrent (id BIGINT PRIMARY KEY, day DATE)"));
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE typed_customer (id BIGINT PRIMARY KEY, "
              + "c_data VARCHAR(500), state CHAR(2))"));
      assertEquals(0, statement.executeUpdate(
          "CREATE UNIQUE INDEX typed_customer_data ON typed_customer(c_data)"));
    }
  }

  private static void assertWideTextParameterRoundTrip(Connection connection)
      throws SQLException {
    String customerData = "x".repeat(500);
    try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO typed_customer VALUES (?,?,?)");
        PreparedStatement select = connection.prepareStatement(
            "SELECT c_data,state FROM typed_customer WHERE c_data=?")) {
      insert.setLong(1, 1);
      insert.setString(2, customerData);
      insert.setString(3, "GC");
      assertEquals(1, insert.executeUpdate());
      select.setString(1, customerData);
      try (ResultSet rows = select.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(customerData, rows.getString(1));
        assertEquals("GC", rows.getString(2));
        assertFalse(rows.next());
      }
    }
  }

  private static void assertSinglePageTextBoundaryRoundTrip(Connection connection)
      throws SQLException {
    String inserted = "\ud83d\ude00".repeat(4_041);
    String updated = "\ud83d\ude01".repeat(4_041);
    try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO typed_page_text VALUES (?,?,?,?,?)");
        PreparedStatement update = connection.prepareStatement(
            "UPDATE typed_page_text SET value=? WHERE id=?");
        PreparedStatement select = connection.prepareStatement(
            "SELECT value FROM typed_page_text WHERE id=?");
        PreparedStatement scan = connection.prepareStatement(
            "SELECT value FROM typed_page_text ORDER BY id")) {
      insert.setLong(1, 1);
      insert.setString(2, inserted);
      insert.setBoolean(3, false);
      insert.setBoolean(4, true);
      insert.setBoolean(5, false);
      assertEquals(1, insert.executeUpdate());
      assertText(select, inserted);
      assertScanText(scan, inserted);

      update.setString(1, updated);
      update.setLong(2, 1);
      assertEquals(1, update.executeUpdate());
      assertText(select, updated);
      assertScanText(scan, updated);
    }
  }

  private static void assertText(PreparedStatement select, String expected)
      throws SQLException {
    select.setLong(1, 1);
    try (ResultSet rows = select.executeQuery()) {
      assertTrue(rows.next());
      assertEquals(expected, rows.getString(1));
      assertFalse(rows.next());
    }
  }

  private static void assertScanText(PreparedStatement select, String expected)
      throws SQLException {
    try (ResultSet rows = select.executeQuery()) {
      assertTrue(rows.next());
      assertEquals(expected, rows.getString(1));
      assertFalse(rows.next());
    }
  }

  private static void insertTypedRows(Connection connection) throws SQLException {
    String sql = "INSERT INTO typed_parameters VALUES (?,?,?,?,?,?,?,?)";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setLong(1, 1);
      insert.setBoolean(2, true);
      insert.setBigDecimal(3, new BigDecimal("42.700"));
      insert.setString(4, "what?'猫");
      insert.setDate(5, Date.valueOf("1969-12-31"));
      insert.setObject(6, LocalTime.of(23, 59, 58));
      insert.setTimestamp(
          7, Timestamp.valueOf("1969-12-31 23:59:58.123456"));
      insert.setObject(
          8,
          OffsetDateTime.of(
              1969, 12, 31, 23, 59, 58, 123_456_000,
              ZoneOffset.ofHours(1)));
      assertEquals(1, insert.executeUpdate());

      insert.setLong(1, 2);
      insert.setNull(2, Types.BOOLEAN);
      insert.setBigDecimal(3, null);
      insert.setString(4, null);
      insert.setObject(5, null, Types.DATE);
      insert.setNull(6, Types.TIME);
      insert.setTimestamp(7, null);
      insert.setObject(8, null, Types.TIMESTAMP_WITH_TIMEZONE);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private static void assertConcurrentPreparedStatements(Connection connection)
      throws SQLException {
    try (PreparedStatement first = connection.prepareStatement(
            "INSERT INTO typed_concurrent VALUES (?,?)");
        PreparedStatement second = connection.prepareStatement(
            "INSERT INTO typed_concurrent VALUES (?,?)");
        PreparedStatement selected = connection.prepareStatement(
            "SELECT id FROM typed_parameters WHERE flag=true ORDER BY id")) {
      first.setLong(1, 1);
      first.setDate(2, Date.valueOf("2024-01-01"));
      first.addBatch();
      first.setLong(1, 2);
      first.setDate(2, Date.valueOf("2024-01-02"));
      first.addBatch();
      second.setLong(1, 3);
      second.setDate(2, Date.valueOf("2024-01-03"));
      second.addBatch();
      second.setLong(1, 4);
      second.setDate(2, Date.valueOf("2024-01-04"));
      second.addBatch();
      assertArrayEquals(new int[] {1, 1}, first.executeBatch());
      assertArrayEquals(new int[] {1, 1}, second.executeBatch());
      try (ResultSet rows = selected.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertFalse(rows.next());
      }
    }
  }

  private static void assertTypedValuesAndBorrowLifetime(Connection connection)
      throws SQLException {
    try (PreparedStatement select = connection.prepareStatement(
        "SELECT id,flag,amount,label,day,clock,observed,captured "
            + "FROM typed_parameters WHERE label=?")) {
      select.setString(1, "what?'猫");
      try (ResultSet rows = select.executeQuery()) {
        select.setString(1, "changed after admission");
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertTrue(rows.getBoolean(2));
        assertEquals(new BigDecimal("42.700"), rows.getBigDecimal(3));
        assertEquals("what?'猫", rows.getString(4));
        assertEquals(LocalDate.of(1969, 12, 31), rows.getObject(5));
        assertEquals(LocalTime.of(23, 59, 58), rows.getObject(6));
        assertEquals(
            LocalDateTime.of(1969, 12, 31, 23, 59, 58, 123_456_000),
            rows.getObject(7));
        assertEquals(
            OffsetDateTime.of(
                1969, 12, 31, 22, 59, 58, 123_456_000, ZoneOffset.UTC),
            rows.getObject(8));
        assertFalse(rows.next());
      }
    }
    try (PreparedStatement quoted = connection.prepareStatement(
        "SELECT id FROM typed_parameters WHERE label='what?''猫' AND id=?")) {
      quoted.setLong(1, 1);
      try (ResultSet rows = quoted.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertFalse(rows.next());
      }
    }
    try (PreparedStatement nulls = connection.prepareStatement(
        "SELECT flag,amount,label,day,clock,observed,captured "
            + "FROM typed_parameters WHERE id=?")) {
      nulls.setLong(1, 2);
      try (ResultSet rows = nulls.executeQuery()) {
        assertTrue(rows.next());
        for (int column = 1; column <= 7; column++) {
          assertNull(rows.getObject(column));
        }
      }
    }
    try (PreparedStatement temporalPredicate = connection.prepareStatement(
        "SELECT id FROM typed_parameters WHERE day=? AND clock=? "
            + "AND observed=? AND captured=?")) {
      temporalPredicate.setObject(1, LocalDate.of(1969, 12, 31), JDBCType.DATE);
      temporalPredicate.setTime(2, Time.valueOf("23:59:58"));
      temporalPredicate.setObject(
          3, LocalDateTime.of(1969, 12, 31, 23, 59, 58, 123_456_000));
      temporalPredicate.setObject(
          4,
          OffsetDateTime.of(
              1969, 12, 31, 22, 59, 58, 123_456_000, ZoneOffset.UTC));
      try (ResultSet rows = temporalPredicate.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertFalse(rows.next());
      }
    }
  }

  private static void assertStrictStatusBoundaries(Connection connection)
      throws SQLException {
    try (PreparedStatement unset = connection.prepareStatement(
        "SELECT id FROM typed_parameters WHERE id=?")) {
      SQLException failure = assertThrows(SQLException.class, unset::executeQuery);
      assertEquals("07001", failure.getSQLState());
    }
    try (PreparedStatement mismatch = connection.prepareStatement(
        "SELECT id FROM typed_parameters WHERE day=?")) {
      mismatch.setString(1, "1969-12-31");
      SQLException failure = assertThrows(SQLException.class, mismatch::executeQuery);
      assertEquals("42804", failure.getSQLState());
      SQLException precision = assertThrows(
          SQLException.class,
          () -> mismatch.setObject(1, LocalTime.ofNanoOfDay(1)));
      assertEquals("22008", precision.getSQLState());
      SQLException offset = assertThrows(
          SQLException.class,
          () -> mismatch.setObject(
              1,
              OffsetDateTime.of(
                  2024, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(15))));
      assertEquals("22009", offset.getSQLState());
      SQLException text = assertThrows(
          SQLException.class,
          () -> mismatch.setString(1, "x".repeat(65_536)));
      assertEquals("22001", text.getSQLState());
      SQLException decimal = assertThrows(
          SQLException.class,
          () -> mismatch.setBigDecimal(
              1, new BigDecimal("123456789012345678901234567890123456789")));
      assertEquals("22003", decimal.getSQLState());
      SQLException negativeScale = assertThrows(
          SQLException.class,
          () -> mismatch.setBigDecimal(1, new BigDecimal("1E+38")));
      assertEquals("22003", negativeScale.getSQLState());
    }
    try (PreparedStatement normalized = connection.prepareStatement(
        "UPDATE typed_parameters SET amount=? WHERE id=1")) {
      normalized.setBigDecimal(1, new BigDecimal("1E+2"));
      assertEquals(1, normalized.executeUpdate());
    }
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(
            "SELECT amount FROM typed_parameters WHERE id=1")) {
      assertTrue(rows.next());
      assertEquals(new BigDecimal("100.000"), rows.getBigDecimal(1));
      assertFalse(rows.next());
    }
    RiverJdbcPreparedStatement lifecycle = (RiverJdbcPreparedStatement)
        connection.prepareStatement("SELECT id FROM typed_parameters WHERE label=?");
    lifecycle.setString(1, "retained until close");
    lifecycle.close();
    lifecycle.close();
    SQLException closed = assertThrows(
        SQLException.class, () -> lifecycle.setString(1, "closed"));
    assertEquals("08003", closed.getSQLState());
  }

  private static void assertBatchSnapshots(Connection connection)
      throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO typed_batch VALUES (?,?)")) {
      addBatch(insert, 10, LocalDate.of(2024, 1, 10));
      addBatch(insert, 11, LocalDate.of(2024, 1, 11));
      insert.clearParameters();
      assertArrayEquals(new int[] {1, 1}, insert.executeBatch());

      addBatch(insert, 12, LocalDate.of(2024, 1, 12));
      addBatch(insert, 10, LocalDate.of(2024, 2, 10));
      addBatch(insert, 13, LocalDate.of(2024, 1, 13));
      BatchUpdateException failure = assertThrows(
          BatchUpdateException.class, insert::executeBatch);
      assertArrayEquals(new int[] {1}, failure.getUpdateCounts());

      for (int index = 0; index < 257; index++) {
        addBatch(insert, 100 + index, LocalDate.of(2024, 2, 1));
      }
      insert.clearBatch();
    }
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(
            "SELECT id FROM typed_batch ORDER BY id")) {
      long[] expected = {10, 11, 12};
      int index = 0;
      while (rows.next()) assertEquals(expected[index++], rows.getLong(1));
      assertEquals(expected.length, index);
    }
  }

  private static void addBatch(
      PreparedStatement insert, long id, LocalDate day) throws SQLException {
    insert.setLong(1, id);
    insert.setObject(2, day, Types.DATE);
    insert.addBatch();
  }

  private static void assertOpenWarningLifecycle(Connection connection)
      throws SQLException {
    assertNull(connection.getWarnings());
    connection.clearWarnings();
    Statement statement = connection.createStatement();
    assertNull(statement.getWarnings());
    statement.clearWarnings();
    ResultSet rows = statement.executeQuery(
        "SELECT id FROM typed_parameters WHERE id=1");
    assertNull(rows.getWarnings());
    rows.clearWarnings();
    rows.close();
    assertClosedWarning(rows::getWarnings);
    assertClosedWarning(rows::clearWarnings);
    statement.close();
    assertClosedWarning(statement::getWarnings);
    assertClosedWarning(statement::clearWarnings);
  }

  private static void assertClosedWarnings(Connection connection) {
    assertClosedWarning(connection::getWarnings);
    assertClosedWarning(connection::clearWarnings);
  }

  private static void assertClosedWarning(SqlOperation operation) {
    SQLException failure = assertThrows(SQLException.class, operation::run);
    assertEquals("08003", failure.getSQLState());
  }

  @FunctionalInterface
  private interface SqlOperation {
    void run() throws SQLException;
  }
}
