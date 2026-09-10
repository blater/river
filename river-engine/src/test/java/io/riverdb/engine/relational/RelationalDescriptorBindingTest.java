package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.runtime.SqlRuntimeLease;
import io.riverdb.engine.runtime.SqlRuntimeLeaseResult;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transaction lifetime and DDL invalidation coverage for descriptor bindings. */
final class RelationalDescriptorBindingTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x42494e44494e4731L, 0x42494e44494e4732L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void repeatedResolutionReturnsIndependentPinsWithinOneTransaction(@TempDir Path root) {
    RelationalDatabase database = createDatabase(root);
    createInitial(database, "items");
    RelationalSession session = openSession(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    SchemaPin first = new SchemaPin();
    SchemaPin second = new SchemaPin();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", first, detail));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", second, detail));
    assertSame(first.descriptor(), second.descriptor());
    assertEquals(StatusCode.OK, first.release());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, second.release());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void bindingBudgetFailureLeavesDestinationInactiveAndRetryable(@TempDir Path root) {
    RelationalDatabase database = createDatabase(root);
    createInitial(database, "items");
    SqlRuntimeLeaseResult blockerResult = new SqlRuntimeLeaseResult();
    assertEquals(StatusCode.OK, database.services().acquireRuntime(blockerResult));
    SqlRuntimeLease blocker = blockerResult.lease();
    long blockedBytes = blocker.config().sessionShapeCacheBytes() - 1;
    assertEquals(StatusCode.OK, blocker.reserve(blockedBytes));

    RelationalSession session = openSession(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    SchemaPin destination = new SchemaPin();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        session.resolveDescriptor("items", destination, detail));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, detail.code());
    assertFalse(destination.isActive());

    assertEquals(StatusCode.OK, blocker.releaseReserved(blockedBytes));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", destination, detail));
    assertTrue(destination.isActive());
    assertEquals(StatusCode.OK, destination.release());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, blocker.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void renameDoesNotReturnAStaleCachedName(@TempDir Path root) {
    RelationalDatabase database = createDatabase(root);
    createInitial(database, "items");
    RelationalSession session = openSession(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    SchemaPin current = new SchemaPin();
    SchemaPin renamed = new SchemaPin();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", current, detail));
    assertEquals(StatusCode.OK,
        session.renameDescriptorTable("items", "renamed", current, detail),
        detail.toString());
    assertEquals(StatusCode.CONFLICT,
        session.resolveDescriptor("items", new SchemaPin(), detail));
    assertEquals(StatusCode.OK, session.resolveDescriptor("renamed", renamed, detail));
    assertSame(current.descriptor(), renamed.descriptor());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, current.release());
    assertEquals(StatusCode.OK, renamed.release());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void committedAndAbortedRenamesAreVisibleToLaterTransactions(@TempDir Path root) {
    RelationalDatabase database = createDatabase(root);
    createInitial(database, "items");
    RelationalSession session = openSession(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    SchemaPin current = new SchemaPin();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", current, detail));
    assertEquals(StatusCode.OK,
        session.renameDescriptorTable("items", "renamed", current, detail));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, current.release());

    SchemaPin committed = new SchemaPin();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.resolveDescriptor("renamed", committed, detail));
    assertEquals(StatusCode.CONFLICT,
        session.resolveDescriptor("items", new SchemaPin(), detail));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, committed.release());

    SchemaPin beforeAbort = new SchemaPin();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.resolveDescriptor("renamed", beforeAbort, detail));
    assertEquals(StatusCode.OK,
        session.renameDescriptorTable("renamed", "aborted", beforeAbort, detail));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, beforeAbort.release());

    SchemaPin afterAbort = new SchemaPin();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.resolveDescriptor("renamed", afterAbort, detail));
    assertEquals(StatusCode.CONFLICT,
        session.resolveDescriptor("aborted", new SchemaPin(), detail));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, afterAbort.release());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rollbackToSavepointRestoresTheDescriptorName(@TempDir Path root) {
    RelationalDatabase database = createDatabase(root);
    createInitial(database, "items");
    RelationalSession session = openSession(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    SchemaPin current = new SchemaPin();
    SchemaPin restored = new SchemaPin();
    IndexedSavepoint savepoint = new IndexedSavepoint();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", current, detail));
    assertEquals(StatusCode.OK, session.createSavepoint(savepoint));
    assertEquals(StatusCode.OK, session.dropDescriptorTable("items", current, detail));
    assertEquals(StatusCode.CONFLICT,
        session.resolveDescriptor("items", new SchemaPin(), detail));
    assertEquals(StatusCode.OK, session.rollbackToSavepoint(savepoint));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", restored, detail));
    assertSame(current.descriptor(), restored.descriptor());
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, current.release());
    assertEquals(StatusCode.OK, restored.release());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void terminalDdlOutcomeIsVisibleToTheNextTransaction(@TempDir Path root) {
    RelationalDatabase database = createDatabase(root);
    createInitial(database, "items");
    RelationalSession session = openSession(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    SchemaPin current = new SchemaPin();

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", current, detail));
    assertEquals(StatusCode.OK, session.prepareDescriptorSuccessor(
        "items", current, successor(current.descriptor()), detail));
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, current.release());

    SchemaPin afterAbort = new SchemaPin();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", afterAbort, detail));
    assertEquals(1, afterAbort.catalogGeneration());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, afterAbort.release());

    SchemaPin predecessor = new SchemaPin();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", predecessor, detail));
    assertEquals(StatusCode.OK, session.prepareDescriptorSuccessor(
        "items", predecessor, successor(predecessor.descriptor()), detail));
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, predecessor.release());

    SchemaPin afterCommit = new SchemaPin();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, session.resolveDescriptor("items", afterCommit, detail));
    assertEquals(2, afterCommit.catalogGeneration());
    assertEquals(StatusCode.OK, session.abort(outcome));
    assertEquals(StatusCode.OK, afterCommit.release());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static RelationalDatabase createDatabase(Path root) {
    RelationalDatabaseOpenResult result = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, result));
    return result.database();
  }

  private static RelationalSession openSession(RelationalDatabase database) {
    RelationalSessionOpenResult result = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(result));
    return result.session();
  }

  private static void createInitial(RelationalDatabase database, CharSequence name) {
    RelationalSession session = openSession(database);
    TransactionOutcome outcome = new TransactionOutcome();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK,
        session.prepareDescriptorTable(name, descriptor(), detail), detail.toString());
    assertEquals(StatusCode.OK, session.commit(outcome));
    assertEquals(StatusCode.OK, session.close());
  }

  private static TableDescriptor descriptor() {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT}, new CharSequence[] {"id"},
        new boolean[] {false}, columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createUnbound(
        KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0}, primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.createProposedSuccessor(
        1, 1, 1, columns.value(), primary.value(), null, null, table, null));
    return table.value();
  }

  private static TableDescriptor successor(TableDescriptor current) {
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, new RelationalDescriptorIndexChange().add(
        current, "by_id", true, new int[] {0}, 0, 1,
        result, new StatusDetail(128)));
    return result.value();
  }
}
