package io.riverdb.jdbc;

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
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDriverTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a44424344524956L, 0x4552544553543031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void driverManagerExecutesStreamingSqlTransactionsAndDurableReopen(@TempDir Path root)
      throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    String url = url(server);

    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      assertTrue(connection.getAutoCommit());
      assertFalse(statement.execute(
          "CREATE TABLE accounts "
              + "(id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT)"));
      assertEquals(0, statement.getUpdateCount());
      assertEquals(
          3,
          statement.executeUpdate(
              "INSERT INTO accounts VALUES "
                  + "(1, 100, 7), (2, 200, 7), (3, 300, 8)"));

      try (ResultSet rows = statement.executeQuery(
          "SELECT id, balance FROM accounts WHERE id >= 1 AND id < 4")) {
        ResultSetMetaData metadata = rows.getMetaData();
        assertEquals(2, metadata.getColumnCount());
        assertEquals(Types.BIGINT, metadata.getColumnType(1));
        assertEquals("column1", metadata.getColumnLabel(1));
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertEquals(100, rows.getLong("column2"));
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1));
        assertEquals(200L, rows.getObject(2, Long.class));
        assertTrue(rows.next());
        assertEquals("3", rows.getString(1));
        assertEquals(300, rows.getLong(2));
        assertFalse(rows.next());
        assertTrue(rows.isAfterLast());
      }

      connection.setAutoCommit(false);
      assertEquals(
          1,
          statement.executeUpdate("INSERT INTO accounts VALUES (4, 400, 9)"));
      connection.rollback();
      try (ResultSet rolledBack = statement.executeQuery(
          "SELECT balance FROM accounts WHERE id=4")) {
        assertFalse(rolledBack.next());
      }
      assertEquals(
          1,
          statement.executeUpdate("INSERT INTO accounts VALUES (4, 450, 9)"));
      connection.commit();
      connection.setAutoCommit(true);
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    server = start(database);
    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery(
            "SELECT balance FROM accounts WHERE id=4")) {
      assertTrue(row.next());
      assertEquals(450, row.getLong(1));
      assertFalse(row.next());
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void reportsBoundedSubsetAndStableSqlStates(@TempDir Path root) throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);

    SQLException badUrl = assertThrows(
        SQLException.class,
        () -> DriverManager.getConnection("jdbc:river://localhost:not-a-port"));
    assertEquals("22000", badUrl.getSQLState());
    try (Connection connection = DriverManager.getConnection(url(server));
        Statement statement = connection.createStatement()) {
      SQLException secondStatement = assertThrows(
          SQLException.class,
          connection::createStatement);
      assertEquals("40001", secondStatement.getSQLState());
      SQLException invalidSql = assertThrows(
          SQLException.class,
          () -> statement.executeUpdate("NOT SQL"));
      assertEquals("22000", invalidSql.getSQLState());
      assertThrows(java.sql.SQLFeatureNotSupportedException.class, () -> {
        connection.setReadOnly(true);
      });
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void preparedStatementsRenderOnlyBoundedBigintParameters(@TempDir Path root)
      throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);

    try (Connection connection = DriverManager.getConnection(url(server));
        Statement schema = connection.createStatement()) {
      assertEquals(0, schema.executeUpdate(
          "CREATE TABLE prepared_values (id BIGINT PRIMARY KEY, value BIGINT)"));
    }
    try (Connection connection = DriverManager.getConnection(url(server));
        PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO prepared_values VALUES (?, ?)")) {
      insert.setLong(1, 1);
      SQLException unset = assertThrows(SQLException.class, insert::executeUpdate);
      assertEquals("22000", unset.getSQLState());
      insert.setLong(2, 100);
      assertEquals(1, insert.executeUpdate());
      insert.setObject(1, Integer.valueOf(2), Types.BIGINT);
      insert.setLong(2, 200);
      assertEquals(1, insert.executeUpdate());
      insert.setLong(1, 3);
      insert.setLong(2, Long.MIN_VALUE);
      assertEquals(1, insert.executeUpdate());
      assertThrows(
          java.sql.SQLFeatureNotSupportedException.class,
          () -> insert.setString(1, "1 OR 1=1"));
      insert.clearParameters();
      assertThrows(SQLException.class, () -> insert.setLong(3, 3));
    }
    try (Connection connection = DriverManager.getConnection(url(server));
        PreparedStatement select = connection.prepareStatement(
            "SELECT value FROM prepared_values WHERE id=?")) {
      select.setLong(1, 2);
      try (ResultSet result = select.executeQuery()) {
        assertEquals(1, result.getMetaData().getColumnCount());
        assertTrue(result.next());
        assertEquals(200, result.getLong(1));
        assertFalse(result.next());
      }
      select.setLong(1, 1);
      try (ResultSet result = select.executeQuery()) {
        assertTrue(result.next());
        assertEquals(100, result.getLong(1));
      }
      select.setLong(1, 3);
      try (ResultSet result = select.executeQuery()) {
        assertTrue(result.next());
        assertEquals(Long.MIN_VALUE, result.getLong(1));
      }
      assertThrows(
          SQLException.class,
          () -> select.executeQuery("SELECT value FROM prepared_values WHERE id=2"));
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
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
