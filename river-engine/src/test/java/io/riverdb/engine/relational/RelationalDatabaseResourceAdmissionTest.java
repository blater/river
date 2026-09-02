package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.engine.runtime.RuntimeResourceRoot;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.engine.sql.SqlExecutionResult;
import io.riverdb.engine.sql.SqlScanCursor;
import io.riverdb.engine.sql.SqlScanRowResult;
import io.riverdb.engine.sql.SqlSession;
import io.riverdb.engine.sql.SqlSessionOpenResult;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RelationalDatabaseResourceAdmissionTest {
  private static final long DATABASE_BYTES = 128_000_000L;
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5245534f55524345L, 0x4441544142415345L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void ordinaryRelationalLifecycleUsesTheDerivedGovernor(@TempDir Path directory) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(directory, DATABASE, GENERATION, 8, opened));
    assertTrue(opened.database().resourceGoverned());
    assertTrue(opened.database().resourceWriteEntryCapacity() > 384);
    assertEquals(StatusCode.OK, opened.database().close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(directory, DATABASE, GENERATION, 8, opened));
    assertTrue(opened.database().resourceGoverned());
    assertTrue(opened.database().resourceWriteEntryCapacity() > 384);
    assertEquals(StatusCode.OK, opened.database().close());
  }

  @Test
  void governedCreateFailureCloseAndReopenConserveRootCapacity(@TempDir Path directory) {
    RuntimeResourceRoot root = root(256_000_000L);
    DatabaseResourcePlan plan = plan();
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, plan, directory, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    assertEquals(DATABASE_BYTES, root.admittedAccountedBytes());

    assertEquals(StatusCode.CONFLICT,
        RelationalDatabase.create(root, plan, directory, DATABASE, GENERATION, 8, opened));
    assertNull(opened.database());
    assertEquals(DATABASE_BYTES, root.admittedAccountedBytes());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(0, root.admittedAccountedBytes());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(
            root, plan, directory, DATABASE, GENERATION, 8, opened));
    assertEquals(DATABASE_BYTES, root.admittedAccountedBytes());
    assertEquals(StatusCode.OK, opened.database().close());
    assertEquals(0, root.admittedAccountedBytes());
  }

  @Test
  void rejectsOwnerPlanSmallerThanTransactionAdmissionBeforeOpeningFiles(
      @TempDir Path directory) {
    RuntimeResourceRoot root = root(256_000_000L);
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        RelationalDatabase.create(
            root, plan(1), directory.resolve("database"), DATABASE, GENERATION, 8, opened));
    assertNull(opened.database());
    assertEquals(0, root.admittedAccountedBytes());
    assertFalse(java.nio.file.Files.exists(directory.resolve("database")));
  }

  @Test
  void openRetainsTheConfiguredLockProviderByteEnvelope(@TempDir Path directory) {
    RuntimeResourceRoot root = root(256_000_000L);
    DatabaseResourcePlan plan = plan(1);
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.create(
            root, plan,
            directory, DATABASE, GENERATION, 1, opened));
    assertEquals(plan.lockProviderBytes(),
        opened.database().retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK, opened.database().close());
    assertEquals(0, root.admittedAccountedBytes());
  }

  @Test
  void unpublishedFailureDestructorReleasesAdmissionAfterOrdinaryCloseConflicts(
      @TempDir Path directory) {
    RuntimeResourceRoot root = root(256_000_000L);
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.create(
            root, plan(), directory, DATABASE, GENERATION, 8, opened));
    EmbeddedDatabase database = opened.database();
    EmbeddedSessionOpenResult session = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(8_192, session));
    assertEquals(StatusCode.OK, session.session().begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.CONFLICT, database.close());
    assertEquals(StatusCode.OK, database.closeAfterOpenFailure());
    assertEquals(0, root.admittedAccountedBytes());
    assertEquals(StatusCode.CLOSED, database.closeAfterOpenFailure());
  }

  @Test
  void transactionPreflightUsesOneLeaseAndAbortReturnsIt(
      @TempDir Path directory) {
    RuntimeResourceRoot root = root(256_000_000L);
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.create(
            root, plan(8, 1), directory, DATABASE, GENERATION, 8, opened));
    EmbeddedDatabase database = opened.database();
    EmbeddedSessionOpenResult first = new EmbeddedSessionOpenResult();
    EmbeddedSessionOpenResult second = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(8_192, first));
    assertEquals(StatusCode.OK, database.createSession(8_192, second));
    assertEquals(StatusCode.OK, first.session().begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, second.session().begin(IsolationLevel.READ_COMMITTED));

    assertEquals(StatusCode.OK, first.session().preflightPendingMutation(8));
    assertEquals(StatusCode.RETRY, second.session().preflightPendingMutation(8));
    assertEquals(1, database.liveResourceWriteEntries());
    assertEquals(StatusCode.OK, first.session().protectKey(1, 1));
    assertEquals(1, database.liveResourceWriteEntries());

    TransactionOutcome outcome = new TransactionOutcome();
    assertEquals(StatusCode.OK, second.session().abort(outcome));
    assertEquals(StatusCode.OK, first.session().abort(outcome));
    assertEquals(0, database.liveResourceWriteEntries());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(0, root.admittedAccountedBytes());
  }

  @Test
  void writeWorkspaceAndLockProviderUseSeparateRetainedBudgets(@TempDir Path directory) {
    RuntimeResourceRoot root = root(256_000_000L);
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.create(
            root, plan(), directory, DATABASE, GENERATION, 8, opened));
    EmbeddedDatabase database = opened.database();
    EmbeddedSessionOpenResult openedSession = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(8_192, openedSession));
    var session = openedSession.session();
    TransactionOutcome outcome = new TransactionOutcome();
    long providerBytes = database.retainedDatabaseAccountedBytes();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, session.preflightPendingMutation(8));
    long retainedAfterWrite = database.retainedDatabaseAccountedBytes();
    assertTrue(retainedAfterWrite > providerBytes);
    assertEquals(StatusCode.OK, session.protectKey(1, 1));
    assertEquals(0, database.liveResourceAccountedBytes());
    long retainedAfterLock = database.retainedDatabaseAccountedBytes();
    assertEquals(retainedAfterWrite, retainedAfterLock);
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(0, database.liveResourceAccountedBytes());
    assertEquals(retainedAfterLock, database.retainedDatabaseAccountedBytes());

    EmbeddedSessionOpenResult secondOpened = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(8_192, secondOpened));
    var second = secondOpened.session();
    assertEquals(StatusCode.OK, second.begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, second.protectKey(1, 1));
    assertEquals(retainedAfterLock, database.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK, second.preflightPendingMutation(8));
    assertEquals(0, database.liveResourceAccountedBytes());
    assertEquals(
        retainedAfterLock + retainedAfterWrite - providerBytes,
        database.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK, second.abort(outcome));
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void sessionAdmissionIsBoundedAndCloseReturnsItsRetainedWorkspace(
      @TempDir Path directory) {
    RuntimeResourceRoot root = root(256_000_000L);
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.create(
            root, plan(2), directory, DATABASE, GENERATION, 1, opened));
    EmbeddedDatabase database = opened.database();
    long providerBytes = database.retainedDatabaseAccountedBytes();
    EmbeddedSessionOpenResult first = new EmbeddedSessionOpenResult();
    EmbeddedSessionOpenResult second = new EmbeddedSessionOpenResult();
    EmbeddedSessionOpenResult refused = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(8_192, first));
    assertEquals(StatusCode.OK, database.createSession(8_192, second));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        database.createSession(8_192, refused));

    assertEquals(StatusCode.OK, first.session().begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.OK, first.session().preflightPendingMutation(8));
    assertTrue(database.retainedDatabaseAccountedBytes() > providerBytes);
    assertEquals(StatusCode.OK, first.session().abort(new TransactionOutcome()));
    assertEquals(StatusCode.OK, first.session().close());
    assertEquals(providerBytes, database.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK, database.createSession(8_192, refused));
    assertEquals(StatusCode.OK, database.close());
    assertEquals(0, root.admittedAccountedBytes());
    assertEquals(StatusCode.CLOSED, second.session().begin(IsolationLevel.READ_COMMITTED));
    assertEquals(StatusCode.CLOSED, refused.session().begin(IsolationLevel.READ_COMMITTED));
  }

  @Test
  void governedSessionCommitsMoreThanLegacy384Writes(
      @TempDir Path directory) {
    RuntimeResourceRoot root = root(256_000_000L);
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.create(
            root, plan(1), directory, DATABASE, GENERATION, 1, opened));
    EmbeddedDatabase database = opened.database();
    EmbeddedSessionOpenResult session = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(8_192, session));
    assertEquals(StatusCode.OK, session.session().begin(IsolationLevel.READ_COMMITTED));
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    for (int key = 1; key <= 385; key++) {
      row.putLong(0, key);
      assertEquals(StatusCode.OK, session.session().insert(1, key, row));
    }
    assertEquals(385, database.liveResourceWriteEntries());
    assertEquals(StatusCode.OK, session.session().commit(new TransactionOutcome()));
    assertEquals(0, database.liveResourceWriteEntries());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void lifecycleCompilationUsesAndReleasesTheGovernedSessionWorkspace(
      @TempDir Path directory) {
    RuntimeResourceRoot root = root(256_000_000L);
    EmbeddedDatabaseOpenResult opened = new EmbeddedDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedDatabase.create(
            root, plan(1), directory, DATABASE, GENERATION, 1, opened));
    EmbeddedDatabase database = opened.database();
    long providerBytes = database.retainedDatabaseAccountedBytes();
    EmbeddedSessionOpenResult sessionResult = new EmbeddedSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(8_192, sessionResult));
    var session = sessionResult.session();
    TupleShape.Result shapeResult = new TupleShape.Result();
    assertEquals(StatusCode.OK,
        TupleShape.create(new int[] {SqlTypeDescriptor.BIGINT}, shapeResult));
    TransactionOutcome outcome = new TransactionOutcome();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.preflightTupleIndexLifecycles(1));
    assertEquals(StatusCode.OK, session.stageTupleIndexBuilding(
        19, 1_000, 2_000, 19, shapeResult.value()));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.preflightTupleIndexLifecycles(1));
    assertEquals(StatusCode.OK, session.stageTupleIndexReady(
        19, 1_000, 2_000, 19, shapeResult.value()));
    for (int key = 1; key <= 385; key++) {
      ByteBuffer row = ByteBuffer.allocate(Long.BYTES);
      row.putLong(key).flip();
      assertEquals(StatusCode.OK, session.insert(77, key, row));
    }
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertTrue(database.retainedDatabaseAccountedBytes() > providerBytes);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(providerBytes, database.retainedDatabaseAccountedBytes());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void governedSqlCommitsAndReopensMoreThan384IndexedTupleMutations(
      @TempDir Path directory) {
    RuntimeResourceRoot root = root(256_000_000L);
    DatabaseResourcePlan plan = plan();
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(
            root, plan, directory, DATABASE, GENERATION, 8, opened));
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(StatusCode.OK,
        session.execute(
            "CREATE TABLE indexed_capacity (id BIGINT PRIMARY KEY, amount BIGINT)",
            execution));
    assertEquals(StatusCode.OK, session.execute("BEGIN", execution));
    for (int key = 1; key <= 385; key++) {
      assertEquals(StatusCode.OK,
          session.execute(
              "INSERT INTO indexed_capacity VALUES (" + key + "," + (key * 10L) + ")",
              execution));
    }
    assertEquals(StatusCode.OK, session.execute("COMMIT", execution));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, opened.database().close());

    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(
            root, plan, directory, DATABASE, GENERATION, 8, opened));
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), sessions));
    session = sessions.session();
    SqlScanCursor scan = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK,
        session.beginScan("SELECT id FROM indexed_capacity ORDER BY id DESC", scan));
    for (int expected = 385; expected > 0; expected--) {
      assertEquals(StatusCode.OK, session.nextScan(scan, row));
      assertEquals(expected, row.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(scan, row));
    assertEquals(StatusCode.OK, session.closeScan(scan, execution));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, opened.database().close());
    assertEquals(0, root.admittedAccountedBytes());
  }

  private static RuntimeResourceRoot root(long bytes) {
    RuntimeResourceRoot.Result result = new RuntimeResourceRoot.Result();
    assertEquals(StatusCode.OK, RuntimeResourceRoot.create(bytes, result));
    return result.root();
  }

  private static DatabaseResourcePlan plan() {
    return plan(8);
  }

  private static DatabaseResourcePlan plan(int owners) {
    return plan(owners, 10_000);
  }

  private static DatabaseResourcePlan plan(int owners, long writeEntries) {
    DatabaseResourcePlanRequest request = new DatabaseResourcePlanRequest()
        .memory(DATABASE_BYTES, 20_000_000, 10_000_000, 2_000_000, 32_000_000)
        .lockProviderBytes(8_000_000)
        .capacity(owners, writeEntries, 1_000, 64_000_000)
        .maximumDelivery(Math.min(1_000, writeEntries), 100, 8_000_000);
    DatabaseResourcePlan.Result result = new DatabaseResourcePlan.Result();
    assertEquals(StatusCode.OK, DatabaseResourcePlan.compile(request, result));
    return result.plan();
  }
}
