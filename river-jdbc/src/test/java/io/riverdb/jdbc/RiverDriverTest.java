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
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
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
        assertEquals("id", metadata.getColumnLabel(1));
        assertEquals("balance", metadata.getColumnLabel(2));
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertEquals(100, rows.getLong("balance"));
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1));
        assertEquals(200L, rows.getObject(2, Long.class));
        assertTrue(rows.next());
        assertEquals("3", rows.getString(1));
        assertEquals(300, rows.getLong(2));
        assertFalse(rows.next());
        assertTrue(rows.isAfterLast());
      }
      try (ResultSet aggregate = statement.executeQuery(
          "SELECT COUNT(*) FROM accounts WHERE region=7")) {
        assertEquals("count", aggregate.getMetaData().getColumnLabel(1));
        assertTrue(aggregate.next());
        assertEquals(2, aggregate.getLong("count"));
        assertFalse(aggregate.next());
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
      DatabaseMetaData metadata = connection.getMetaData();
      assertEquals("River", metadata.getDatabaseProductName());
      assertEquals("River JDBC", metadata.getDriverName());
      assertEquals(url(server), metadata.getURL());
      assertEquals(connection, metadata.getConnection());
      assertEquals(4, metadata.getJDBCMajorVersion());
      assertEquals(3, metadata.getJDBCMinorVersion());
      assertTrue(metadata.supportsTransactions());
      assertTrue(metadata.supportsTransactionIsolationLevel(
          Connection.TRANSACTION_REPEATABLE_READ));
      assertTrue(metadata.supportsTransactionIsolationLevel(
          Connection.TRANSACTION_SERIALIZABLE));
      assertFalse(metadata.supportsTransactionIsolationLevel(
          Connection.TRANSACTION_READ_COMMITTED));
      assertTrue(metadata.supportsBatchUpdates());
      assertTrue(metadata.supportsResultSetConcurrency(
          ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
      assertFalse(metadata.supportsResultSetConcurrency(
          ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
      assertEquals(8, metadata.getMaxColumnsInTable());
      assertEquals(64, metadata.getMaxTableNameLength());
      assertThrows(
          java.sql.SQLFeatureNotSupportedException.class,
          () -> metadata.getTables(null, null, null, null));
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
        assertEquals("value", result.getMetaData().getColumnName(1));
        assertTrue(result.next());
        assertEquals(200, result.getLong("value"));
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

  @Test
  void batchesAreBoundedAndReportTheSuccessfulPrefix(@TempDir Path root)
      throws SQLException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);

    try (Connection connection = DriverManager.getConnection(url(server))) {
      try (Statement statement = connection.createStatement()) {
        assertEquals(0, statement.executeUpdate(
            "CREATE TABLE batch_values (id BIGINT PRIMARY KEY, value BIGINT)"));
        statement.addBatch("INSERT INTO batch_values VALUES (1, 10)");
        statement.addBatch("INSERT INTO batch_values VALUES (2, 20)");
        assertTrue(Arrays.equals(new int[] {1, 1}, statement.executeBatch()));
        assertEquals(0, statement.executeBatch().length);

        statement.addBatch("INSERT INTO batch_values VALUES (3, 30)");
        statement.addBatch("INSERT INTO batch_values VALUES (1, 999)");
        statement.addBatch("INSERT INTO batch_values VALUES (4, 40)");
        BatchUpdateException partial = assertThrows(
            BatchUpdateException.class,
            statement::executeBatch);
        assertTrue(Arrays.equals(new int[] {1}, partial.getUpdateCounts()));
        assertEquals("40001", partial.getSQLState());

        for (int index = 0;
            index < RiverJdbcStatement.MAXIMUM_BATCH_STATEMENTS;
            index++) {
          statement.addBatch("INSERT INTO batch_values VALUES (99, 99)");
        }
        SQLException full = assertThrows(
            SQLException.class,
            () -> statement.addBatch("INSERT INTO batch_values VALUES (100, 100)"));
        assertEquals("53000", full.getSQLState());
        statement.clearBatch();
      }

      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO batch_values VALUES (?, ?)")) {
        insert.setLong(1, 4);
        insert.setLong(2, 40);
        insert.addBatch();
        insert.setLong(1, 5);
        insert.setLong(2, 50);
        insert.addBatch();
        assertTrue(Arrays.equals(new int[] {1, 1}, insert.executeBatch()));
      }

      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery(
              "SELECT id, value FROM batch_values WHERE id >= 1 AND id < 6")) {
        long[] expectedKeys = {1, 2, 3, 4, 5};
        int index = 0;
        while (rows.next()) {
          assertEquals(expectedKeys[index++], rows.getLong(1));
        }
        assertEquals(expectedKeys.length, index);
      }
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void dataSourceExecutesJdbcInsideTlsBoundTokenAuthentication(@TempDir Path root)
      throws Exception {
    byte[] token = "river-jdbc-auth-token-0001".getBytes(StandardCharsets.UTF_8);
    TokenAuthenticatorOpenResult authenticator = new TokenAuthenticatorOpenResult();
    assertEquals(
        StatusCode.OK,
        TokenAuthenticator.create(token, token.length, authenticator));
    SSLContext serverContext = TestTlsContexts.server();
    SSLContext clientContext = TestTlsContexts.trustedClient();
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = startAuthenticated(
        database, serverContext, authenticator.authenticator());

    RiverDataSource source = new RiverDataSource();
    source.setPort(server.port());
    assertEquals(5, source.getLoginTimeout());
    source.setLoginTimeout(5);
    assertThrows(
        java.sql.SQLFeatureNotSupportedException.class,
        () -> source.setLoginTimeout(0));
    source.setAuthentication(clientContext, token, token.length);
    Arrays.fill(token, (byte) 0);
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      assertEquals(0, statement.executeUpdate(
          "CREATE TABLE secure_jdbc (id BIGINT PRIMARY KEY, value BIGINT)"));
      assertEquals(
          1,
          statement.executeUpdate("INSERT INTO secure_jdbc VALUES (1, 700)"));
      try (ResultSet result = statement.executeQuery(
          "SELECT value FROM secure_jdbc WHERE id=1")) {
        assertTrue(result.next());
        assertEquals(700, result.getLong("value"));
      }
    }
    source.close();
    SQLException closed = assertThrows(SQLException.class, source::getConnection);
    assertEquals("08003", closed.getSQLState());

    byte[] wrongToken =
        "wrong-jdbc-auth-token-0001".getBytes(StandardCharsets.UTF_8);
    RiverDataSource wrong = new RiverDataSource();
    wrong.setPort(server.port());
    wrong.setAuthentication(clientContext, wrongToken, wrongToken.length);
    SQLException rejected = assertThrows(SQLException.class, wrong::getConnection);
    assertEquals("28000", rejected.getSQLState());
    wrong.close();
    Arrays.fill(wrongToken, (byte) 0);

    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static LoopbackRiverServer start(RiverDatabase database) {
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
    assertEquals(StatusCode.OK, LoopbackRiverServer.start(database, 0, result));
    return result.server();
  }

  private static LoopbackRiverServer startAuthenticated(
      RiverDatabase database,
      SSLContext context,
      TokenAuthenticator authenticator) {
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.startAuthenticated(
            database, 0, context, authenticator, result));
    return result.server();
  }

  private static String url(LoopbackRiverServer server) {
    return RiverDriver.URL_PREFIX + server.port();
  }
}
