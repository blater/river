package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSessionTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(757, 761);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void streamingForUpdateWaitIsGrantedAndLeavesExplicitTransactionUsable(
      @TempDir Path root) throws Exception {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession first = sessions.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession second = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        first.execute(
            "CREATE TABLE locked_rows (id BIGINT PRIMARY KEY, value BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        first.execute("INSERT INTO locked_rows VALUES (1,10),(2,20)", result));
    assertEquals(StatusCode.OK, first.execute("BEGIN", result));
    assertEquals(StatusCode.OK, second.execute("BEGIN", result));

    SqlScanCursor held = new SqlScanCursor();
    SqlScanCursor waiting = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        first.beginScan(
            "SELECT value FROM locked_rows WHERE id=1 FOR UPDATE", held));
    assertEquals(StatusCode.OK, first.nextScan(held, row));
    assertEquals(10, row.valueAt(0));
    assertEquals(StatusCode.OK, first.closeScan(held, result));
    assertEquals(StatusCode.OK,
        first.execute("UPDATE locked_rows SET value=11 WHERE id=1", result));
    assertEquals(
        StatusCode.OK,
        second.beginScan(
            "SELECT value FROM locked_rows WHERE id=1 FOR UPDATE", waiting));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    SqlScanRowResult waitingRow = new SqlScanRowResult();
    try {
      Future<StatusCode> granted = executor.submit(() ->
          second.nextScan(waiting, waitingRow));
      assertEquals(StatusCode.OK, first.execute("COMMIT", result));
      assertEquals(StatusCode.OK, granted.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(11, waitingRow.valueAt(0));
    assertEquals(StatusCode.OK, second.closeScan(waiting, result));
    assertEquals(true, result.transactionActive());

    assertEquals(
        StatusCode.OK,
        second.execute("UPDATE locked_rows SET value=21 WHERE id=2", result));
    assertEquals(StatusCode.OK, second.execute("COMMIT", result));
    close(database, first, second);
  }

  @Test
  void forUpdateReturnsAVisibleRowsCurrentCommittedSuccessor(
      @TempDir Path root) {
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
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        reader.execute(
            "CREATE TABLE current_rows (id BIGINT PRIMARY KEY, value BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        reader.execute("INSERT INTO current_rows VALUES (1,10),(2,20)", result));
    assertEquals(StatusCode.OK, reader.execute("BEGIN REPEATABLE READ", result));
    assertEquals(
        StatusCode.OK,
        writer.execute("UPDATE current_rows SET value=11 WHERE id=1", result));

    assertEquals(StatusCode.OK, reader.execute(
        "SELECT value FROM current_rows WHERE id=1 FOR UPDATE", result));
    assertEquals(11, result.value());
    assertEquals(true, result.transactionActive());
    assertEquals(
        StatusCode.OK,
        reader.execute("UPDATE current_rows SET value=21 WHERE id=2", result));
    assertEquals(StatusCode.OK, reader.execute("ROLLBACK", result));
    close(database, reader, writer);
  }

  @Test
  void forUpdateReleasesARejectedCurrentSuccessor(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession reader = sessions.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession writer = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK,
        reader.execute("CREATE TABLE legacy_current_rows", result));
    assertEquals(StatusCode.OK,
        reader.execute("INSERT INTO legacy_current_rows VALUES (1,11)", result));

    assertEquals(StatusCode.OK, reader.execute("BEGIN REPEATABLE READ", result));
    assertEquals(StatusCode.OK,
        writer.execute("UPDATE legacy_current_rows SET value=12 WHERE key=1", result));
    assertEquals(StatusCode.CONFLICT, reader.execute(
        "SELECT value FROM legacy_current_rows "
            + "WHERE key=1 AND value=11 FOR UPDATE", result));
    assertEquals(true, result.transactionActive());
    assertEquals(StatusCode.OK,
        writer.execute("UPDATE legacy_current_rows SET value=13 WHERE key=1", result));
    assertEquals(StatusCode.OK, reader.execute("ROLLBACK", result));

    assertEquals(StatusCode.OK, reader.execute("BEGIN REPEATABLE READ", result));
    assertEquals(StatusCode.OK,
        writer.execute("DELETE FROM legacy_current_rows WHERE key=1", result));
    assertEquals(StatusCode.OK,
        writer.execute("INSERT INTO legacy_current_rows VALUES (1,14)", result));
    assertEquals(StatusCode.CONFLICT, reader.execute(
        "SELECT value FROM legacy_current_rows WHERE key=1 FOR UPDATE", result));
    assertEquals(true, result.transactionActive());
    assertEquals(StatusCode.OK, reader.execute("ROLLBACK", result));
    close(database, reader, writer);
  }

  @Test
  void selectForUpdateProtectsDescriptorRowsUntilTransactionEnd(
      @TempDir Path root) throws Exception {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession first = sessions.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession second = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();

    assertEquals(StatusCode.OK, first.execute("CREATE TABLE legacy_accounts", result));
    assertEquals(
        StatusCode.OK,
        first.execute("INSERT INTO legacy_accounts VALUES (1,100)", result));
    assertEquals(StatusCode.OK, first.execute("BEGIN", result));
    assertEquals(StatusCode.OK, second.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        first.execute(
            "SELECT value FROM legacy_accounts WHERE key=1 FOR UPDATE", result));
    assertQueuedStatementGranted(
        first, second, "ROLLBACK",
        "UPDATE legacy_accounts SET value=101 WHERE key=1", result);
    assertEquals(StatusCode.OK, second.execute("COMMIT", result));

    assertEquals(
        StatusCode.OK,
        first.execute(
            "CREATE TABLE district (d_w_id BIGINT, d_id BIGINT, "
                + "d_next_o_id BIGINT, PRIMARY KEY(d_w_id,d_id))",
            result));
    assertEquals(
        StatusCode.OK,
        first.execute("INSERT INTO district VALUES (1,1,10),(1,2,20)", result));
    assertEquals(StatusCode.OK, first.execute("BEGIN", result));
    assertEquals(StatusCode.OK, second.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        first.execute(
            "SELECT d_next_o_id FROM district "
                + "WHERE d_w_id=1 AND d_id=1 FOR UPDATE",
            result));
    assertEquals(10, result.value());
    assertQueuedStatementGranted(
        first, second, "COMMIT",
        "UPDATE district SET d_next_o_id=11 WHERE d_w_id=1 AND d_id=1", result);
    assertEquals(
        StatusCode.OK,
        second.execute(
            "UPDATE district SET d_next_o_id=21 WHERE d_w_id=1 AND d_id=2",
            result));
    assertEquals(StatusCode.OK, second.execute("COMMIT", result));

    assertEquals(StatusCode.OK, first.execute("BEGIN", result));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        first.beginScan(
            "SELECT d_id FROM district ORDER BY d_next_o_id LIMIT 1 FOR UPDATE",
            cursor));
    assertEquals(StatusCode.OK, first.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.OK, first.closeScan(cursor, result));
    assertEquals(StatusCode.OK, second.execute("BEGIN", result));
    assertQueuedStatementGranted(
        first, second, "ROLLBACK",
        "UPDATE district SET d_next_o_id=12 WHERE d_w_id=1 AND d_id=1", result);
    assertEquals(
        StatusCode.OK,
        second.execute(
            "UPDATE district SET d_next_o_id=22 WHERE d_w_id=1 AND d_id=2",
            result));
    assertEquals(StatusCode.OK, second.execute("COMMIT", result));
    close(database, first, second);
  }

  @Test
  void executesScalarTemporalExtractAndDateArithmetic(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();

    assertScalar(
        session,
        result,
        "SELECT EXTRACT(YEAR FROM DATE '2024-02-29')",
        SqlTypeDescriptor.BIGINT,
        2024);
    assertScalar(
        session,
        result,
        "SELECT EXTRACT(SECOND FROM TIME '12:34:56.123')",
        SqlTypeDescriptor.decimal(5, 3),
        56_123);
    assertScalar(
        session,
        result,
        "SELECT EXTRACT(SECOND FROM TIME '12:34:56')",
        SqlTypeDescriptor.decimal(2, 0),
        56);
    assertScalar(
        session,
        result,
        "SELECT EXTRACT(SECOND FROM "
            + "TIMESTAMP '1969-12-31 23:59:59.999999')",
        SqlTypeDescriptor.decimal(8, 6),
        59_999_999);
    assertScalar(
        session,
        result,
        "SELECT EXTRACT(HOUR FROM TIMESTAMP WITH TIME ZONE "
            + "'2024-01-01 01:02:03.004+01:00')",
        SqlTypeDescriptor.BIGINT,
        0);
    assertScalar(
        session,
        result,
        "SELECT EXTRACT(TIMEZONE_HOUR FROM TIMESTAMP WITH TIME ZONE "
            + "'2024-01-01 01:02:03.004+01:00')",
        SqlTypeDescriptor.BIGINT,
        0);
    assertScalar(
        session,
        result,
        "SELECT DATE '2024-02-29'+1",
        SqlTypeDescriptor.DATE,
        19_783);
    assertScalar(
        session,
        result,
        "SELECT DATE '2024-03-01'-DATE '2024-02-29'",
        SqlTypeDescriptor.BIGINT,
        1);
    assertScalar(
        session,
        result,
        "SELECT EXTRACT(DAY FROM DATE '2024-02-28'+1)",
        SqlTypeDescriptor.BIGINT,
        29);
    assertScalar(
        session, result, "SELECT 1+2", SqlTypeDescriptor.INTEGER, 3);

    assertEquals(
        StatusCode.OK,
        session.execute("SELECT EXTRACT(SECOND FROM CURRENT_TIMESTAMP)", result));
    assertEquals(SqlTypeDescriptor.decimal(8, 6), result.typeDescriptorAt(0));
    assertEquals(true, result.valueAt(0) >= 0 && result.valueAt(0) < 60_000_000);
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT EXTRACT(YEAR FROM CURRENT_DATE)", result));
    assertEquals(true, result.valueAt(0) >= 1 && result.valueAt(0) <= 9_999);

    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        session.execute("SELECT DATE '9999-12-31'+1", result));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        session.execute("SELECT DATE '0001-01-01'-1", result));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute("SELECT EXTRACT(YEAR FROM TIME '12:34:56')", result));
    close(database, session);
  }

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
        StatusCode.UNIQUE_VIOLATION,
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
        StatusCode.UNIQUE_VIOLATION,
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
    close(database, session);

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
    close(database, session);
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
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO t VALUES (1, 11)", result));
    assertEquals(StatusCode.OK, session.execute("SELECT value FROM t WHERE key=1", result));
    assertEquals(10, result.value());
    close(database, session);
  }

  @Test
  void multiRowInsertCommitsOnceAndRollsBackAtomically(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 4, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE batch_rows", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO batch_rows VALUES (1, 10), (2, 20), (3, 30)",
            result));
    assertEquals(3, result.affectedRows());
    long batchCommit = result.commitSequence();
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute(
            "INSERT INTO batch_rows VALUES (4, 40), (2, 21), (5, 50)",
            result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM batch_rows WHERE key=4", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM batch_rows WHERE key=5", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM batch_rows", result));
    assertEquals(3, result.value());
    close(database, session);

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 4, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM batch_rows WHERE key=3", result));
    assertEquals(30, result.value());
    assertEquals(batchCommit, result.commitSequence());
    close(database, session);
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
        StatusCode.OK,
        session.execute(
            "INSERT INTO measurements VALUES (4, 140737488355327)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT value FROM measurements WHERE key=4", execution));
    assertEquals(140737488355327L, execution.value());
    close(database, session);
  }

  @Test
  void activeScanRejectsExecuteBeforeMutatingItsBoundState(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE messages (id BIGINT PRIMARY KEY, body VARCHAR(32))",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO messages VALUES (1, 'first')", execution));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO messages VALUES (2, 'second')", execution));

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT body AS message FROM messages WHERE id >= 1 AND id < 3",
            cursor));
    assertEquals("message", session.scanColumnName(cursor, 0).toString());
    int descriptor = session.scanColumnTypeDescriptor(cursor, 0);
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    char[] text = new char[32];
    int length = row.copyTextAt(0, text, 0);
    assertEquals("first", new String(text, 0, length));

    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT COUNT(*) FROM messages", execution));
    assertEquals("message", session.scanColumnName(cursor, 0).toString());
    assertEquals(descriptor, session.scanColumnTypeDescriptor(cursor, 0));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    length = row.copyTextAt(0, text, 0);
    assertEquals("second", new String(text, 0, length));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
    close(database, session);
  }

  @Test
  void scanCapabilitiesRejectWrongOwnersAndStaleGenerations(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession owner = sessionResult.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession other = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(StatusCode.OK, owner.execute("CREATE TABLE tokens", execution));
    assertEquals(
        StatusCode.OK,
        owner.execute("INSERT INTO tokens VALUES (1, 10), (2, 20)", execution));

    SqlScanCursor first = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        owner.beginScan("SELECT key, value FROM tokens", first));
    assertEquals(
        StatusCode.CONFLICT,
        other.beginScan("SELECT key, value FROM tokens", first));
    assertEquals(StatusCode.CONFLICT, other.nextScan(first, row));
    assertEquals(StatusCode.CONFLICT, other.closeScan(first, execution));
    assertEquals(
        StatusCode.OK,
        other.execute("SELECT COUNT(*) FROM tokens", execution));
    assertEquals(2, execution.value());
    assertEquals(StatusCode.OK, owner.nextScan(first, row));
    assertEquals(1, row.key());
    assertEquals(StatusCode.OK, owner.closeScan(first, execution));

    SqlScanCursor second = new SqlScanCursor();
    assertEquals(
        StatusCode.OK,
        owner.beginScan("SELECT key, value FROM tokens", second));
    assertEquals(StatusCode.CONFLICT, owner.nextScan(first, row));
    assertEquals(StatusCode.CONFLICT, owner.closeScan(first, execution));
    assertEquals(StatusCode.OK, owner.nextScan(second, row));
    assertEquals(1, row.key());
    assertEquals(StatusCode.OK, owner.closeScan(second, execution));
    close(database, owner, other);
  }

  @Test
  void sequenceValueCanBeConsumedThroughScanCapability(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 4, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE SEQUENCE scan_ids START WITH 41", execution));

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan("SELECT NEXT VALUE FOR scan_ids", cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(41, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
    close(database, session);
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
    close(database, writer, observer);
  }

  @Test
  void grantedInsertWaitReportsUniqueViolationAndLeavesExplicitTransactionUsable(
      @TempDir Path root) throws Exception {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession first = sessions.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession second = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, first.execute("CREATE TABLE accounts", result));
    assertEquals(StatusCode.OK, first.execute("BEGIN", result));
    assertEquals(StatusCode.OK, second.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        first.execute("INSERT INTO accounts VALUES (1, 100)", result));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    SqlExecutionResult waitingResult = new SqlExecutionResult();
    try {
      Future<StatusCode> waiting = executor.submit(() ->
          second.execute("INSERT INTO accounts VALUES (1, 101)", waitingResult));
      assertEquals(StatusCode.OK, first.execute("COMMIT", result));
      assertEquals(StatusCode.UNIQUE_VIOLATION, waiting.get());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(true, waitingResult.transactionActive());
    assertEquals(
        StatusCode.OK,
        second.execute("INSERT INTO accounts VALUES (2, 200)", result));
    assertEquals(StatusCode.OK, second.execute("COMMIT", result));
    assertEquals(
        StatusCode.OK,
        first.execute("SELECT value FROM accounts WHERE key=1", result));
    assertEquals(100, result.value());
    assertEquals(
        StatusCode.OK,
        first.execute("SELECT value FROM accounts WHERE key=2", result));
    assertEquals(200, result.value());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
    close(database);
  }

  @Test
  void currentPrimarySourceTransferNeverMutatesTheReplacement(
      @TempDir Path root) throws Exception {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession mover = sessions.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession waiter = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        mover.execute(
            "CREATE TABLE accounts "
                + "(id BIGINT PRIMARY KEY, balance BIGINT)", result));
    assertEquals(
        StatusCode.OK,
        mover.execute("INSERT INTO accounts VALUES (1, 100)", result));
    assertEquals(StatusCode.OK, mover.execute("BEGIN", result));
    assertEquals(StatusCode.OK, waiter.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        mover.execute("UPDATE accounts SET id=2 WHERE id=1", result));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    SqlExecutionResult waitingResult = new SqlExecutionResult();
    try {
      Future<StatusCode> waiting = executor.submit(() ->
          waiter.execute(
              "UPDATE accounts SET balance=999 WHERE id=1", waitingResult));
      assertEquals(StatusCode.OK, mover.execute("COMMIT", result));
      assertEquals(StatusCode.CONFLICT, waiting.get());
    } finally {
      executor.shutdownNow();
    }

    assertEquals(true, waitingResult.transactionActive());
    assertEquals(StatusCode.OK, waiter.execute("ROLLBACK", result));
    assertEquals(
        StatusCode.CONFLICT,
        mover.execute("SELECT balance FROM accounts WHERE id=1", result));
    assertEquals(
        StatusCode.OK,
        mover.execute("SELECT balance FROM accounts WHERE id=2", result));
    assertEquals(100, result.value());
    close(database, mover, waiter);
  }

  @Test
  void tableCreationCommitsAndRollsBackWithItsTransaction(@TempDir Path root) {
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

    assertEquals(StatusCode.OK, writer.execute("BEGIN", result));
    assertEquals(StatusCode.OK, writer.execute("CREATE TABLE staged", result));
    assertEquals(true, result.transactionActive());
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO staged VALUES (1, 100)", result));
    assertEquals(
        StatusCode.RETRY,
        observer.execute("SELECT value FROM staged WHERE key=1", result));
    assertEquals(StatusCode.OK, writer.execute("COMMIT", result));
    assertEquals(
        StatusCode.OK,
        observer.execute("SELECT value FROM staged WHERE key=1", result));
    assertEquals(100, result.value());

    assertEquals(StatusCode.OK, writer.execute("BEGIN", result));
    assertEquals(StatusCode.OK, writer.execute("SAVEPOINT before_ddl", result));
    assertEquals(StatusCode.OK, writer.execute("CREATE TABLE discarded", result));
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO discarded VALUES (2, 200)", result));
    assertEquals(
        StatusCode.OK,
        writer.execute("ROLLBACK TO SAVEPOINT before_ddl", result));
    assertEquals(StatusCode.OK, writer.execute("CREATE TABLE replacement", result));
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO replacement VALUES (3, 300)", result));
    assertEquals(StatusCode.OK, writer.execute("RELEASE SAVEPOINT before_ddl", result));
    assertEquals(StatusCode.OK, writer.execute("COMMIT", result));
    assertEquals(
        StatusCode.CONFLICT,
        observer.execute("SELECT value FROM discarded WHERE key=2", result));
    assertEquals(
        StatusCode.OK,
        observer.execute("SELECT value FROM replacement WHERE key=3", result));
    assertEquals(300, result.value());
    close(database, writer, observer);

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    observer = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        observer.execute("SELECT value FROM staged WHERE key=1", result));
    assertEquals(100, result.value());
    assertEquals(
        StatusCode.CONFLICT,
        observer.execute("SELECT value FROM discarded WHERE key=2", result));
    assertEquals(
        StatusCode.OK,
        observer.execute("SELECT value FROM replacement WHERE key=3", result));
    assertEquals(300, result.value());
    close(database, observer);
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
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT second", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT third", result));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        session.execute("SAVEPOINT fourth", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (2, 200)", result));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
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
        session.execute("ROLLBACK TO SAVEPOINT second", result));
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
    close(database, session);
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
        StatusCode.OK,
        reader.beginScan(
            "SELECT key, value FROM items WHERE key >= 9 AND key < 4",
            cursor));
    assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, row));
    assertEquals(StatusCode.OK, reader.closeScan(cursor, execution));
    assertEquals(
        StatusCode.OK,
        reader.execute("SELECT value FROM items WHERE key=4", execution));
    assertEquals(40, execution.value());
    close(database, reader, writer);
  }

  @Test
  void scalarScanKeepsItsPageGenerationWhenAnEarlierKeyIsInserted(@TempDir Path root) {
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
    assertEquals(StatusCode.OK, writer.execute("INSERT INTO items VALUES (10,100)", execution));
    assertEquals(StatusCode.OK, writer.execute("INSERT INTO items VALUES (20,200)", execution));
    assertEquals(StatusCode.OK, writer.execute("INSERT INTO items VALUES (30,300)", execution));

    assertEquals(StatusCode.OK, reader.execute("BEGIN REPEATABLE READ", execution));
    assertEquals(StatusCode.OK, writer.execute("INSERT INTO items VALUES (5,50)", execution));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, reader.beginScan("SELECT key,value FROM items", cursor));
    assertEquals(StatusCode.OK, reader.nextScan(cursor, row));
    assertEquals(10, row.key());
    assertEquals(StatusCode.OK, writer.execute("INSERT INTO items VALUES (1,10)", execution));
    assertEquals(StatusCode.OK, reader.nextScan(cursor, row));
    assertEquals(20, row.key());
    assertEquals(StatusCode.OK, reader.nextScan(cursor, row));
    assertEquals(30, row.key());
    assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, row));
    assertEquals(StatusCode.OK, reader.closeScan(cursor, execution));
    assertEquals(StatusCode.OK, reader.execute("COMMIT", execution));
    close(database, reader, writer);
  }

  @Test
  void serializableSqlRangeBlocksPhantomButAllowsOutsideCommit(@TempDir Path root) {
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
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO items VALUES (12, 120)", execution));
    assertEquals(StatusCode.OK, reader.execute("BEGIN SERIALIZABLE", execution));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        reader.beginScan(
            "SELECT key, value FROM items WHERE key >= 10 AND key < 20",
            cursor));
    assertEquals(StatusCode.OK, reader.nextScan(cursor, row));
    assertEquals(12, row.key());
    assertEquals(StatusCode.CONFLICT, reader.nextScan(cursor, row));
    assertEquals(StatusCode.OK, reader.closeScan(cursor, execution));
    assertEquals(
        StatusCode.OK,
        reader.execute("UPDATE items SET value=121 WHERE key=12", execution));
    assertEquals(
        StatusCode.TIMEOUT,
        writer.execute("UPDATE items SET value=122 WHERE key=12", execution));
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO items VALUES (25, 250)", execution));
    assertEquals(
        StatusCode.TIMEOUT,
        writer.execute("INSERT INTO items VALUES (15, 150)", execution));
    assertEquals(StatusCode.OK, reader.execute("COMMIT", execution));
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO items VALUES (15, 150)", execution));
    assertEquals(
        StatusCode.OK,
        writer.execute("UPDATE items SET value=122 WHERE key=12", execution));
    assertEquals(
        StatusCode.OK,
        reader.execute("SELECT value FROM items WHERE key=12", execution));
    assertEquals(122, execution.value());
    assertEquals(
        StatusCode.OK,
        reader.execute("SELECT value FROM items WHERE key=15", execution));
    assertEquals(150, execution.value());
    assertEquals(
        StatusCode.OK,
        reader.execute("SELECT value FROM items WHERE key=25", execution));
    assertEquals(250, execution.value());
    close(database, reader, writer);
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
        StatusCode.UNIQUE_VIOLATION,
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
    close(database, session);
  }

  @Test
  void uniqueIndexCreationCommitsWithItsExplicitTransaction(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession writer = sessions.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession reader = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, writer.execute("CREATE TABLE ledger", result));
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO ledger VALUES (1, 101), (2, 202)", result));

    assertEquals(StatusCode.OK, writer.execute("BEGIN SERIALIZABLE", result));
    assertEquals(
        StatusCode.OK,
        writer.execute("CREATE UNIQUE INDEX ledger_value ON ledger(value)", result));
    assertEquals(true, result.transactionActive());
    assertEquals(
        StatusCode.OK,
        writer.execute("SELECT key, value FROM ledger WHERE value=202", result));
    assertEquals(2, result.key());
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        writer.execute("INSERT INTO ledger VALUES (3, 202)", result));
    assertEquals(true, result.transactionActive());
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        writer.execute("UPDATE ledger SET value=101 WHERE key=2", result));
    assertEquals(true, result.transactionActive());
    assertEquals(
        StatusCode.OK,
        writer.execute("INSERT INTO ledger VALUES (3, 303)", result));
    assertEquals(
        StatusCode.RETRY,
        reader.execute("SELECT value FROM ledger WHERE key=1", result));
    assertEquals(StatusCode.OK, writer.execute("COMMIT", result));

    assertEquals(
        StatusCode.OK,
        reader.execute("SELECT key, value FROM ledger WHERE value=101", result));
    assertEquals(1, result.key());
    assertEquals(
        StatusCode.OK,
        reader.execute("SELECT key, value FROM ledger WHERE value=303", result));
    assertEquals(3, result.key());
    close(database, writer, reader);

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 6, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession reopened = sessions.session();
    assertEquals(
        StatusCode.OK,
        reopened.execute("SELECT key, value FROM ledger WHERE value=303", result));
    assertEquals(3, result.key());
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        reopened.execute("INSERT INTO ledger VALUES (4, 303)", result));
    close(database, reopened);
  }

  @Test
  void publishingUniqueIndexProtectsKeysFromEarlierBackfillBatches(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE ledger", result));
    for (int first = 1; first <= 1_024; first += 64) {
      StringBuilder insert = new StringBuilder("INSERT INTO ledger VALUES ");
      for (int row = first; row < first + 64; row++) {
        if (row > first) insert.append(',');
        insert.append('(').append(row).append(',').append(row * 10).append(')');
      }
      assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
      assertEquals(64, result.affectedRows());
    }

    assertEquals(StatusCode.OK, session.execute("BEGIN SERIALIZABLE", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX ledger_value ON ledger(value)", result));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO ledger VALUES (1025, 10)", result));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("UPDATE ledger SET value=10 WHERE key=1024", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO ledger VALUES (1025, 10250)", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    close(database, session);

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 6, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    session = sessions.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT key FROM ledger WHERE value=10250", result));
    assertEquals(1025, result.key());
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO ledger VALUES (1026, 10)", result));
    close(database, session);
  }

  @Test
  void uniqueIndexRollbackRemovesCatalogAndReleasesSchemaBarrier(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession second = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE events", result));
    assertEquals(StatusCode.OK, session.execute("INSERT INTO events VALUES (1, 11)", result));

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT before_index", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX events_value ON events(value)", result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK TO before_index", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT key, value FROM events WHERE value=11", result));
    assertEquals(1, result.key());
    assertEquals(11, result.value());
    assertEquals(
        StatusCode.OK,
        second.execute("SELECT value FROM events WHERE key=1", result));
    assertEquals(11, result.value());
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));

    assertEquals(
        StatusCode.OK,
        second.execute("CREATE UNIQUE INDEX events_value ON events(value)", result));
    assertEquals(
        StatusCode.OK,
        second.execute("SELECT key, value FROM events WHERE value=11", result));
    assertEquals(1, result.key());
    close(database, session, second);
  }

  @Test
  void bindsDurableExplicitColumnNamesAcrossReopen(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE balances "
                + "(account_id BIGINT PRIMARY KEY, amount BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO balances VALUES (7, 700)", result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("SELECT value FROM balances WHERE key=7", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT amount FROM balances WHERE account_id=7", result));
    assertEquals(700, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE balances SET amount=701 WHERE account_id=7", result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "CREATE UNIQUE INDEX wrong_column ON balances(value)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE UNIQUE INDEX balances_amount ON balances(amount)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT account_id, amount FROM balances WHERE amount=701", result));
    assertEquals(7, result.key());
    assertEquals(701, result.value());
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult scanRow = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT account_id, amount FROM balances "
                + "WHERE account_id >= 7 AND account_id < 8",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanRow));
    assertEquals(7, scanRow.key());
    assertEquals(701, scanRow.value());
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, scanRow));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT account_id, amount FROM balances "
                + "WHERE amount >= 701 AND amount < 702",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, scanRow));
    assertEquals(7, scanRow.key());
    assertEquals(701, scanRow.value());
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, scanRow));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    close(database, session);

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 6, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    session = sessions.session();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT amount FROM balances WHERE account_id=7", result));
    assertEquals(701, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT account_id, amount FROM balances WHERE amount=701", result));
    assertEquals(7, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM balances WHERE account_id=7", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "SELECT amount FROM balances WHERE account_id=7", result));
    close(database, session);
  }

  @Test
  void executesMultiColumnRowsAndIndexesArbitraryColumn(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE accounts "
                + "(id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO accounts VALUES (1, 100, 7), (2, 200, 8)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO accounts (region, id, balance) VALUES (6, 3, 300)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT balance FROM accounts WHERE id=3", result));
    assertEquals(300, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT region, id, balance FROM accounts WHERE id=3", result));
    assertEquals(3, result.columnCount());
    assertEquals(6, result.valueAt(0));
    assertEquals(3, result.valueAt(1));
    assertEquals(300, result.valueAt(2));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "INSERT INTO accounts (id, balance, balance) VALUES (4, 400, 9)",
            result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "UPDATE accounts SET balance=111, balance=112 WHERE id=1",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT region FROM accounts WHERE id=2", result));
    assertEquals(8, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET balance=250 WHERE id=2", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT region FROM accounts WHERE id=2", result));
    assertEquals(8, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET balance=260 WHERE region=8", result));
    assertEquals(1, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM accounts WHERE balance=250", result));
    assertEquals(0, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX accounts_region ON accounts(region)", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX accounts_balance ON accounts(balance)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, balance FROM accounts WHERE balance=300", result));
    assertEquals(3, result.key());
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, region FROM accounts WHERE region=8", result));
    assertEquals(2, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT * FROM accounts WHERE region=8", result));
    assertEquals(3, result.columnCount());
    assertEquals(2, result.valueAt(0));
    assertEquals(260, result.valueAt(1));
    assertEquals(8, result.valueAt(2));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO accounts VALUES (4, 400, 8)", result));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO accounts VALUES (4, 260, 10)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET region=9 WHERE id=2", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id, region FROM accounts WHERE region=8", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, region FROM accounts WHERE region=9", result));
    assertEquals(2, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET balance=260 WHERE region=9", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, balance FROM accounts WHERE balance=260", result));
    assertEquals(2, result.key());
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id, balance FROM accounts WHERE balance=250", result));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET balance=250 WHERE balance=260", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, balance FROM accounts WHERE balance=250", result));
    assertEquals(2, result.key());
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE accounts SET region=10, balance=999 WHERE id=2",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, balance FROM accounts WHERE balance=999", result));
    assertEquals(2, result.key());
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id, region FROM accounts WHERE region=10", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT balance FROM accounts WHERE id=1", result));
    assertEquals(100, result.value());
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id, balance FROM accounts WHERE balance=999", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, balance FROM accounts WHERE balance=100", result));
    assertEquals(1, result.key());
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute(
            "UPDATE accounts SET balance=100, region=6 WHERE id=2",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, balance FROM accounts WHERE balance=250", result));
    assertEquals(2, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, region FROM accounts WHERE region=9", result));
    assertEquals(2, result.key());

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, balance FROM accounts WHERE region >= 7 AND region < 10",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.key());
    assertEquals(100, row.value());
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.key());
    assertEquals(250, row.value());
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, region FROM accounts WHERE balance >= 200 AND balance < 301",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.key());
    assertEquals(9, row.value());
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(3, row.key());
    assertEquals(6, row.value());
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT region, id, balance FROM accounts WHERE id >= 1 AND id < 4",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(3, row.columnCount());
    assertEquals(7, row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertEquals(100, row.valueAt(2));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(9, row.valueAt(0));
    assertEquals(2, row.valueAt(1));
    assertEquals(250, row.valueAt(2));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(6, row.valueAt(0));
    assertEquals(3, row.valueAt(1));
    assertEquals(300, row.valueAt(2));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    close(database, session);

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 6, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    session = sessions.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT balance FROM accounts WHERE id=2", result));
    assertEquals(250, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, region FROM accounts WHERE region=9", result));
    assertEquals(2, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, balance FROM accounts WHERE balance=250", result));
    assertEquals(2, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM accounts WHERE balance=300", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id, balance FROM accounts WHERE balance=300", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id, region FROM accounts WHERE region=6", result));
    close(database, session);
  }

  @Test
  void insertsMaximumStatementAcrossFourIndexes(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events (id BIGINT PRIMARY KEY, a BIGINT, b BIGINT, "
                + "c BIGINT, d BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX events_a ON events(a)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX events_b ON events(b)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX events_c ON events(c)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX events_d ON events(d)", result));

    StringBuilder insert = new StringBuilder("INSERT INTO events VALUES ");
    for (int row = 0; row < 64; row++) {
      if (row > 0) {
        insert.append(", ");
      }
      insert.append('(').append(row)
          .append(',').append(1_000 + row)
          .append(',').append(2_000 + row)
          .append(',').append(3_000 + row)
          .append(',').append(4_000 + row).append(')');
    }
    assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, a FROM events WHERE a=1063", result));
    assertEquals(63, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, b FROM events WHERE b=2063", result));
    assertEquals(63, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, c FROM events WHERE c=3063", result));
    assertEquals(63, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, d FROM events WHERE d=4063", result));
    assertEquals(63, result.key());
    close(database, session);
  }

  @Test
  void scansAndMaintainsDuplicateSecondaryIndexEntries(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events "
                + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO events VALUES "
                + "(1, 10, 100), (2, 10, 200), (3, 10, 300), "
                + "(4, 20, 400), (5, 20, 500)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX events_category ON events(category)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM events WHERE category=10", result));
    assertEquals(3, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM events WHERE category >= 10 AND category < 21",
            result));
    assertEquals(5, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM events WHERE amount >= 150 AND amount < 350 "
                + "AND category=10",
            result));
    assertEquals(2, result.value());

    SqlScanCursor conjunction = new SqlScanCursor();
    SqlScanRowResult conjunctionRow = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, amount FROM events WHERE amount=200 AND category=10",
            conjunction));
    assertEquals(StatusCode.OK, session.nextScan(conjunction, conjunctionRow));
    assertEquals(2, conjunctionRow.valueAt(0));
    assertEquals(200, conjunctionRow.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(conjunction, conjunctionRow));
    assertEquals(StatusCode.OK, session.closeScan(conjunction, result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE events SET amount=250 WHERE category=10 AND id=2",
            result));
    assertEquals(1, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM events WHERE id=2", result));
    assertEquals(250, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "DELETE FROM events WHERE category=20 AND id=99",
            result));
    assertEquals(0, result.affectedRows());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "SELECT COUNT(*) FROM events WHERE category=10 AND missing=1",
            result));

    assertDuplicateIndexRows(session, result, new long[] {1, 2, 3, 4, 5});
    assertDuplicateIndexEquality(session, result, 10, new long[] {1, 2, 3});
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("SELECT id, category FROM events WHERE category=10", result));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE events SET amount=999 WHERE category=10", result));
    assertEquals(3, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM events WHERE id=1", result));
    assertEquals(999, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE events SET category=20 WHERE id=2", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT category FROM events WHERE id=2", result));
    assertEquals(20, result.value());
    assertDuplicateIndexEquality(session, result, 10, new long[] {1, 3});
    assertDuplicateIndexEquality(session, result, 20, new long[] {2, 4, 5});
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM events WHERE id=3", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO events VALUES (6, 10, 600)", result));
    assertDuplicateIndexRows(session, result, new long[] {1, 2, 4, 5, 6});
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE events SET category=30 WHERE category=10", result));
    assertEquals(2, result.affectedRows());
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertDuplicateIndexEquality(session, result, 10, new long[] {1, 6});
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM events WHERE category=20", result));
    assertEquals(3, result.affectedRows());
    assertDuplicateIndexRows(session, result, new long[] {1, 6});
    close(database, session);

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    session = sessions.session();
    assertDuplicateIndexRows(session, result, new long[] {1, 6});
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX events_amount ON events(amount)", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, amount FROM events WHERE amount >= 500 AND amount < 1000",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(6, row.key());
    assertEquals(600, row.value());
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.key());
    assertEquals(999, row.value());
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    close(database, session);
  }

  @Test
  void mutatesDuplicateIndexRowsBeyondFormerFixedBatchLimit(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 10, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events "
                + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)",
            result));

    StringBuilder insert = new StringBuilder("INSERT INTO events VALUES ");
    for (int row = 1; row <= 64; row++) {
      if (row > 1) {
        insert.append(", ");
      }
      insert.append('(').append(row).append(",10,").append(row * 100).append(')');
    }
    assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO events VALUES (65, 10, 6500)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX events_category ON events(category)", result));

    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE events SET amount=999 WHERE category=10", result));
    assertEquals(65, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM events WHERE id=1", result));
    assertEquals(999, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM events WHERE id=65", result));
    assertEquals(999, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM events WHERE category=10", result));
    assertEquals(65, result.affectedRows());
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT amount FROM events WHERE id=1", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT amount FROM events WHERE id=65", result));
    close(database, session);
  }

  @Test
  void fallsBackToTableScanForUnindexedPredicates(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events "
                + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO events VALUES "
                + "(1, 10, 100), (2, 10, 200), (3, 20, 300), "
                + "(4, 10, 400), (5, 30, 500)",
            result));

    assertUnindexedRows(
        session,
        result,
        "SELECT id, amount FROM events WHERE category=10",
        new long[] {1, 2, 4},
        new long[] {100, 200, 400});
    assertUnindexedRows(
        session,
        result,
        "SELECT id, amount FROM events WHERE category >= 10 AND category < 21",
        new long[] {1, 2, 3, 4},
        new long[] {100, 200, 300, 400});
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM events WHERE category=10", result));
    assertEquals(3, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM events WHERE category >= 10 AND category < 21",
            result));
    assertEquals(4, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM events WHERE id >= 2 AND id < 5", result));
    assertEquals(3, result.value());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("SELECT COUNT(*) FROM events WHERE missing=10", result));
    SqlScanCursor unindexedOrder = new SqlScanCursor();
    SqlScanRowResult orderedRow = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, category FROM events "
                + "WHERE amount >= 150 AND amount < 450 ORDER BY category",
            unindexedOrder));
    long[] orderedIds = {2, 4, 3};
    long[] orderedCategories = {10, 10, 20};
    for (int index = 0; index < orderedIds.length; index++) {
      assertEquals(StatusCode.OK, session.nextScan(unindexedOrder, orderedRow));
      assertEquals(orderedIds[index], orderedRow.valueAt(0));
      assertEquals(orderedCategories[index], orderedRow.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT amount AS category, id FROM events ORDER BY category",
            unindexedOrder));
    long[] baseFirstOrder = {100, 200, 400, 300, 500};
    for (long amount : baseFirstOrder) {
      assertEquals(StatusCode.OK, session.nextScan(unindexedOrder, orderedRow));
      assertEquals(amount, orderedRow.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id AS chosen, category FROM events ORDER BY chosen DESC LIMIT 1",
            unindexedOrder));
    assertEquals(StatusCode.OK, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(5, orderedRow.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginScan(
            "SELECT id AS chosen, amount AS chosen FROM events ORDER BY chosen",
            unindexedOrder));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginScan(
            "SELECT NULL AS chosen FROM events ORDER BY chosen",
            unindexedOrder));
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT d.id, d.amount FROM "
                + "(SELECT id, amount, category FROM events "
                + "WHERE events.category=10) d "
                + "WHERE d.amount >= 150 AND d.amount < 450 "
                + "ORDER BY amount LIMIT 2",
            unindexedOrder));
    assertEquals(StatusCode.OK, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(2, orderedRow.valueAt(0));
    assertEquals(200, orderedRow.valueAt(1));
    assertEquals(StatusCode.OK, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(4, orderedRow.valueAt(0));
    assertEquals(400, orderedRow.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, amount FROM events WHERE amount="
                + "(SELECT amount FROM events WHERE events.id=2)",
            unindexedOrder));
    assertEquals(StatusCode.OK, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(2, orderedRow.valueAt(0));
    assertEquals(200, orderedRow.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id FROM events WHERE amount="
                + "(SELECT amount FROM events WHERE id=999)",
            unindexedOrder));
    assertEquals(StatusCode.CONFLICT, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        session.beginScan(
            "SELECT id FROM events WHERE amount="
                + "(SELECT category FROM events WHERE category=10)",
            unindexedOrder));
    assertFalse(orderedRow.isAvailable());
    assertFalse(unindexedOrder.isActive());
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertUnindexedRows(
        session,
        result,
        "SELECT id, amount FROM events WHERE category IN "
            + "(SELECT category FROM events WHERE id=3)",
        new long[] {3},
        new long[] {300});
    assertUnindexedRows(
        session,
        result,
        "SELECT id, amount FROM events WHERE category NOT IN "
            + "(SELECT category FROM events WHERE id=999)",
        new long[] {1, 2, 3, 4, 5},
        new long[] {100, 200, 300, 400, 500});
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id FROM events WHERE category NOT IN "
                + "(SELECT NULL FROM events WHERE id=1)",
            unindexedOrder));
    assertEquals(StatusCode.CONFLICT, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT e.id FROM events e WHERE e.category IN "
                + "(SELECT i.category FROM events i WHERE i.id=e.id)",
            unindexedOrder));
    int correlatedMembershipRows = 0;
    while (session.nextScan(unindexedOrder, orderedRow).isOk()) {
      correlatedMembershipRows++;
    }
    assertEquals(5, correlatedMembershipRows);
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        session.beginScan(
            "SELECT e.id FROM events e WHERE e.amount="
                + "(SELECT i.amount FROM events i WHERE i.category=e.category)",
            unindexedOrder));
    assertFalse(orderedRow.isAvailable());
    assertFalse(unindexedOrder.isActive());
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM events", result));
    assertEquals(5, result.value());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id FROM events WHERE EXISTS "
                + "(SELECT id FROM events WHERE category=999)",
            unindexedOrder));
    assertEquals(StatusCode.CONFLICT, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id FROM events WHERE NOT EXISTS "
                + "(SELECT id FROM events WHERE id=999)",
            unindexedOrder));
    int existenceRows = 0;
    while (session.nextScan(unindexedOrder, orderedRow).isOk()) {
      existenceRows++;
    }
    assertEquals(5, existenceRows);
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id FROM events WHERE NOT EXISTS "
                + "(SELECT id FROM events WHERE category=10)",
            unindexedOrder));
    assertEquals(StatusCode.CONFLICT, session.nextScan(unindexedOrder, orderedRow));
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    String nested = "SELECT id FROM events";
    for (int depth = 1; depth < SqlQuery.MAXIMUM_QUERY_BLOCKS; depth++) {
      nested = "SELECT d" + depth + ".id FROM (" + nested + ") d" + depth;
    }
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(StatusCode.OK, session.beginScan(nested, unindexedOrder));
    int nestedRows = 0;
    while (session.nextScan(unindexedOrder, orderedRow).isOk()) {
      nestedRows++;
    }
    assertEquals(5, nestedRows);
    assertEquals(StatusCode.OK, session.closeScan(unindexedOrder, result));
    nested = "SELECT overflow.id FROM (" + nested + ") overflow";
    assertEquals(StatusCode.OK, unindexedOrder.reset());
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        session.beginScan(nested, unindexedOrder));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX events_category ON events(category)", result));
    SqlScanCursor groups = new SqlScanCursor();
    SqlScanRowResult group = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT category, COUNT(*) FROM events "
                + "WHERE category >= 10 AND category < 30 "
                + "AND amount >= 150 AND amount < 450 "
                + "GROUP BY category ORDER BY category",
            groups));
    assertEquals(true, "category".contentEquals(session.scanColumnName(groups, 0)));
    assertEquals(true, "count".contentEquals(session.scanColumnName(groups, 1)));
    assertEquals(StatusCode.OK, session.nextScan(groups, group));
    assertEquals(10, group.valueAt(0));
    assertEquals(2, group.valueAt(1));
    assertEquals(StatusCode.OK, session.nextScan(groups, group));
    assertEquals(20, group.valueAt(0));
    assertEquals(1, group.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(groups, group));
    assertEquals(StatusCode.OK, session.closeScan(groups, result));
    SqlScanCursor distinct = new SqlScanCursor();
    SqlScanRowResult distinctRow = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT DISTINCT category FROM events "
                + "WHERE amount >= 150 AND amount < 450 "
                + "ORDER BY category LIMIT 2",
            distinct));
    assertEquals(true, "category".contentEquals(session.scanColumnName(distinct, 0)));
    assertEquals(StatusCode.OK, session.nextScan(distinct, distinctRow));
    assertEquals(10, distinctRow.valueAt(0));
    assertEquals(StatusCode.OK, session.nextScan(distinct, distinctRow));
    assertEquals(20, distinctRow.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(distinct, distinctRow));
    assertEquals(StatusCode.OK, session.closeScan(distinct, result));
    assertEquals(StatusCode.OK, distinct.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan("SELECT DISTINCT amount FROM events", distinct));
    long[] distinctAmounts = {100, 200, 300, 400, 500};
    for (long amount : distinctAmounts) {
      assertEquals(StatusCode.OK, session.nextScan(distinct, distinctRow));
      assertEquals(amount, distinctRow.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(distinct, distinctRow));
    assertEquals(StatusCode.OK, session.closeScan(distinct, result));
    assertEquals(StatusCode.OK, groups.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT category, COUNT(*) FROM events "
                + "GROUP BY category ORDER BY category LIMIT 1",
            groups));
    assertEquals(StatusCode.OK, session.nextScan(groups, group));
    assertEquals(10, group.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(groups, group));
    assertEquals(StatusCode.OK, session.closeScan(groups, result));
    assertUnindexedRows(
        session,
        result,
        "SELECT id, amount FROM events "
            + "WHERE category >= 10 AND category < 31 ORDER BY id",
        new long[] {1, 2, 3, 4, 5},
        new long[] {100, 200, 300, 400, 500});
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX events_amount ON events(amount)", result));
    assertUnindexedRows(
        session,
        result,
        "SELECT id, amount FROM events ORDER BY amount",
        new long[] {1, 2, 3, 4, 5},
        new long[] {100, 200, 300, 400, 500});
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE categories (id BIGINT PRIMARY KEY, code BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO categories VALUES "
                + "(10, 1000), (20, 2000), (100, 100), (200, 200), "
                + "(300, 300), (400, 400), (500, 500)",
            result));
    SqlScanCursor joined = new SqlScanCursor();
    SqlScanRowResult joinedRow = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT events.id, categories.code FROM events "
                + "JOIN categories ON events.category=categories.id "
                + "WHERE events.id >= 1 AND events.id < 5 LIMIT 2",
            joined));
    long[] joinedKeys = {1, 2};
    long[] joinedCodes = {1000, 1000};
    for (int index = 0; index < joinedKeys.length; index++) {
      assertEquals(StatusCode.OK, session.nextScan(joined, joinedRow));
      assertEquals(joinedKeys[index], joinedRow.valueAt(0));
      assertEquals(joinedCodes[index], joinedRow.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(joined, joinedRow));
    assertEquals(StatusCode.OK, session.closeScan(joined, result));
    assertEquals(StatusCode.OK, joined.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT events.id, categories.code FROM events "
                + "JOIN categories ON events.category=categories.id "
                + "WHERE events.category=20 AND events.amount >= 250 "
                + "AND events.amount < 350",
            joined));
    assertEquals(StatusCode.OK, session.nextScan(joined, joinedRow));
    assertEquals(3, joinedRow.valueAt(0));
    assertEquals(2000, joinedRow.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(joined, joinedRow));
    assertEquals(StatusCode.OK, session.closeScan(joined, result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE category_labels "
                + "(id BIGINT PRIMARY KEY, category BIGINT, code BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO category_labels VALUES "
                + "(1, 10, 10001), (2, 10, 10002), (3, 20, 20001), "
                + "(4, NULL, 99999)",
            result));
    assertEquals(StatusCode.OK, joined.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT events.id, category_labels.code FROM events "
                + "JOIN category_labels "
                + "ON events.category=category_labels.category "
                + "WHERE events.id=1 AND category_labels.code>=10002",
            joined));
    assertEquals(StatusCode.OK, session.nextScan(joined, joinedRow));
    assertEquals(1, joinedRow.valueAt(0));
    assertEquals(10002, joinedRow.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(joined, joinedRow));
    assertEquals(StatusCode.OK, session.closeScan(joined, result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX category_labels_category ON category_labels(category)",
            result));
    assertEquals(StatusCode.OK, joined.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT events.id, category_labels.code FROM events "
                + "JOIN category_labels "
                + "ON events.category=category_labels.category "
                + "WHERE events.id=1 LIMIT 1",
            joined));
    assertEquals(StatusCode.OK, session.nextScan(joined, joinedRow));
    assertEquals(StatusCode.CONFLICT, session.nextScan(joined, joinedRow));
    assertEquals(StatusCode.OK, session.closeScan(joined, result));
    assertEquals(StatusCode.OK, joined.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT events.id, category_labels.code FROM events "
                + "JOIN category_labels "
                + "ON events.category=category_labels.category "
                + "WHERE events.id=1",
            joined));
    long joinedCodeSum = 0;
    long joinedCodeProduct = 1;
    for (int index = 0; index < 2; index++) {
      assertEquals(StatusCode.OK, session.nextScan(joined, joinedRow));
      assertEquals(1, joinedRow.valueAt(0));
      joinedCodeSum += joinedRow.valueAt(1);
      joinedCodeProduct *= joinedRow.valueAt(1);
    }
    assertEquals(20003, joinedCodeSum);
    assertEquals(100030002, joinedCodeProduct);
    assertEquals(StatusCode.CONFLICT, session.nextScan(joined, joinedRow));
    assertEquals(StatusCode.OK, session.closeScan(joined, result));
    assertEquals(StatusCode.OK, joined.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT events.id, category_labels.code FROM events "
                + "JOIN category_labels "
                + "ON events.category=category_labels.category "
                + "WHERE events.id=1 AND category_labels.code=10002",
            joined));
    assertEquals(StatusCode.OK, session.nextScan(joined, joinedRow));
    assertEquals(1, joinedRow.valueAt(0));
    assertEquals(10002, joinedRow.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(joined, joinedRow));
    assertEquals(StatusCode.OK, session.closeScan(joined, result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE UNIQUE INDEX categories_code ON categories(code)",
            result));
    assertEquals(StatusCode.OK, joined.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT events.id, categories.id FROM events "
                + "JOIN categories ON events.amount=categories.code",
            joined));
    for (int index = 1; index <= 5; index++) {
      assertEquals(StatusCode.OK, session.nextScan(joined, joinedRow));
      assertEquals(index, joinedRow.valueAt(0));
      assertEquals(index * 100, joinedRow.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(joined, joinedRow));
    assertEquals(StatusCode.OK, session.closeScan(joined, result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginScan(
            "SELECT id, code FROM events "
                + "JOIN categories ON events.category=categories.id",
            new SqlScanCursor()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("SELECT id, amount FROM events WHERE category=10", result));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE events SET amount=999 WHERE category=10", result));
    assertEquals(3, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM events WHERE id=4", result));
    assertEquals(999, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM events WHERE amount=999", result));
    assertEquals(3, result.affectedRows());
    assertEquals(StatusCode.OK, session.execute("SELECT COUNT(*) FROM events", result));
    assertEquals(2, result.value());
    SqlScanCursor aggregate = new SqlScanCursor();
    SqlScanRowResult aggregateRow = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan("SELECT COUNT(*) FROM events", aggregate));
    assertEquals("count", session.scanColumnName(aggregate, 0).toString());
    assertEquals(StatusCode.OK, session.nextScan(aggregate, aggregateRow));
    assertEquals(2, aggregateRow.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(aggregate, aggregateRow));
    assertEquals(StatusCode.OK, session.closeScan(aggregate, result));
    assertEquals(false, result.transactionActive());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginScan(
            "SELECT id FROM events WHERE missing=10", new SqlScanCursor()));
    close(database, session);
  }

  @Test
  void updatesAndDeletesBoundedPredicateRanges(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events "
                + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO events VALUES "
                + "(1, 10, 100), (2, 15, 200), (3, 20, 300), "
                + "(4, 25, 400), (5, 30, 500), (6, 35, 600)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX events_category ON events(category)", result));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE events SET amount=999 "
                + "WHERE category >= 15 AND category < 31",
            result));
    assertEquals(4, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "DELETE FROM events WHERE amount >= 900 AND amount < 1000",
            result));
    assertEquals(4, result.affectedRows());
    assertEquals(StatusCode.OK, session.execute("SELECT COUNT(*) FROM events", result));
    assertEquals(2, result.value());
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE events SET amount=777 WHERE id >= 1 AND id < 7", result));
    assertEquals(2, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "DELETE FROM events WHERE id >= 7 AND id < 7", result));
    assertEquals(0, result.affectedRows());
    assertEquals(StatusCode.OK, session.execute("SELECT COUNT(*) FROM events", result));
    assertEquals(2, result.value());
    close(database, session);
  }

  @Test
  void spillsAndMergesUnindexedSortRuns(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 6, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE spilled (id BIGINT PRIMARY KEY, value BIGINT)", result));
    int rowCount = 1_025;
    for (int first = 0; first < rowCount; first += SqlCommand.MAXIMUM_INSERT_ROWS) {
      int end = Math.min(first + SqlCommand.MAXIMUM_INSERT_ROWS, rowCount);
      StringBuilder insert = new StringBuilder("INSERT INTO spilled VALUES ");
      for (int key = first; key < end; key++) {
        if (key > first) {
          insert.append(',');
        }
        insert.append('(')
            .append(key)
            .append(',')
            .append(rowCount - key)
            .append(')');
      }
      assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
    }
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, value FROM spilled ORDER BY value LIMIT 3", cursor));
    for (long value = 1; value <= 3; value++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(value, row.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan("SELECT id, value FROM spilled ORDER BY value", cursor));
    for (long value = 1; value <= rowCount; value++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(rowCount - value, row.valueAt(0));
      assertEquals(value, row.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, value FROM spilled ORDER BY value DESC LIMIT 3",
            cursor));
    for (long value = rowCount; value > rowCount - 3; value--) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(value, row.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    close(database, session);
  }

  private static void assertQueuedStatementGranted(
      SqlSession holder,
      SqlSession waiter,
      String holderCompletion,
      String waitingSql,
      SqlExecutionResult holderResult) throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    AtomicReference<Thread> worker = new AtomicReference<>();
    SqlExecutionResult waitingResult = new SqlExecutionResult();
    try {
      Future<StatusCode> granted = executor.submit(() -> {
        worker.set(Thread.currentThread());
        entered.countDown();
        return waiter.execute(waitingSql, waitingResult);
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      Thread.State state = Thread.State.NEW;
      while (!granted.isDone() && System.nanoTime() < deadline) {
        Thread thread = worker.get();
        if (thread != null) {
          state = thread.getState();
          if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) break;
        }
        Thread.onSpinWait();
      }
      assertFalse(granted.isDone());
      assertTrue(state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);
      assertEquals(StatusCode.OK, holder.execute(holderCompletion, holderResult));
      assertEquals(StatusCode.OK, granted.get(5, TimeUnit.SECONDS));
      assertTrue(waitingResult.transactionActive());
    } finally {
      executor.shutdownNow();
    }
  }

  private static void close(
      RelationalDatabase database,
      SqlSession... sessions) {
    for (SqlSession session : sessions) {
      assertEquals(StatusCode.OK, session.close());
    }
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertScalar(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      int descriptor,
      long expected) {
    assertEquals(StatusCode.OK, session.execute(sql, result));
    assertEquals(descriptor, result.typeDescriptorAt(0));
    assertEquals(expected, result.valueAt(0));
  }

  private static void assertDuplicateIndexRows(
      SqlSession session,
      SqlExecutionResult result,
      long[] expectedKeys) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, category FROM events "
                + "WHERE category >= 10 AND category < 21",
            cursor));
    boolean[] seen = new boolean[7];
    long previousCategory = Long.MIN_VALUE;
    int count = 0;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      assertEquals(true, row.value() >= previousCategory);
      previousCategory = row.value();
      seen[(int) row.key()] = true;
      count++;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(expectedKeys.length, count);
    for (long key : expectedKeys) {
      assertEquals(true, seen[(int) key]);
    }
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertDuplicateIndexEquality(
      SqlSession session,
      SqlExecutionResult result,
      long value,
      long[] expectedKeys) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, category FROM events WHERE category=" + value,
            cursor));
    boolean[] seen = new boolean[7];
    int count = 0;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      assertEquals(value, row.value());
      seen[(int) row.key()] = true;
      count++;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(expectedKeys.length, count);
    for (long key : expectedKeys) {
      assertEquals(true, seen[(int) key]);
    }
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertUnindexedRows(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      long[] expectedKeys,
      long[] expectedValues) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (int index = 0; index < expectedKeys.length; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(expectedKeys[index], row.key());
      assertEquals(expectedValues[index], row.value());
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }
}
