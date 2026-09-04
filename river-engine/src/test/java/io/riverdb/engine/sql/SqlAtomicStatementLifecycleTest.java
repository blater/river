package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
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

final class SqlAtomicStatementLifecycleTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(887, 907);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void implicitCompletionRetriesWithoutDoubleCompletingStatement(
      @TempDir Path root) {
    Fixture fixture = open(root);
    TableDefinition table = createTable(fixture.database);
    RelationalScanCursor cursor = new RelationalScanCursor();

    assertEquals(
        StatusCode.OK,
        fixture.lifecycle.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, fixture.session.beginScan(table, cursor));
    assertEquals(StatusCode.CONFLICT, fixture.lifecycle.finish(StatusCode.OK));
    assertTrue(fixture.lifecycle.isActive());

    assertEquals(StatusCode.OK, fixture.session.closeScan(cursor));
    assertEquals(StatusCode.OK, fixture.lifecycle.retry());
    assertFalse(fixture.lifecycle.isActive());
    assertFalse(fixture.session.isTransactionActive());
    assertEquals(StatusCode.OK, fixture.lifecycle.retry());
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  @Test
  void explicitBeginStatementFailureReleasesItsStatementSavepoint(
      @TempDir Path root) {
    Fixture fixture = open(root);
    assertEquals(
        StatusCode.OK,
        fixture.transactions.beginExplicit(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, fixture.transactions.beginStatement());

    assertEquals(
        StatusCode.CONFLICT,
        fixture.lifecycle.begin(IsolationLevel.READ_COMMITTED));
    assertFalse(fixture.lifecycle.isActive());
    assertEquals(StatusCode.OK, fixture.transactions.completeStatement());

    assertEquals(
        StatusCode.OK,
        fixture.lifecycle.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, fixture.lifecycle.finish(StatusCode.OK));
    assertFalse(fixture.lifecycle.isActive());
    assertEquals(StatusCode.OK, fixture.transactions.commitExplicit());
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  @Test
  void failedTerminalAttemptRetainsExplicitStateForRetry(@TempDir Path root) {
    Fixture fixture = open(root);
    assertEquals(
        StatusCode.OK,
        fixture.transactions.beginExplicit(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, fixture.transactions.beginStatement());

    assertEquals(StatusCode.CONFLICT, fixture.transactions.abortExplicit());
    assertTrue(fixture.transactions.isExplicit());
    assertTrue(fixture.session.transactionActive());

    assertEquals(StatusCode.OK, fixture.transactions.completeStatement());
    assertEquals(StatusCode.OK, fixture.transactions.abortExplicit());
    assertFalse(fixture.transactions.isExplicit());
    assertFalse(fixture.session.transactionActive());
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  @Test
  void explicitFailureResumesAfterStatementCompletionBecomesPossible(
      @TempDir Path root) {
    Fixture fixture = open(root);
    TableDefinition table = createTable(fixture.database);
    RelationalScanCursor cursor = new RelationalScanCursor();
    assertEquals(
        StatusCode.OK,
        fixture.transactions.beginExplicit(IsolationLevel.READ_COMMITTED));
    assertEquals(
        StatusCode.OK,
        fixture.lifecycle.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, fixture.session.beginScan(table, cursor));

    assertEquals(
        StatusCode.CONFLICT,
        fixture.lifecycle.finish(StatusCode.CHECK_VIOLATION));
    assertTrue(fixture.lifecycle.isActive());
    assertEquals(StatusCode.OK, fixture.session.closeScan(cursor));
    assertEquals(StatusCode.CHECK_VIOLATION, fixture.lifecycle.retry());
    assertFalse(fixture.lifecycle.isActive());

    assertEquals(
        StatusCode.OK,
        fixture.lifecycle.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, fixture.lifecycle.finish(StatusCode.OK));
    assertEquals(StatusCode.OK, fixture.transactions.commitExplicit());
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  private static TableDefinition createTable(RelationalDatabase database) {
    TableDefinition table = new TableDefinition();
    assertEquals(StatusCode.OK, database.createTable("events", table));
    return table;
  }

  private static Fixture open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
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
        new SqlAtomicStatementLifecycle(session, transactions));
  }

  private record Fixture(
      RelationalDatabase database,
      RelationalSession session,
      SqlTransactionState transactions,
      SqlAtomicStatementLifecycle lifecycle) {}
}
