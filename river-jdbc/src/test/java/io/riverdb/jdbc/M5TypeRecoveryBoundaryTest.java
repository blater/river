package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.backup.BackupResult;
import io.riverdb.backup.OfflineDatabaseBackup;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.cli.RiverSqlMain;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.LoopbackServerOpenResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One database lineage through the complete M5 typed recovery boundary. */
final class M5TypeRecoveryBoundaryTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4d35545950454741L, 0x5445303030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final LocalDate DAY = LocalDate.of(1969, 12, 31);
  private static final LocalTime CLOCK = LocalTime.of(23, 59, 58, 123_456_000);
  private static final LocalDateTime OBSERVED = LocalDateTime.of(DAY, CLOCK);
  private static final OffsetDateTime CAPTURED =
      OffsetDateTime.of(OBSERVED, ZoneOffset.ofHours(1));

  @Test
  void preservesOneAuthenticatedTypedLineageThroughRecoveryAndFault(
      @TempDir Path root) throws Exception {
    Path source = Files.createDirectory(root.resolve("source"));
    Path sourceAudit = Files.createDirectory(root.resolve("audit-source"));
    Path backupDirectory = Files.createDirectory(root.resolve("backup"));
    Path restored = Files.createDirectory(root.resolve("restored"));
    Path restoredAudit = Files.createDirectory(root.resolve("audit-restored"));
    Path failedRestore = Files.createDirectory(root.resolve("failed-restore"));
    byte[] token = "m5-useful-sql-boundary-token".getBytes(StandardCharsets.UTF_8);

    createAuthenticatedCheckpointLineage(source, sourceAudit, token);
    assertEmbeddedReopen(source);

    OfflineDatabaseBackup backup = new OfflineDatabaseBackup();
    BackupResult backupResult = new BackupResult();
    assertEquals(
        StatusCode.OK,
        backup.create(source, backupDirectory, backupResult));
    assertTrue(backupResult.isComplete());
    assertEquals(DATABASE, backupResult.database());
    assertEquals(GENERATION, backupResult.walGeneration());
    assertTrue(backupResult.fileCount() >= 4);
    assertTrue(backupResult.totalBytes() > 0);

    BackupResult restoreResult = new BackupResult();
    assertEquals(
        StatusCode.OK,
        backup.restore(backupDirectory, restored, restoreResult));
    assertTrue(restoreResult.isComplete());
    assertEquals(backupResult.fileCount(), restoreResult.fileCount());
    assertEquals(backupResult.totalBytes(), restoreResult.totalBytes());
    assertAuthenticatedRestoredBoundary(restored, restoredAudit, token);

    Path pages = backupDirectory.resolve("river.indexed.pages");
    byte[] pageBytes = Files.readAllBytes(pages);
    pageBytes[pageBytes.length - 1] ^= 0x5a;
    Files.write(pages, pageBytes);
    assertEquals(
        StatusCode.CORRUPTION,
        backup.restore(backupDirectory, failedRestore, restoreResult));
    assertFalse(restoreResult.isComplete());
    Arrays.fill(pageBytes, (byte) 0);
    Arrays.fill(token, (byte) 0);
  }

  private static void createAuthenticatedCheckpointLineage(
      Path source, Path audit, byte[] token) throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(source, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = startAuthenticated(database, audit, token);
    RiverDataSource dataSource = dataSource(server, token);
    try (Connection connection = dataSource.getConnection()) {
      createSchema(connection);
      insertRows(connection);
      assertCheckViolation(connection);
      try (Statement statement = connection.createStatement()) {
        assertEquals(0, statement.executeUpdate("CHECKPOINT"));
        assertEquals(
            1,
            statement.executeUpdate(
                "UPDATE m5_types SET label='after猫' WHERE id=1"));
      }
    }
    dataSource.close();
    awaitConnections(server, 0);
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createSchema(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      assertEquals(
          0,
          statement.executeUpdate(
              "CREATE TABLE m5_types (id BIGINT PRIMARY KEY,"
                  + "flag BOOLEAN DEFAULT TRUE,"
                  + "amount DECIMAL(8,3) CHECK(amount>=0),"
                  + "label VARCHAR(32),day DATE,clock TIME(6),"
                  + "observed TIMESTAMP(6),"
                  + "captured TIMESTAMP(6) WITH TIME ZONE)"));
      assertEquals(
          0,
          statement.executeUpdate("CREATE INDEX m5_day ON m5_types(day)"));
    }
  }

  private static void insertRows(Connection connection) throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO m5_types VALUES (?,?,?,?,?,?,?,?)")) {
      insert.setLong(1, 1);
      insert.setBoolean(2, true);
      insert.setBigDecimal(3, new BigDecimal("42.700"));
      insert.setString(4, "before猫");
      insert.setObject(5, DAY);
      insert.setObject(6, CLOCK);
      insert.setObject(7, OBSERVED);
      insert.setObject(8, CAPTURED);
      assertEquals(1, insert.executeUpdate());
    }
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO m5_types(id,amount,label,day,clock,observed,captured) "
            + "VALUES (?,?,?,?,?,?,?)")) {
      insert.setLong(1, 2);
      insert.setNull(2, Types.DECIMAL);
      insert.setString(3, null);
      insert.setObject(4, null, Types.DATE);
      insert.setNull(5, Types.TIME);
      insert.setObject(6, null, Types.TIMESTAMP);
      insert.setObject(7, null, Types.TIMESTAMP_WITH_TIMEZONE);
      assertEquals(1, insert.executeUpdate());
    }
  }

  private static void assertCheckViolation(Connection connection)
      throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO m5_types VALUES (?,?,?,?,?,?,?,?)")) {
      insert.setLong(1, 3);
      insert.setBoolean(2, false);
      insert.setBigDecimal(3, new BigDecimal("-0.001"));
      insert.setString(4, null);
      insert.setObject(5, null, Types.DATE);
      insert.setNull(6, Types.TIME);
      insert.setObject(7, null, Types.TIMESTAMP);
      insert.setObject(8, null, Types.TIMESTAMP_WITH_TIMEZONE);
      SQLException failure = assertThrows(SQLException.class, insert::executeUpdate);
      assertEquals("23514", failure.getSQLState());
    }
  }

  private static void assertEmbeddedReopen(Path source) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(source, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id,flag,amount,label,day,clock,observed,captured "
                + "FROM m5_types ORDER BY id",
            queryResult));
    RiverQuery query = queryResult.query();
    assertDescriptors(query);
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEmbeddedValueRow(row);
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(2, row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertEquals(0xfcL, row.nullMask());
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertDescriptors(RiverQuery query) {
    int[] expected = {
      SqlTypeDescriptor.BIGINT,
      SqlTypeDescriptor.BOOLEAN,
      SqlTypeDescriptor.decimal(8, 3),
      SqlTypeDescriptor.varchar(32),
      SqlTypeDescriptor.DATE,
      SqlTypeDescriptor.time(6),
      SqlTypeDescriptor.timestamp(6),
      SqlTypeDescriptor.timestampWithTimeZone(6)
    };
    assertEquals(expected.length, query.columnCount());
    for (int index = 0; index < expected.length; index++) {
      assertEquals(expected[index], query.columnTypeDescriptor(index));
    }
  }

  private static void assertEmbeddedValueRow(RowResult row) {
    assertTrue(row.isAvailable());
    assertEquals(0, row.nullMask());
    assertEquals(1, row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertEquals(42_700, row.valueAt(2));
    assertEquals("after猫", text(row, 3));
    assertEquals(DAY.toEpochDay(), row.valueAt(4));
    assertEquals(CLOCK.toNanoOfDay() / 1_000, row.valueAt(5));
    assertEquals(localMicros(OBSERVED), row.valueAt(6));
    assertEquals(instantMicros(CAPTURED), row.valueAt(7));
  }

  private static void assertAuthenticatedRestoredBoundary(
      Path restored, Path audit, byte[] token) throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(restored, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = startAuthenticated(database, audit, token);
    RiverDataSource dataSource = dataSource(server, token);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(
            "SELECT id,flag,amount,label,day,clock,observed,captured "
                + "FROM m5_types ORDER BY id")) {
      assertRestoredMetadata(rows.getMetaData());
      assertTrue(rows.next());
      assertEquals(1, rows.getLong(1));
      assertTrue(rows.getBoolean(2));
      assertEquals(new BigDecimal("42.700"), rows.getBigDecimal(3));
      assertEquals("after猫", rows.getString(4));
      assertEquals(DAY, rows.getObject(5));
      assertEquals(CLOCK, rows.getObject(6));
      assertEquals(OBSERVED, rows.getObject(7));
      assertEquals(CAPTURED.withOffsetSameInstant(ZoneOffset.UTC), rows.getObject(8));
      assertTrue(rows.next());
      assertEquals(2, rows.getLong(1));
      assertTrue(rows.getBoolean(2));
      for (int column = 3; column <= 8; column++) {
        assertNull(rows.getObject(column));
        assertTrue(rows.wasNull());
      }
      assertFalse(rows.next());
    }
    awaitConnections(server, 0);
    assertCliRows(server, token);
    awaitConnections(server, 0);
    assertDisconnectRollsBackTypedMutation(server, dataSource);
    dataSource.close();

    server = startAuthenticated(database, audit, token);
    RiverDataSource reopened = dataSource(server, token);
    assertTypedMutationWasNotPublished(reopened);
    reopened.close();
    awaitConnections(server, 0);
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertDisconnectRollsBackTypedMutation(
      LoopbackRiverServer server, RiverDataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement mutation = connection.prepareStatement(
            "UPDATE m5_types SET amount=? WHERE id=1");
        Statement probe = connection.createStatement()) {
      connection.setAutoCommit(false);
      mutation.setBigDecimal(1, new BigDecimal("99.999"));
      assertEquals(1, mutation.executeUpdate());
      assertEquals(StatusCode.OK, server.close());
      awaitConnections(server, 0);
      SQLException disconnected = assertThrows(
          SQLException.class,
          () -> probe.executeQuery(
              "SELECT amount FROM m5_types WHERE id=1"));
      assertEquals("08006", disconnected.getSQLState());
      SQLException rollback = assertThrows(SQLException.class, connection::rollback);
      assertEquals("08006", rollback.getSQLState());
    }
  }

  private static void assertTypedMutationWasNotPublished(RiverDataSource dataSource)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet row = statement.executeQuery(
            "SELECT amount,label,captured FROM m5_types WHERE id=1")) {
      assertTrue(row.next());
      assertEquals(new BigDecimal("42.700"), row.getBigDecimal(1));
      assertEquals("after猫", row.getString(2));
      assertEquals(
          CAPTURED.withOffsetSameInstant(ZoneOffset.UTC), row.getObject(3));
      assertFalse(row.next());
    }
  }

  private static void assertRestoredMetadata(ResultSetMetaData metadata)
      throws SQLException {
    int[] types = {
      Types.BIGINT,
      Types.BOOLEAN,
      Types.DECIMAL,
      Types.VARCHAR,
      Types.DATE,
      Types.TIME,
      Types.TIMESTAMP,
      Types.TIMESTAMP_WITH_TIMEZONE
    };
    assertEquals(types.length, metadata.getColumnCount());
    for (int column = 1; column <= types.length; column++) {
      assertEquals(types[column - 1], metadata.getColumnType(column));
      assertEquals(
          column == 1
              ? ResultSetMetaData.columnNoNulls
              : ResultSetMetaData.columnNullable,
          metadata.isNullable(column));
    }
    assertEquals(3, metadata.getScale(3));
    assertEquals(6, metadata.getScale(6));
    assertEquals(6, metadata.getScale(7));
    assertEquals(6, metadata.getScale(8));
  }

  private static void assertCliRows(LoopbackRiverServer server, byte[] token)
      throws Exception {
    String script = "SELECT id,flag,amount,label,day,clock,observed,captured "
        + "FROM m5_types ORDER BY id;";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    int exit = RiverSqlMain.runAuthenticated(
        server.port(),
        TestTlsContexts.trustedClient(),
        token,
        token.length,
        new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(errors, true, StandardCharsets.UTF_8));
    assertEquals(0, exit);
    assertEquals("", errors.toString(StandardCharsets.UTF_8));
    assertEquals(
        "id\tflag\tamount\tlabel\tday\tclock\tobserved\tcaptured\n"
            + "1\tTRUE\t42.700\tafter猫\t1969-12-31\t23:59:58.123456\t"
            + "1969-12-31 23:59:58.123456\t"
            + "1969-12-31 22:59:58.123456+00:00\n"
            + "2\tTRUE\tNULL\tNULL\tNULL\tNULL\tNULL\tNULL\nROWS\t2\n",
        output.toString(StandardCharsets.UTF_8));
  }

  private static LoopbackRiverServer startAuthenticated(
      RiverDatabase database, Path audit, byte[] token) throws Exception {
    TokenAuthenticatorOpenResult authenticated = new TokenAuthenticatorOpenResult();
    assertEquals(
        StatusCode.OK,
        TokenAuthenticator.create(token, token.length, authenticated));
    LoopbackServerOpenResult listener = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.startAuthenticated(
            database,
            0,
            TestTlsContexts.server(),
            authenticated.authenticator(),
            audit,
            LoopbackServerLimits.defaults(8),
            listener));
    return listener.server();
  }

  private static RiverDataSource dataSource(
      LoopbackRiverServer server, byte[] token) throws Exception {
    RiverDataSource source = new RiverDataSource();
    source.setPort(server.port());
    source.setAuthentication(
        TestTlsContexts.trustedClient(), token, token.length);
    return source;
  }

  private static void awaitConnections(
      LoopbackRiverServer server, int expected) throws InterruptedException {
    long deadline = System.nanoTime() + 3_000_000_000L;
    while (server.activeConnections() != expected
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(expected, server.activeConnections());
  }

  private static long localMicros(LocalDateTime value) {
    return value.toLocalDate().toEpochDay() * LocalTemporal.MICROSECONDS_PER_DAY
        + value.toLocalTime().toNanoOfDay() / 1_000;
  }

  private static long instantMicros(OffsetDateTime value) {
    long seconds = value.toEpochSecond();
    return seconds * LocalTemporal.MICROSECONDS_PER_SECOND
        + value.getNano() / 1_000;
  }

  private static String text(RowResult row, int column) {
    char[] characters = new char[32];
    int length = row.copyTextAt(column, characters, 0);
    return new String(characters, 0, length);
  }
}
