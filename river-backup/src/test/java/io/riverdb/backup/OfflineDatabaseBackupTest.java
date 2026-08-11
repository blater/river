package io.riverdb.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.sql.SqlExecutionResult;
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
        RelationalDatabase.create(source, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult command = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE accounts "
                + "(id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO accounts VALUES "
                + "(1, 100, 7), (2, 250, 7), (3, 300, 8)",
            command));
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
        RelationalDatabase.openExisting(restored, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT balance FROM accounts WHERE id=2", command));
    assertTrue(command.hasValue());
    assertEquals(250, command.value());
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
}
