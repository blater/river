package io.riverdb.jdbc;

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
      try (ResultSet nullable = statement.executeQuery(
          "SELECT id, NULL FROM accounts WHERE id=1")) {
        assertTrue(nullable.next());
        assertEquals(1, nullable.getLong(1));
        assertFalse(nullable.wasNull());
        assertEquals(0, nullable.getLong(2));
        assertTrue(nullable.wasNull());
        assertNull(nullable.getString(2));
        assertTrue(nullable.wasNull());
        assertNull(nullable.getObject(2));
        assertTrue(nullable.wasNull());
        assertNull(nullable.getObject(2, Long.class));
        assertTrue(nullable.wasNull());
        assertFalse(nullable.next());
      }
      try (ResultSet membership = statement.executeQuery(
          "SELECT id FROM accounts WHERE balance IN "
              + "(SELECT balance FROM accounts WHERE region=7) ORDER BY id")) {
        assertTrue(membership.next());
        assertEquals(1, membership.getLong(1));
        assertTrue(membership.next());
        assertEquals(2, membership.getLong(1));
        assertFalse(membership.next());
      }
      try (ResultSet membership = statement.executeQuery(
          "SELECT id FROM accounts WHERE balance NOT IN "
              + "(SELECT balance FROM accounts WHERE region=7)")) {
        assertTrue(membership.next());
        assertEquals(3, membership.getLong(1));
        assertFalse(membership.next());
      }
      try (ResultSet unknown = statement.executeQuery(
          "SELECT id FROM accounts WHERE id NOT IN "
              + "(SELECT NULL FROM accounts WHERE id=1)")) {
        assertFalse(unknown.next());
      }
      try (ResultSet unknown = statement.executeQuery(
          "SELECT id FROM accounts WHERE id IN "
              + "(SELECT NULL FROM accounts WHERE id=1)")) {
        assertFalse(unknown.next());
      }
      try (ResultSet unknown = statement.executeQuery(
          "SELECT id FROM accounts WHERE id="
              + "(SELECT NULL FROM accounts WHERE id=1)")) {
        assertFalse(unknown.next());
      }
      try (ResultSet exists = statement.executeQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT NULL FROM accounts WHERE id=1) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(exists.next());
          assertEquals(expected, exists.getLong(1));
        }
        assertFalse(exists.next());
      }
      try (ResultSet empty = statement.executeQuery(
          "SELECT id FROM accounts WHERE id NOT IN "
              + "(SELECT id FROM accounts WHERE id=99) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(empty.next());
          assertEquals(expected, empty.getLong(1));
        }
        assertFalse(empty.next());
      }
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE regions (id BIGINT PRIMARY KEY, code BIGINT)"));
      assertEquals(
          2,
          statement.executeUpdate(
              "INSERT INTO regions VALUES (7, 7000), (8, 8000)"));
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE region_labels "
                  + "(id BIGINT PRIMARY KEY, region BIGINT, code BIGINT)"));
      assertEquals(
          3,
          statement.executeUpdate(
              "INSERT INTO region_labels VALUES "
                  + "(1, 7, 7001), (2, 7, 7002), (3, 8, 8001)"));
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE INDEX region_labels_region ON region_labels(region)"));
      try (ResultSet ordered = statement.executeQuery(
          "SELECT id, balance FROM accounts ORDER BY balance")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(ordered.next());
          assertEquals(expected, ordered.getLong("id"));
          assertEquals(expected * 100, ordered.getLong("balance"));
        }
        assertFalse(ordered.next());
      }
      try (ResultSet derived = statement.executeQuery(
          "SELECT d.id, d.balance FROM "
              + "(SELECT id, balance, region FROM accounts WHERE accounts.region=7) d "
              + "WHERE d.balance >= 50 AND d.balance < 350 ORDER BY balance")) {
        assertTrue(derived.next());
        assertEquals(1, derived.getLong("id"));
        assertEquals(100, derived.getLong("balance"));
        assertTrue(derived.next());
        assertEquals(2, derived.getLong("id"));
        assertEquals(200, derived.getLong("balance"));
        assertFalse(derived.next());
      }
      try (ResultSet scalar = statement.executeQuery(
          "SELECT id, balance FROM accounts WHERE region=7 AND balance="
              + "(SELECT balance FROM accounts WHERE accounts.id=2)")) {
        assertTrue(scalar.next());
        assertEquals(2, scalar.getLong("id"));
        assertEquals(200, scalar.getLong("balance"));
        assertFalse(scalar.next());
      }
      try (ResultSet scalar = statement.executeQuery(
          "SELECT id FROM accounts WHERE balance="
              + "(SELECT balance FROM accounts WHERE id=99)")) {
        assertFalse(scalar.next());
      }
      SQLException cardinality = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(
              "SELECT id FROM accounts WHERE balance="
                  + "(SELECT region FROM accounts WHERE region=7)"));
      assertEquals("21000", cardinality.getSQLState());
      try (ResultSet exists = statement.executeQuery(
          "SELECT id FROM accounts WHERE EXISTS "
              + "(SELECT id FROM regions WHERE code=7000) ORDER BY id")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(exists.next());
          assertEquals(expected, exists.getLong("id"));
        }
        assertFalse(exists.next());
      }
      try (ResultSet notExists = statement.executeQuery(
          "SELECT id FROM accounts WHERE NOT EXISTS "
              + "(SELECT id FROM regions WHERE code=7000)")) {
        assertFalse(notExists.next());
      }
      String nested = "SELECT id FROM accounts";
      for (int depth = 1; depth < 32; depth++) {
        nested = "SELECT d" + depth + ".id FROM (" + nested + ") d" + depth;
      }
      nested = "SELECT overflow.id FROM (" + nested + ") overflow";
      String tooDeep = nested;
      SQLException depthFailure = assertThrows(
          SQLException.class,
          () -> statement.executeQuery(tooDeep));
      assertEquals("54001", depthFailure.getSQLState());
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE INDEX accounts_balance ON accounts(balance)"));
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE INDEX accounts_region ON accounts(region)"));

      try (ResultSet ordered = statement.executeQuery(
          "SELECT id, balance FROM accounts ORDER BY balance")) {
        for (long expected = 1; expected <= 3; expected++) {
          assertTrue(ordered.next());
          assertEquals(expected, ordered.getLong("id"));
          assertEquals(expected * 100, ordered.getLong("balance"));
        }
        assertFalse(ordered.next());
      }
      try (ResultSet grouped = statement.executeQuery(
          "SELECT region, COUNT(*) FROM accounts "
              + "WHERE balance >= 150 AND balance < 350 "
              + "GROUP BY region ORDER BY region")) {
        assertEquals("region", grouped.getMetaData().getColumnLabel(1));
        assertEquals("count", grouped.getMetaData().getColumnLabel(2));
        assertTrue(grouped.next());
        assertEquals(7, grouped.getLong("region"));
        assertEquals(1, grouped.getLong("count"));
        assertTrue(grouped.next());
        assertEquals(8, grouped.getLong(1));
        assertEquals(1, grouped.getLong(2));
        assertFalse(grouped.next());
      }
      try (ResultSet distinct = statement.executeQuery(
          "SELECT DISTINCT region FROM accounts "
              + "WHERE balance >= 150 AND balance < 350 "
              + "ORDER BY region")) {
        assertTrue(distinct.next());
        assertEquals(7, distinct.getLong("region"));
        assertTrue(distinct.next());
        assertEquals(8, distinct.getLong(1));
        assertFalse(distinct.next());
      }
      try (ResultSet joined = statement.executeQuery(
          "SELECT accounts.id, regions.code FROM accounts "
              + "JOIN regions ON accounts.region=regions.id "
              + "WHERE accounts.id >= 1 AND accounts.id < 4 "
              + "AND accounts.region=7 LIMIT 2")) {
        assertEquals("id", joined.getMetaData().getColumnLabel(1));
        assertEquals("code", joined.getMetaData().getColumnLabel(2));
        assertTrue(joined.next());
        long firstId = joined.getLong("id");
        assertEquals(7000, joined.getLong("code"));
        assertTrue(joined.next());
        long secondId = joined.getLong("id");
        assertEquals(7000, joined.getLong("code"));
        assertEquals(3, firstId + secondId);
        assertEquals(2, firstId * secondId);
        assertFalse(joined.next());
      }
      try (ResultSet joined = statement.executeQuery(
          "SELECT accounts.id, region_labels.code FROM accounts "
              + "JOIN region_labels ON accounts.region=region_labels.region "
              + "WHERE accounts.id=1")) {
        assertTrue(joined.next());
        long firstCode = joined.getLong("code");
        assertEquals(1, joined.getLong("id"));
        assertTrue(joined.next());
        long secondCode = joined.getLong("code");
        assertEquals(1, joined.getLong("id"));
        assertEquals(14003, firstCode + secondCode);
        assertEquals(49021002, firstCode * secondCode);
        assertFalse(joined.next());
      }
      try (ResultSet joined = statement.executeQuery(
          "SELECT accounts.id, region_labels.code FROM accounts "
              + "JOIN region_labels ON accounts.region=region_labels.region "
              + "WHERE accounts.id=1 AND region_labels.code=7002")) {
        assertTrue(joined.next());
        assertEquals(1, joined.getLong("id"));
        assertEquals(7002, joined.getLong("code"));
        assertFalse(joined.next());
      }

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
