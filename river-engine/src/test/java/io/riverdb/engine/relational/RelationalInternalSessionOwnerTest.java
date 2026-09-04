package io.riverdb.engine.relational;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RelationalInternalSessionOwnerTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x494e5445524e414cL, 0x53455353494f4e31L);

  @Test
  void retriesTerminalCleanupBeforeReleasingTheRegistrySlot(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(2), root, DATABASE, WalGeneration.of(1), 2, opened));
    RelationalDatabase database = opened.database();
    RelationalSessionOpenResult sessionResult = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RelationalSession session = sessionResult.session();
    TransactionOutcome outcome = new TransactionOutcome();
    TableDefinition table = new TableDefinition();
    assertEquals(StatusCode.OK, session.begin(IsolationLevel.SERIALIZABLE));
    assertEquals(StatusCode.OK, session.createTable("cleanup_rows", table));
    assertEquals(StatusCode.OK, session.commit(outcome));

    assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
    RelationalScanCursor cursor = new RelationalScanCursor();
    assertEquals(StatusCode.OK, session.beginScan(table, cursor));
    RelationalInternalSessionOwner owner = new RelationalInternalSessionOwner();
    assertEquals(StatusCode.CONFLICT, owner.finish(session, outcome.reset(), StatusCode.OK));
    assertEquals(StatusCode.OK, session.closeScan(cursor));
    assertEquals(StatusCode.OK, owner.retry());

    sessionResult.reset();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    assertEquals(StatusCode.OK, sessionResult.session().close());
    assertEquals(StatusCode.OK, database.close());
  }
}
