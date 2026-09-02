package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.tx.api.IsolationLevel;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlStreamingStatementLifecycleTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(881, 883);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void cleanupFailureDoesNotAdvanceAndRetryCompletesOnce(
      @TempDir Path root) {
    Fixture fixture = open(root);
    SqlExecutionResult result = new SqlExecutionResult();

    assertEquals(StatusCode.OK, fixture.lifecycle.begin());
    assertTrue(fixture.lifecycle.isActive());
    assertFalse(fixture.lifecycle.statementCompleted());
    assertEquals(
        StatusCode.IO_FAILURE,
        fixture.lifecycle.finish(StatusCode.IO_FAILURE, result));
    assertTrue(fixture.lifecycle.isActive());
    assertFalse(fixture.lifecycle.statementCompleted());

    assertEquals(StatusCode.OK, fixture.lifecycle.finish(StatusCode.OK, result));
    assertFalse(fixture.lifecycle.isActive());
    assertFalse(fixture.session.isTransactionActive());
    assertTrue(result.commitSequence() > 0);
    assertEquals(
        StatusCode.CONFLICT,
        fixture.lifecycle.finish(StatusCode.OK, result));
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  @Test
  void failedStartRetainsItsFrameUntilPhysicalCleanupCompletes(
      @TempDir Path root) {
    Fixture fixture = open(root);

    assertEquals(StatusCode.OK, fixture.lifecycle.begin());
    assertEquals(
        StatusCode.IO_FAILURE,
        fixture.lifecycle.failStart(StatusCode.IO_FAILURE, false));
    assertTrue(fixture.lifecycle.isActive());
    assertFalse(fixture.lifecycle.statementCompleted());
    assertTrue(fixture.session.isTransactionActive());

    assertEquals(
        StatusCode.IO_FAILURE,
        fixture.lifecycle.failStart(StatusCode.OK, true));
    assertFalse(fixture.lifecycle.isActive());
    assertFalse(fixture.session.isTransactionActive());
    assertEquals(StatusCode.OK, fixture.lifecycle.begin());
    assertEquals(
        StatusCode.OK,
        fixture.lifecycle.finish(StatusCode.OK, new SqlExecutionResult()));
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  @Test
  void statementCompletionRetriesAfterAnActivePhysicalCursorCloses(
      @TempDir Path root) {
    Fixture fixture = open(root);
    TableDefinition table = new TableDefinition();
    assertEquals(StatusCode.OK, fixture.database.createTable("events", table));
    RelationalScanCursor cursor = new RelationalScanCursor();

    assertEquals(StatusCode.OK, fixture.lifecycle.begin());
    assertEquals(StatusCode.OK, fixture.session.beginScan(table, cursor));
    assertEquals(
        StatusCode.CONFLICT,
        fixture.lifecycle.finish(
            StatusCode.OK, true, new SqlExecutionResult()));
    assertTrue(fixture.lifecycle.isActive());
    assertFalse(fixture.lifecycle.statementCompleted());

    assertEquals(StatusCode.OK, fixture.session.closeScan(cursor));
    assertEquals(
        StatusCode.OK,
        fixture.lifecycle.finish(
            StatusCode.OK, true, new SqlExecutionResult()));
    assertFalse(fixture.lifecycle.isActive());
    assertFalse(fixture.session.isTransactionActive());
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  @Test
  void explicitFailureRollsBackAndReleasesItsStatementSavepoint(
      @TempDir Path root) {
    Fixture fixture = open(root);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        fixture.transactions.beginExplicit(IsolationLevel.REPEATABLE_READ));

    assertEquals(StatusCode.OK, fixture.lifecycle.begin());
    assertEquals(
        StatusCode.RETRY,
        fixture.lifecycle.finish(StatusCode.RETRY, true, result));
    assertFalse(fixture.lifecycle.isActive());
    assertTrue(fixture.session.isTransactionActive());

    assertEquals(StatusCode.OK, fixture.lifecycle.begin());
    assertEquals(StatusCode.OK, fixture.lifecycle.finish(StatusCode.OK, result));
    assertEquals(StatusCode.OK, fixture.transactions.commitExplicit());
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  private static Fixture open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 4, opened));
    RelationalDatabase database = opened.database();
    RelationalSessionOpenResult sessionResult =
        new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RelationalSession session = sessionResult.session();
    SqlTransactionState transactions = new SqlTransactionState(session);
    return new Fixture(
        database,
        session,
        transactions,
        new SqlStreamingStatementLifecycle(session, transactions));
  }

  private record Fixture(
      RelationalDatabase database,
      RelationalSession session,
      SqlTransactionState transactions,
      SqlStreamingStatementLifecycle lifecycle) {}
}
