package io.riverdb.backup;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.sql.SqlExecutionResult;
import io.riverdb.engine.sql.SqlScanCursor;
import io.riverdb.engine.sql.SqlScanRowResult;
import io.riverdb.engine.sql.SqlSession;
import io.riverdb.engine.sql.SqlSessionOpenResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OfflineDatabaseBackupTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4241434b55505445L, 0x5354444230303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void backsUpRestoresAndRejectsCorruptOrOccupiedTargets(@TempDir Path root)
      throws IOException {
    Path source = Files.createDirectory(root.resolve("source"));
    Path backupDirectory = Files.createDirectory(root.resolve("backup"));
    Path restored = Files.createDirectory(root.resolve("restored"));
    Path occupied = Files.createDirectory(root.resolve("occupied"));
    Path corruptRestore = Files.createDirectory(root.resolve("corrupt-restore"));

    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            databaseRequest(8), source, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult command = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE accounts "
                + "(id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT, "
                + "label VARCHAR(32))",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO accounts VALUES "
                + "(1, 100, 7, '東京支店'), "
                + "(2, 250, 7, '河川データ庫'), "
                + "(3, 300, 8, 'north')",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE regions (id BIGINT PRIMARY KEY,label VARCHAR(32),"
                + "country BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO regions VALUES (7,'東京地域',81),(8,'north',44)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE countries (id BIGINT PRIMARY KEY,label VARCHAR(32))",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO countries VALUES (81,'日本'),(44,'Britain')",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE temporal_archive ("
                + "id BIGINT PRIMARY KEY, day DATE, alarm TIME(3), "
                + "observed TIMESTAMP(6) "
                + "CHECK (EXTRACT(YEAR FROM observed)>=1969), "
                + "captured TIMESTAMP(6) WITH TIME ZONE)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO temporal_archive VALUES "
                + "(1, DATE '1969-12-31', TIME '01:02:03.456', "
                + "TIMESTAMP '1969-12-31 23:59:59.123456', "
                + "TIMESTAMP WITH TIME ZONE '1970-01-01 01:30:00+01:30'), "
                + "(2, NULL, NULL, NULL, NULL)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW unicode_totals AS SELECT region,SUM(balance) AS total "
                + "FROM accounts WHERE label='河川データ庫' GROUP BY region",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW joined_accounts AS SELECT a.id AS account_id,"
                + "a.balance AS balance,r.label AS region_label "
                + "FROM accounts a JOIN regions r ON a.region=r.id "
                + "JOIN countries c ON r.country=c.id",
            command));
    assertEquals(StatusCode.OK, session.execute("ANALYZE accounts", command));
    assertEquals(StatusCode.OK, session.execute("ANALYZE regions", command));
    assertEquals(StatusCode.OK, session.execute("ANALYZE countries", command));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

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
    assertTrue(Files.isRegularFile(
        backupDirectory.resolve(OfflineDatabaseBackup.MANIFEST_FILE_NAME)));

    BackupResult restoreResult = new BackupResult();
    assertEquals(
        StatusCode.OK,
        backup.restore(backupDirectory, restored, restoreResult));
    assertTrue(restoreResult.isComplete());
    assertEquals(backupResult.fileCount(), restoreResult.fileCount());
    assertEquals(backupResult.totalBytes(), restoreResult.totalBytes());
    assertFalse(Files.exists(restored.resolve(OfflineDatabaseBackup.MANIFEST_FILE_NAME)));

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(
            databaseRequest(8), restored, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT balance, label FROM accounts WHERE id=2", command));
    assertTrue(command.hasValue());
    assertEquals(250, command.valueAt(0));
    char[] restoredLabel = new char[32];
    int restoredLabelLength = command.copyTextAt(1, restoredLabel, 0);
    assertEquals(6, restoredLabelLength);
    assertEquals("河川データ庫", new String(restoredLabel, 0, restoredLabelLength));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT total FROM unicode_totals WHERE region=7", command));
    assertEquals(250, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT balance,region_label FROM joined_accounts "
                + "WHERE account_id=2",
            command));
    assertEquals(250, command.valueAt(0));
    char[] joinedLabel = new char[32];
    int joinedLabelLength = command.copyTextAt(1, joinedLabel, 0);
    assertEquals(4, joinedLabelLength);
    assertEquals("東京地域", new String(joinedLabel, 0, joinedLabelLength));
    assertRestoredStatisticsPlan(session, command);
    assertEquals(StatusCode.OK, session.execute("DROP VIEW unicode_totals", command));
    assertEquals(StatusCode.CONFLICT, session.execute("DROP TABLE accounts", command));
    assertEquals(StatusCode.CONFLICT, session.execute("DROP TABLE regions", command));
    assertEquals(StatusCode.CONFLICT, session.execute("DROP TABLE countries", command));
    assertEquals(StatusCode.OK, session.execute("DROP VIEW joined_accounts", command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT day, alarm, observed, captured FROM temporal_archive WHERE id=1",
            command));
    assertEquals(SqlTypeDescriptor.DATE, command.typeDescriptorAt(0));
    assertEquals(-1, command.valueAt(0));
    assertEquals(SqlTypeDescriptor.time(3), command.typeDescriptorAt(1));
    assertEquals(3_723_456_000L, command.valueAt(1));
    assertEquals(SqlTypeDescriptor.timestamp(6), command.typeDescriptorAt(2));
    assertEquals(-876_544, command.valueAt(2));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), command.typeDescriptorAt(3));
    assertEquals(0, command.valueAt(3));
    assertEquals(0, command.nullMask());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT day, alarm, observed, captured FROM temporal_archive WHERE id=2",
            command));
    assertEquals(SqlTypeDescriptor.DATE, command.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.time(3), command.typeDescriptorAt(1));
    assertEquals(SqlTypeDescriptor.timestamp(6), command.typeDescriptorAt(2));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), command.typeDescriptorAt(3));
    assertEquals(0b1111, command.nullMask());
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO temporal_archive VALUES (3, NULL, NULL, "
                + "TIMESTAMP '1968-12-31 00:00:00', NULL)",
            command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    Path sentinel = occupied.resolve("keep.me");
    Files.write(sentinel, new byte[] {1, 2, 3});
    assertEquals(
        StatusCode.CONFLICT,
        backup.restore(backupDirectory, occupied, restoreResult));
    assertTrue(Files.exists(sentinel));
    assertFalse(restoreResult.isComplete());

    Path pages = backupDirectory.resolve("river.indexed.pages");
    byte[] pageBytes = Files.readAllBytes(pages);
    pageBytes[pageBytes.length - 1] ^= 0x5a;
    Files.write(pages, pageBytes);
    assertEquals(
        StatusCode.CORRUPTION,
        backup.restore(backupDirectory, corruptRestore, restoreResult));
    assertFalse(restoreResult.isComplete());
  }

  private static void assertRestoredStatisticsPlan(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT a.id,r.id,c.id FROM accounts a "
                + "JOIN regions r ON a.region=r.id "
                + "JOIN countries c ON r.country=c.id",
            cursor));
    int exact = 0;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      if (row.valueAt(0) == PackedText.pack("exact")) exact++;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(3, exact);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }
}
