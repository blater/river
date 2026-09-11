package io.riverdb.jdbc;

import static io.riverdb.jdbc.JdbcMetadataAssertions.assertColumnMetadata;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.SqlState;
import io.riverdb.server.app.GeneratedClientFileTestFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDriverTest {
  @Test
  void exposesAllocationFreeTransportCountersThroughJdbcUnwrap(@TempDir Path root)
      throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
        assertTrue(connection.isWrapperFor(RiverConnectionMetrics.class));
        RiverConnectionMetrics metrics = connection.unwrap(RiverConnectionMetrics.class);
        long requests = metrics.completedRequests();
        assertEquals(0, statement.executeUpdate("CHECKPOINT"));
        assertTrue(metrics.completedRequests() > requests);
        assertTrue(metrics.bytesSent() > 0);
        assertTrue(metrics.bytesReceived() > 0);
      }
    }
  }

  @Test
  void preparedHandleSendsSqlOnceAndSurvivesCatalogChange(@TempDir Path root)
      throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement ddl = connection.createStatement()) {
        ddl.executeUpdate("CREATE TABLE retained (id INTEGER PRIMARY KEY,value INTEGER)");
        RiverConnectionMetrics metrics = connection.unwrap(RiverConnectionMetrics.class);
        long requests = metrics.completedRequests();
        PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO retained VALUES (?,?)");
        assertEquals(requests + 1, metrics.completedRequests());

        insert.setInt(1, 1);
        insert.setInt(2, 10);
        long beforeExecuteBytes = metrics.bytesSent();
        assertEquals(1, insert.executeUpdate());
        assertEquals(requests + 2, metrics.completedRequests());
        long executeBytes = metrics.bytesSent() - beforeExecuteBytes;
        ddl.executeUpdate("CREATE INDEX retained_value ON retained(value)");
        insert.setInt(1, 2);
        insert.setInt(2, 20);
        beforeExecuteBytes = metrics.bytesSent();
        assertEquals(1, insert.executeUpdate());
        assertEquals(requests + 4, metrics.completedRequests());
        assertEquals(executeBytes, metrics.bytesSent() - beforeExecuteBytes);
        insert.close();
        assertEquals(requests + 5, metrics.completedRequests());
      }
    }
  }

  @Test
  void readCommittedRefreshesEachNestedQueryStatement(@TempDir Path root)
      throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      String query = "SELECT id FROM isolation_values WHERE value="
          + "(SELECT value FROM isolation_values WHERE id=1) ORDER BY id";

      try (Connection setup = DriverManager.getConnection(fixture.url());
          Statement statement = setup.createStatement()) {
        assertEquals(0, statement.executeUpdate(
            "CREATE TABLE isolation_values "
                + "(id BIGINT PRIMARY KEY, value BIGINT)"));
        assertEquals(2, statement.executeUpdate(
            "INSERT INTO isolation_values VALUES (1, 10), (2, 20)"));
      }
      try (Connection reader = DriverManager.getConnection(fixture.url());
          Connection writer = DriverManager.getConnection(fixture.url());
          Statement reads = reader.createStatement();
          Statement writes = writer.createStatement()) {
        reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        reader.setAutoCommit(false);
        assertEquals(Connection.TRANSACTION_READ_COMMITTED,
            reader.getTransactionIsolation());
        assertQueryKeys(reads, query, 1);
        assertEquals(1, writes.executeUpdate(
            "UPDATE isolation_values SET value=20 WHERE id=1"));
        assertQueryKeys(reads, query, 1, 2);
        reader.rollback();

        reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        assertQueryKeys(reads, query, 1, 2);
        assertEquals(1, writes.executeUpdate(
            "UPDATE isolation_values SET value=30 WHERE id=1"));
        assertQueryKeys(reads, query, 1, 2);
        reader.rollback();
        reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        assertQueryKeys(reads, query, 1);
        reader.rollback();
      }
    }
  }

  private static void assertQueryKeys(
      Statement statement,
      String query,
      long... expectedKeys) throws SQLException {
    try (ResultSet rows = statement.executeQuery(query)) {
      for (long expected : expectedKeys) {
        assertTrue(rows.next());
        assertEquals(expected, rows.getLong(1));
      }
      assertFalse(rows.next());
    }
  }

  @Test
  void retainsColumnMetadataAcrossGeometricGrowth(@TempDir Path root) throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
        assertEquals(
            0,
            statement.executeUpdate(
                "CREATE TABLE wide_metadata (id BIGINT PRIMARY KEY, "
                    + "c1 BIGINT, c2 BIGINT NOT NULL, c3 BIGINT, c4 BIGINT NOT NULL, "
                    + "c5 BIGINT, c6 BIGINT NOT NULL, c7 BIGINT, c8 VARCHAR(7))"));
        try (ResultSet columns = connection.getMetaData().getColumns(
            null, null, "wide_metadata", "%")) {
          assertColumnMetadata(columns, "wide_metadata", "id", Types.BIGINT, 1, false);
          for (int column = 1; column < 8; column++) {
            assertColumnMetadata(
                columns,
                "wide_metadata",
                "c" + column,
                Types.BIGINT,
                column + 1,
                (column & 1) != 0);
          }
          assertColumnMetadata(
              columns, "wide_metadata", "c8", Types.VARCHAR, 9, true);
          assertFalse(columns.next());
        }
      }
    }
  }

  @Test
  void reportsBoundedSubsetAndStableSqlStates(@TempDir Path root) throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 4)) {

      SQLException badUrl = assertThrows(
          SQLException.class,
          () -> DriverManager.getConnection("jdbc:river://localhost:not-a-port"));
      assertEquals("08001", badUrl.getSQLState());
      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
        DatabaseMetaData metadata = connection.getMetaData();
        assertEquals("River", metadata.getDatabaseProductName());
        assertEquals("River JDBC", metadata.getDriverName());
        assertEquals(fixture.url(), metadata.getURL());
        assertEquals(connection, metadata.getConnection());
        assertEquals(4, metadata.getJDBCMajorVersion());
        assertEquals(3, metadata.getJDBCMinorVersion());
        assertTrue(metadata.supportsTransactions());
        assertTrue(metadata.supportsTransactionIsolationLevel(
            Connection.TRANSACTION_READ_COMMITTED));
        assertTrue(metadata.supportsTransactionIsolationLevel(
            Connection.TRANSACTION_REPEATABLE_READ));
        assertTrue(metadata.supportsTransactionIsolationLevel(
            Connection.TRANSACTION_SERIALIZABLE));
        assertTrue(metadata.supportsBatchUpdates());
        assertTrue(metadata.supportsSavepoints());
        assertTrue(metadata.supportsGetGeneratedKeys());
        assertTrue(metadata.supportsResultSetConcurrency(
            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        assertFalse(metadata.supportsResultSetConcurrency(
            ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
        assertEquals(1_024, metadata.getMaxColumnsInTable());
        assertEquals(1_664, metadata.getMaxColumnsInSelect());
        assertEquals(32, metadata.getMaxColumnsInIndex());
        assertEquals(64, metadata.getMaxTableNameLength());
        try (ResultSet tables = metadata.getTables(null, null, null, null)) {
          assertFalse(tables.next());
        }
        try (Statement secondStatement = connection.createStatement()) {
          assertFalse(secondStatement.isClosed());
        }
        SQLException invalidSql = assertThrows(
            SQLException.class,
            () -> statement.executeUpdate("NOT SQL"));
        assertEquals("22000", invalidSql.getSQLState());
        assertThrows(java.sql.SQLFeatureNotSupportedException.class, () -> {
          connection.setReadOnly(true);
        });
      }
    }
  }

  @Test
  void rollsBackAndReleasesJdbcSavepoints(@TempDir Path root) throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {

      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
        assertEquals(
            0,
            statement.executeUpdate(
                "CREATE TABLE savepoint_rows (id BIGINT PRIMARY KEY, value BIGINT)"));
        connection.setAutoCommit(false);
        assertEquals(
            1,
            statement.executeUpdate(
                "INSERT INTO savepoint_rows VALUES (1, 10)"));
        String longName = "before second row ".repeat(20);
        Savepoint named = connection.setSavepoint(longName);
        assertEquals(longName, named.getSavepointName());
        assertThrows(SQLException.class, named::getSavepointId);
        assertEquals(
            1,
            statement.executeUpdate(
                "INSERT INTO savepoint_rows VALUES (2, 20)"));
        Savepoint unnamed = connection.setSavepoint();
        assertTrue(unnamed.getSavepointId() > 0);
        assertThrows(SQLException.class, unnamed::getSavepointName);
        assertEquals(
            1,
            statement.executeUpdate(
                "INSERT INTO savepoint_rows VALUES (3, 30)"));
        Savepoint third = connection.setSavepoint("before fourth row");
        Savepoint fourth = connection.setSavepoint();
        assertTrue(fourth.getSavepointId() > 0);
        assertEquals(
            1,
            statement.executeUpdate(
                "INSERT INTO savepoint_rows VALUES (4, 40)"));
        connection.rollback(unnamed);
        assertQueryKeys(
            statement,
            "SELECT id FROM savepoint_rows ORDER BY id",
            1,
            2);
        assertThrows(SQLException.class, () -> connection.rollback(third));
        connection.releaseSavepoint(unnamed);
        connection.rollback(named);
        assertQueryKeys(
            statement,
            "SELECT id FROM savepoint_rows ORDER BY id",
            1);
        connection.releaseSavepoint(named);
        connection.commit();
        assertThrows(SQLException.class, () -> connection.rollback(unnamed));
        assertQueryKeys(
            statement,
            "SELECT id FROM savepoint_rows ORDER BY id",
            1);
      }
    }
  }

  @Test
  void preparedStatementsSendBoundedTypedParameters(@TempDir Path root)
      throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {

      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement schema = connection.createStatement()) {
        assertEquals(0, schema.executeUpdate(
            "CREATE TABLE prepared_values (id BIGINT PRIMARY KEY, value BIGINT)"));
      }
      try (Connection connection = DriverManager.getConnection(fixture.url());
          PreparedStatement insert = connection.prepareStatement(
              "INSERT INTO prepared_values VALUES (?, ?)")) {
        insert.setLong(1, 1);
        SQLException unset = assertThrows(SQLException.class, insert::executeUpdate);
        assertEquals("07001", unset.getSQLState());
        insert.setLong(2, 100);
        assertEquals(1, insert.executeUpdate());
        insert.setObject(1, Integer.valueOf(2), Types.BIGINT);
        insert.setLong(2, 200);
        assertEquals(1, insert.executeUpdate());
        insert.setLong(1, 3);
        insert.setLong(2, Long.MIN_VALUE);
        assertEquals(1, insert.executeUpdate());
        insert.setString(1, "1 OR 1=1");
        SQLException mismatch = assertThrows(SQLException.class, insert::executeUpdate);
        assertEquals(SqlState.DATATYPE_MISMATCH, mismatch.getSQLState());
        insert.clearParameters();
        assertThrows(SQLException.class, () -> insert.setLong(3, 3));
      }
      try (Connection connection = DriverManager.getConnection(fixture.url());
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
      try (Connection connection = DriverManager.getConnection(fixture.url());
          PreparedStatement select = connection.prepareStatement(
              "SELECT id FROM prepared_values WHERE value>=? ORDER BY id")) {
        select.setLong(1, 100);
        try (ResultSet result = select.executeQuery()) {
          assertTrue(result.next());
          assertEquals(1, result.getLong(1));
          assertTrue(result.next());
          assertEquals(2, result.getLong(1));
          assertFalse(result.next());
        }
      }
      try (Connection connection = DriverManager.getConnection(fixture.url());
          PreparedStatement select = connection.prepareStatement(
              "SELECT id FROM prepared_values WHERE id IN (?, ?) ORDER BY id")) {
        select.setLong(1, 3);
        select.setLong(2, 1);
        try (ResultSet result = select.executeQuery()) {
          assertTrue(result.next());
          assertEquals(1, result.getLong(1));
          assertTrue(result.next());
          assertEquals(3, result.getLong(1));
          assertFalse(result.next());
        }
      }
      try (Connection connection = DriverManager.getConnection(fixture.url());
          PreparedStatement select = connection.prepareStatement(
              "SELECT id FROM prepared_values WHERE value BETWEEN ? AND ? ORDER BY id")) {
        select.setLong(1, 100);
        select.setLong(2, 200);
        try (ResultSet result = select.executeQuery()) {
          assertTrue(result.next());
          assertEquals(1, result.getLong(1));
          assertTrue(result.next());
          assertEquals(2, result.getLong(1));
          assertFalse(result.next());
        }
      }
    }
  }

  @Test
  void jdbcCarriesVarcharMetadataValuesAndPreparedParameters(@TempDir Path root)
      throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {

      try (Connection connection = DriverManager.getConnection(fixture.url())) {
        try (Statement schema = connection.createStatement()) {
          assertEquals(0, schema.executeUpdate(
              "CREATE TABLE text_values "
                  + "(id BIGINT PRIMARY KEY, label VARCHAR(32), "
                  + "state VARCHAR(12) DEFAULT '新規')"));
          assertEquals(0, schema.executeUpdate(
              "CREATE UNIQUE INDEX text_values_label ON text_values(label)"));
          assertEquals(1, schema.executeUpdate(
              "INSERT INTO text_values (id, label) VALUES (3, NULL)"));
        }

        try (Statement catalog = connection.createStatement();
            ResultSet indexes = catalog.executeQuery("SHOW INDEXES FROM text_values")) {
          ResultSetMetaData metadata = indexes.getMetaData();
          assertEquals(Types.VARCHAR, metadata.getColumnType(1));
          assertEquals(64, metadata.getPrecision(1));
          assertEquals(Types.BOOLEAN, metadata.getColumnType(3));
          assertEquals("BOOLEAN", metadata.getColumnTypeName(3));
          assertEquals(Boolean.class.getName(), metadata.getColumnClassName(3));
          assertTrue(indexes.next());
          assertEquals(Boolean.TRUE, indexes.getObject(3));
          assertEquals(Boolean.TRUE, indexes.getObject(3, Boolean.class));
        }
        try (Statement typed = connection.createStatement()) {
          SQLException mismatch = assertThrows(
              SQLException.class,
              () -> typed.executeQuery("SELECT SUM(label) FROM text_values"));
          assertEquals(SqlState.DATATYPE_MISMATCH, mismatch.getSQLState());
        }

        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO text_values (id, label) VALUES (?, ?)")) {
          insert.setLong(1, 1);
          insert.setString(2, "河川データ庫");
          assertEquals(1, insert.executeUpdate());
          insert.setLong(1, 2);
          insert.setObject(2, "alpha", Types.VARCHAR);
          assertEquals(1, insert.executeUpdate());
          insert.setLong(1, 4);
          insert.setString(2, "x".repeat(33));
          SQLException tooLong = assertThrows(SQLException.class, insert::executeUpdate);
          assertEquals(SqlState.DATATYPE_MISMATCH, tooLong.getSQLState());
        }

        try (PreparedStatement select = connection.prepareStatement(
            "SELECT id, label, state FROM text_values WHERE label=?")) {
          select.setString(1, "河川データ庫");
          try (ResultSet rows = select.executeQuery()) {
            ResultSetMetaData metadata = rows.getMetaData();
            assertEquals(Types.BIGINT, metadata.getColumnType(1));
            assertEquals(Types.VARCHAR, metadata.getColumnType(2));
            assertEquals("VARCHAR", metadata.getColumnTypeName(2));
            assertEquals(String.class.getName(), metadata.getColumnClassName(2));
            assertTrue(metadata.isCaseSensitive(2));
            assertFalse(metadata.isSigned(2));
            assertEquals(32, metadata.getPrecision(2));
            assertTrue(rows.next());
            assertEquals(1, rows.getLong("id"));
            assertEquals("河川データ庫", rows.getString("label"));
            assertEquals("河川データ庫", rows.getObject("label"));
            assertEquals("河川データ庫", rows.getObject("label", String.class));
            assertEquals("新規", rows.getString("state"));
            SQLException numeric = assertThrows(
                SQLException.class, () -> rows.getLong("label"));
            assertEquals("0A000", numeric.getSQLState());
            assertFalse(rows.next());
          }
        }

        try (Statement statement = connection.createStatement();
            ResultSet nullable = statement.executeQuery(
                "SELECT label FROM text_values WHERE id=3")) {
          assertTrue(nullable.next());
          assertNull(nullable.getString(1));
          assertTrue(nullable.wasNull());
          assertNull(nullable.getObject(1));
          assertTrue(nullable.wasNull());
          SQLException numeric = assertThrows(
              SQLException.class, () -> nullable.getLong(1));
          assertEquals("0A000", numeric.getSQLState());
        }
      }
    }
  }

  @Test
  void batchesAreBoundedAndReportTheSuccessfulPrefix(@TempDir Path root)
      throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {

      try (Connection connection = DriverManager.getConnection(fixture.url())) {
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
          assertEquals("23505", partial.getSQLState());

          for (int index = 0; index < 257; index++) {
            statement.addBatch("INSERT INTO batch_values VALUES (99, 99)");
          }
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
    }
  }

  @Test
  void reportsCheckConstraintSqlState(@TempDir Path root) throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
        assertEquals(
            0,
            statement.executeUpdate(
                "CREATE TABLE checked_values "
                    + "(id BIGINT PRIMARY KEY, value BIGINT CHECK (value >= 0))"));
        SQLException violation = assertThrows(
            SQLException.class,
            () -> statement.executeUpdate(
                "INSERT INTO checked_values VALUES (1, -1)"));
        assertEquals("23514", violation.getSQLState());
        assertEquals(StatusCode.CHECK_VIOLATION.stableCode(), violation.getErrorCode());
        assertEquals(
            1,
            statement.executeUpdate("INSERT INTO checked_values VALUES (1, 1)"));
      }
    }
  }

  @Test
  void enforcesUniqueColumnsThroughJdbc(@TempDir Path root) throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
        assertEquals(
            0,
            statement.executeUpdate(
                "CREATE TABLE unique_values "
                    + "(id BIGINT PRIMARY KEY, value BIGINT UNIQUE)"));
        assertEquals(
            1,
            statement.executeUpdate("INSERT INTO unique_values VALUES (1, 10)"));
        SQLException violation = assertThrows(
            SQLException.class,
            () -> statement.executeUpdate("INSERT INTO unique_values VALUES (2, 10)"));
        assertEquals("23505", violation.getSQLState());
        assertEquals(StatusCode.UNIQUE_VIOLATION.stableCode(), violation.getErrorCode());
        try (ResultSet rows = statement.executeQuery(
            "SELECT id FROM unique_values WHERE value=10")) {
          assertTrue(rows.next());
          assertEquals(1, rows.getLong(1));
          assertFalse(rows.next());
        }
      }
    }
  }

  @Test
  void reportsForeignKeySqlState(@TempDir Path root) throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
        assertEquals(
            0,
            statement.executeUpdate(
                "CREATE TABLE fk_parents (id BIGINT PRIMARY KEY, value BIGINT)"));
        assertEquals(
            0,
            statement.executeUpdate(
                "CREATE TABLE fk_children "
                    + "(id BIGINT PRIMARY KEY, parent_id BIGINT REFERENCES fk_parents(id))"));
        SQLException violation = assertThrows(
            SQLException.class,
            () -> statement.executeUpdate(
                "INSERT INTO fk_children VALUES (1, 99)"));
        assertEquals("23503", violation.getSQLState());
        assertEquals(StatusCode.FOREIGN_KEY_VIOLATION.stableCode(), violation.getErrorCode());
        assertEquals(
            1,
            statement.executeUpdate("INSERT INTO fk_parents VALUES (99, 10)"));
        assertEquals(
            1,
            statement.executeUpdate("INSERT INTO fk_children VALUES (1, 99)"));
        SQLException deleteViolation = assertThrows(
            SQLException.class,
            () -> statement.executeUpdate("DELETE FROM fk_parents WHERE id=99"));
        assertEquals("23503", deleteViolation.getSQLState());
      }
    }
  }

  @Test
  void returnsIdentityKeysThroughJdbc(@TempDir Path root) throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {

      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
        assertEquals(
            0,
            statement.executeUpdate(
                "CREATE TABLE generated_events "
                    + "(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                    + "payload BIGINT)"));
        assertEquals(
            1,
            statement.executeUpdate(
                "INSERT INTO generated_events(payload) VALUES (10)",
                Statement.RETURN_GENERATED_KEYS));
        try (ResultSet keys = statement.getGeneratedKeys()) {
          assertTrue(keys.next());
          assertEquals(1, keys.getLong(1));
          assertEquals(1L, keys.getObject("GENERATED_KEY", Long.class));
          assertTrue(keys.getMetaData().isAutoIncrement(1));
          assertFalse(keys.next());
        }

        connection.setAutoCommit(false);
        assertEquals(
            1,
            statement.executeUpdate(
                "INSERT INTO generated_events(payload) VALUES (20)",
                Statement.RETURN_GENERATED_KEYS));
        try (ResultSet keys = statement.getGeneratedKeys()) {
          assertTrue(keys.next());
          assertEquals(2, keys.getLong(1));
        }
        connection.rollback();
        connection.setAutoCommit(true);
        assertFalse(statement.execute(
            "INSERT INTO generated_events(payload) VALUES (30)",
            Statement.RETURN_GENERATED_KEYS));
        try (ResultSet keys = statement.getGeneratedKeys()) {
          assertTrue(keys.next());
          assertEquals(3, keys.getLong(1));
          assertFalse(keys.next());
        }
        assertEquals(
            1,
            statement.executeUpdate(
                "UPDATE generated_events SET payload=31 WHERE id=3",
                Statement.RETURN_GENERATED_KEYS));
        try (ResultSet keys = statement.getGeneratedKeys()) {
          assertFalse(keys.next());
        }
      }
      assertEquals(0, fixture.database().activeTransactionCount());
      assertEquals(0, fixture.database().activeLockCount());
      assertEquals(0, fixture.database().waitingLockCount());
      try (Connection connection = DriverManager.getConnection(fixture.url());
          PreparedStatement insert = connection.prepareStatement(
              "INSERT INTO generated_events(payload) VALUES (?)",
              Statement.RETURN_GENERATED_KEYS)) {
        insert.setLong(1, 40);
        assertEquals(1, insert.executeUpdate());
        try (ResultSet keys = insert.getGeneratedKeys()) {
          assertTrue(keys.next());
          assertEquals(4, keys.getLong(1));
          assertFalse(keys.next());
        }
      }
    }
  }

  @Test
  void dataSourceExecutesJdbcInsideTlsBoundTokenAuthentication(@TempDir Path root)
      throws Exception {
    GeneratedClientFileTestFixture fixture = GeneratedClientFileTestFixture.open(root);
    try {
      RiverDataSource source = new RiverDataSource();
      source.setClientFile(fixture.clientFile());
      assertEquals(5, source.getLoginTimeout());
      source.setLoginTimeout(5);
      assertThrows(
          java.sql.SQLFeatureNotSupportedException.class,
          () -> source.setLoginTimeout(0));
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

      fixture.replaceTokenWithWrongValue();
      RiverDataSource wrong = new RiverDataSource();
      wrong.setClientFile(fixture.clientFile());
      SQLException rejected = assertThrows(SQLException.class, wrong::getConnection);
      assertEquals("28000", rejected.getSQLState());
      wrong.close();
    } finally {
      assertEquals(StatusCode.OK, fixture.close());
    }
  }

  @Test
  void streamsExplainAndAnalyzePlansThroughJdbc(@TempDir Path root)
      throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE planned "
                + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)");
        statement.executeUpdate(
            "CREATE INDEX planned_category ON planned(category)");
        statement.executeUpdate(
            "INSERT INTO planned VALUES (1,7,10),(2,7,20),(3,8,30),(4,9,40)");
        statement.executeUpdate(
            "CREATE TABLE plan_labels "
                + "(id BIGINT PRIMARY KEY, category BIGINT, code BIGINT)");
        statement.executeUpdate(
            "INSERT INTO plan_labels VALUES (1,7,70),(2,7,71),(3,8,80)");
        try (ResultSet joined = statement.executeQuery(
            "SELECT planned.id, plan_labels.code FROM planned "
                + "JOIN plan_labels ON planned.category=plan_labels.category "
                + "WHERE planned.id=1")) {
          assertTrue(joined.next());
          assertEquals(70, joined.getLong(2));
          assertTrue(joined.next());
          assertEquals(71, joined.getLong(2));
          assertFalse(joined.next());
        }
        try (ResultSet joined = statement.executeQuery(
            "SELECT planned.id, plan_labels.code FROM planned "
                + "LEFT JOIN plan_labels ON planned.category=plan_labels.category "
                + "WHERE planned.id=4 AND plan_labels.code IS NULL")) {
          assertTrue(joined.next());
          assertEquals(4, joined.getLong(1));
          assertNull(joined.getObject(2));
          assertFalse(joined.next());
        }
        try (ResultSet joined = statement.executeQuery(
            "SELECT planned.id, plan_labels.code FROM planned "
                + "LEFT JOIN plan_labels ON planned.category=plan_labels.category "
                + "WHERE planned.id=1 AND plan_labels.code=70")) {
          assertTrue(joined.next());
          assertEquals(1, joined.getLong(1));
          assertEquals(70, joined.getLong(2));
          assertFalse(joined.next());
        }
        try (ResultSet plan = statement.executeQuery(
            "EXPLAIN SELECT id FROM planned WHERE category=7")) {
          ResultSetMetaData metadata = plan.getMetaData();
          assertEquals(3, metadata.getColumnCount());
          assertEquals("operator", metadata.getColumnName(1));
          assertEquals("detail", metadata.getColumnName(2));
          assertEquals("rows", metadata.getColumnName(3));
          assertTrue(plan.next());
          assertEquals("filter", plan.getString("operator"));
          assertNull(plan.getObject("rows"));
          assertEquals(1, plan.getLong("detail"));
          assertTrue(plan.next());
          assertEquals("index", plan.getString("operator"));
          assertNull(plan.getObject("rows"));
          assertEquals(1, plan.getLong("detail"));
          assertFalse(plan.next());
        }
        try (ResultSet plan = statement.executeQuery(
            "EXPLAIN ANALYZE SELECT id FROM planned WHERE category=7")) {
          assertTrue(plan.next());
          assertEquals("filter", plan.getString(1));
          assertEquals(2, plan.getLong(3));
          assertTrue(plan.next());
          assertEquals("index", plan.getString(1));
          assertNull(plan.getObject(3));
          assertFalse(plan.next());
        }
        try (ResultSet selected = statement.executeQuery(
            "SELECT id FROM planned "
                + "WHERE category=7 OR amount>=40 ORDER BY id")) {
          for (long id : new long[] {1, 2, 4}) {
            assertTrue(selected.next());
            assertEquals(id, selected.getLong(1));
          }
          assertFalse(selected.next());
        }
        assertEquals(
            2,
            statement.executeUpdate(
                "UPDATE planned SET amount=99 WHERE id=1 OR id=3"));
        try (ResultSet selected = statement.executeQuery(
            "SELECT COUNT(*) FROM planned WHERE amount=99")) {
          assertTrue(selected.next());
          assertEquals(2, selected.getLong(1));
          assertFalse(selected.next());
        }
        assertEquals(
            0,
            statement.executeUpdate(
                "CREATE VIEW expensive_plans AS "
                    + "SELECT id, category AS kind, amount FROM planned "
                    + "WHERE amount>=90"));
        try (ResultSet selected = statement.executeQuery(
            "SELECT id, amount FROM expensive_plans "
                + "WHERE kind=7 ORDER BY id")) {
          assertTrue(selected.next());
          assertEquals(1, selected.getLong(1));
          assertEquals(99, selected.getLong(2));
          assertFalse(selected.next());
        }
        assertEquals(0, statement.executeUpdate("DROP VIEW expensive_plans"));
      }
    }
  }

  @Test
  void reportsCompositeNumericPrimaryKeysAndNoKeyForKeylessTables(
      @TempDir Path root) throws SQLException {
    try (RiverDriverTestFixture fixture = RiverDriverTestFixture.open(root, 8)) {
      try (Connection connection = DriverManager.getConnection(fixture.url());
          Statement statement = connection.createStatement()) {
        assertEquals(0, statement.executeUpdate(
            "CREATE TABLE composite_metadata (tenant INTEGER,"
                + "amount DECIMAL(22,18),payload BIGINT,"
                + "CONSTRAINT composite_metadata_pk PRIMARY KEY(amount,tenant))"));
        assertEquals(0, statement.executeUpdate(
            "CREATE TABLE keyless_metadata (tenant INTEGER,amount DECIMAL(22,18))"));
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet keys = metadata.getPrimaryKeys(
            null, null, "composite_metadata")) {
          assertTrue(keys.next());
          assertEquals("amount", keys.getString("COLUMN_NAME"));
          assertEquals(1, keys.getShort("KEY_SEQ"));
          assertEquals("composite_metadata_pk", keys.getString("PK_NAME"));
          assertTrue(keys.next());
          assertEquals("tenant", keys.getString("COLUMN_NAME"));
          assertEquals(2, keys.getShort("KEY_SEQ"));
          assertEquals("composite_metadata_pk", keys.getString("PK_NAME"));
          assertFalse(keys.next());
        }
        try (ResultSet keys = metadata.getPrimaryKeys(
            null, null, "keyless_metadata")) {
          assertFalse(keys.next());
        }
      }
    }
  }

}
