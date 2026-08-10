package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSessionTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(757, 761);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void executesDurableSqlPointStatements(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE accounts", result));
    assertEquals(0, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (7, 700)", result));
    assertEquals(1, result.affectedRows());
    long insertSequence = result.commitSequence();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM accounts WHERE key = 7", result));
    assertEquals(700, result.value());
    assertEquals(insertSequence, result.commitSequence());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM accounts", result));
    assertEquals(1, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET value = 701 WHERE key = 7", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM accounts WHERE key = 7", result));
    assertEquals(701, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE UNIQUE INDEX accounts_value ON accounts(value)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT key, value FROM accounts WHERE value=701",
            result));
    assertEquals(7, result.key());
    assertEquals(701, result.value());
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO accounts VALUES (8, 701)", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM accounts WHERE key=8", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (8, 800)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET value=801 WHERE key=8", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT key, value FROM accounts WHERE value=800", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT key, value FROM accounts WHERE value=801", result));
    assertEquals(8, result.key());
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("UPDATE accounts SET value=701 WHERE key=8", result));
    assertEquals(true, result.transactionActive());
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (9, 900)", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT key, value FROM accounts WHERE value=801", result));
    assertEquals(8, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT key, value FROM accounts WHERE value=900", result));
    assertEquals(9, result.key());
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(0, result.affectedRows());
    assertEquals(true, result.commitSequence() > insertSequence);
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 6, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM accounts WHERE key = 7", result));
    assertEquals(701, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT key, value FROM accounts WHERE value=801", result));
    assertEquals(8, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM accounts WHERE key = 7", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT key, value FROM accounts WHERE value=701", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM accounts WHERE key = 7", result));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void statementFailureRollsBackAndLeavesSessionReusable(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 4, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE t", result));
    assertEquals(StatusCode.CONFLICT, session.execute("SELECT value FROM t WHERE key=1", result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, session.execute("SELECT nope", result));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO t VALUES (1, 10)", result));
    assertEquals(StatusCode.CONFLICT, session.execute("INSERT INTO t VALUES (1, 11)", result));
    assertEquals(StatusCode.OK, session.execute("SELECT value FROM t WHERE key=1", result));
    assertEquals(10, result.value());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void scansUniqueIndexInSignedValueOrder(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE measurements", execution));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO measurements VALUES (1, -20)", execution));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO measurements VALUES (2, 5)", execution));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO measurements VALUES (3, 25)", execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE UNIQUE INDEX measurements_value ON measurements(value)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT key, value FROM measurements WHERE value=-20",
            execution));
    assertEquals(1, execution.key());

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT key, value FROM measurements "
                + "WHERE value >= -20 AND value < 20",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.key());
    assertEquals(-20, row.value());
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.key());
    assertEquals(5, row.value());
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "INSERT INTO measurements VALUES (4, 140737488355327)",
            execution));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM measurements WHERE key=4", execution));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void explicitTransactionCommitsOrRollsBackMultipleStatements(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession writer = sessionResult.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession observer = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, writer.execute("CREATE TABLE accounts", result));
    assertEquals(StatusCode.OK, writer.execute("CREATE TABLE papers", result));
    assertEquals(StatusCode.OK, writer.execute("BEGIN", result));
    assertEquals(true, result.transactionActive());
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO accounts VALUES (1, 100)", result));
    assertEquals(0, result.commitSequence());
    assertEquals(true, result.transactionActive());
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO papers VALUES (1, 200)", result));
    assertEquals(
        StatusCode.OK,
        writer.execute("SELECT value FROM accounts WHERE key=1", result));
    assertEquals(100, result.value());
    assertEquals(
        StatusCode.OK,
        writer.execute("SELECT COUNT(*) FROM accounts", result));
    assertEquals(1, result.value());
    assertEquals(
        StatusCode.OK,
        observer.execute("SELECT COUNT(*) FROM accounts", result));
    assertEquals(0, result.value());
    assertEquals(
        StatusCode.CONFLICT,
        observer.execute("SELECT value FROM accounts WHERE key=1", result));
    assertEquals(StatusCode.OK, writer.execute("COMMIT", result));
    assertEquals(false, result.transactionActive());
    assertEquals(
        StatusCode.OK,
        observer.execute("SELECT value FROM accounts WHERE key=1", result));
    assertEquals(100, result.value());
    assertEquals(
        StatusCode.OK,
        observer.execute("SELECT value FROM papers WHERE key=1", result));
    assertEquals(200, result.value());

    assertEquals(StatusCode.OK, writer.execute("BEGIN", result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, writer.execute("SELECT nope", result));
    assertEquals(true, result.transactionActive());
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO accounts VALUES (2, 101)", result));
    assertEquals(StatusCode.OK, writer.execute("ROLLBACK", result));
    assertEquals(
        StatusCode.CONFLICT,
        observer.execute("SELECT value FROM accounts WHERE key=2", result));
    assertEquals(StatusCode.CONFLICT, writer.execute("COMMIT", result));

    assertEquals(StatusCode.OK, writer.execute("BEGIN", result));
    for (int key = 10; key < 26; key++) {
      assertEquals(
          StatusCode.OK,
          writer.execute(
              "INSERT INTO accounts VALUES (" + key + ", " + key * 10 + ")",
              result));
    }
    assertEquals(StatusCode.OK, writer.execute("COMMIT", result));
    long batchSequence = result.commitSequence();
    assertEquals(
        StatusCode.OK,
        observer.execute("SELECT value FROM accounts WHERE key=25", result));
    assertEquals(250, result.value());
    assertEquals(batchSequence, result.commitSequence());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void namedSavepointCoexistsWithStatementRollback(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE accounts", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (1, 100)", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT middle", result));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        session.execute("SAVEPOINT second", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (2, 200)", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO accounts VALUES (2, 201)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (3, 300)", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("ROLLBACK TO SAVEPOINT unknown", result));
    assertEquals(
        StatusCode.OK,
        session.execute("ROLLBACK TO SAVEPOINT middle", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM accounts WHERE key=2", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM accounts WHERE key=3", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (4, 400)", result));
    assertEquals(StatusCode.OK, session.execute("RELEASE SAVEPOINT middle", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM accounts WHERE key=1", result));
    assertEquals(100, result.value());
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM accounts WHERE key=2", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM accounts WHERE key=4", result));
    assertEquals(400, result.value());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void scansOrderedSnapshotRowsAndCanCloseEarly(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession reader = sessionResult.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession writer = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(StatusCode.OK, writer.execute("CREATE TABLE items", execution));
    assertEquals(StatusCode.OK, writer.execute("BEGIN", execution));
    for (int key = 0; key < 20; key++) {
      assertEquals(
          StatusCode.OK,
          writer.execute(
              "INSERT INTO items VALUES (" + key + ", " + key * 10 + ")",
              execution));
    }
    assertEquals(StatusCode.OK, writer.execute("COMMIT", execution));

    assertEquals(StatusCode.OK, reader.execute("BEGIN", execution));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        reader.beginScan("SELECT key, value FROM items", cursor));
    assertEquals(StatusCode.OK, writer.execute("UPDATE items SET value=999 WHERE key=5", execution));
    assertEquals(StatusCode.OK, writer.execute("DELETE FROM items WHERE key=6", execution));
    int count = 0;
    StatusCode scanStatus;
    while ((scanStatus = reader.nextScan(cursor, row)).isOk()) {
      assertEquals(count, row.key());
      assertEquals(count * 10L, row.value());
      count++;
    }
    assertEquals(StatusCode.CONFLICT, scanStatus);
    assertEquals(20, count);
    assertEquals(20, cursor.rowsReturned());
    assertEquals(StatusCode.OK, reader.closeScan(cursor, execution));
    assertEquals(true, execution.transactionActive());
    assertEquals(StatusCode.OK, reader.execute("COMMIT", execution));

    assertEquals(StatusCode.OK, reader.execute("BEGIN", execution));
    assertEquals(StatusCode.OK, reader.execute("UPDATE items SET value=555 WHERE key=5", execution));
    assertEquals(StatusCode.OK, reader.execute("DELETE FROM items WHERE key=7", execution));
    assertEquals(StatusCode.OK, reader.execute("INSERT INTO items VALUES (30, 300)", execution));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        reader.beginScan("SELECT KEY, VALUE FROM items", cursor));
    count = 0;
    boolean sawFive = false;
    boolean sawThirty = false;
    while ((scanStatus = reader.nextScan(cursor, row)).isOk()) {
      assertNotEquals(6, row.key());
      assertNotEquals(7, row.key());
      if (row.key() == 5) {
        assertEquals(555, row.value());
        sawFive = true;
      }
      if (row.key() == 30) {
        assertEquals(300, row.value());
        sawThirty = true;
      }
      count++;
    }
    assertEquals(StatusCode.CONFLICT, scanStatus);
    assertEquals(true, sawFive);
    assertEquals(true, sawThirty);
    assertEquals(19, count);
    assertEquals(19, cursor.rowsReturned());
    assertEquals(StatusCode.OK, reader.closeScan(cursor, execution));
    assertEquals(StatusCode.OK, reader.execute("ROLLBACK", execution));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        reader.beginScan("SELECT KEY, VALUE FROM items", cursor));
    assertEquals(StatusCode.OK, reader.nextScan(cursor, row));
    assertEquals(0, row.key());
    assertEquals(StatusCode.OK, reader.closeScan(cursor, execution));
    assertEquals(false, execution.transactionActive());

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        reader.beginScan(
            "SELECT key, value FROM items WHERE key >= 4 AND key < 9",
            cursor));
    long[] expectedKeys = {4, 5, 7, 8};
    long[] expectedValues = {40, 999, 70, 80};
    int expectedIndex = 0;
    while ((scanStatus = reader.nextScan(cursor, row)).isOk()) {
      assertEquals(expectedKeys[expectedIndex], row.key());
      assertEquals(expectedValues[expectedIndex], row.value());
      expectedIndex++;
    }
    assertEquals(StatusCode.CONFLICT, scanStatus);
    assertEquals(expectedKeys.length, expectedIndex);
    assertEquals(StatusCode.OK, reader.closeScan(cursor, execution));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        reader.beginScan(
            "SELECT key, value FROM items WHERE key >= 9 AND key < 4",
            cursor));
    assertEquals(
        StatusCode.OK,
        reader.execute("SELECT value FROM items WHERE key=4", execution));
    assertEquals(40, execution.value());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void serializableSqlRangeAbortsOnConcurrentPhantom(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession reader = sessions.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession writer = sessions.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(StatusCode.OK, writer.execute("CREATE TABLE items", execution));
    assertEquals(StatusCode.OK, reader.execute("BEGIN SERIALIZABLE", execution));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        reader.beginScan(
            "SELECT key, value FROM items WHERE key >= 10 AND key < 20",
            cursor));
    assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, row));
    assertEquals(StatusCode.OK, reader.closeScan(cursor, execution));
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO items VALUES (15, 150)", execution));
    assertEquals(StatusCode.CONFLICT, reader.execute("COMMIT", execution));
    assertEquals(
        StatusCode.OK,
        reader.execute("SELECT value FROM items WHERE key=15", execution));
    assertEquals(150, execution.value());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void uniqueIndexBuildRejectsDuplicateExistingValuesAtomically(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE items", result));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO items VALUES (1, 10)", result));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO items VALUES (2, 10)", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("CREATE UNIQUE INDEX items_value ON items(value)", result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("SELECT key, value FROM items WHERE value=10", result));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE items SET value=20 WHERE key=2", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX items_value ON items(value)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT key, value FROM items WHERE value=10", result));
    assertEquals(1, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT key, value FROM items WHERE value=20", result));
    assertEquals(2, result.key());
    assertEquals(StatusCode.OK, database.close());
  }
}
