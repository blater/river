package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverRenameTableTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x52454e414d455442L, 0x4c45454e47494e45L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void renamesCatalogIdentityWhilePreservingRowsAndIndexes(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE accounts (id BIGINT PRIMARY KEY, code VARCHAR(7))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE TABLE customers (id BIGINT PRIMARY KEY, value BIGINT)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO accounts VALUES (1, 'alpha'), (2, 'beta')", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX accounts_code ON accounts(code)", result));

    SessionOpenResult secondResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(secondResult));
    RiverSession second = secondResult.session();
    assertEquals(StatusCode.OK, second.execute("BEGIN", result));
    assertEquals(
        StatusCode.RETRY,
        session.execute("ALTER TABLE accounts RENAME TO ledger", result));
    assertEquals(StatusCode.OK, second.execute("ROLLBACK", result));
    assertEquals(StatusCode.OK, second.close());

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("ALTER TABLE accounts RENAME TO ledger", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO ledger VALUES (3, 'gamma')", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO accounts VALUES (4, 'delta')", result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(2, countRows(session, "SELECT id FROM accounts"));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO ledger VALUES (3, 'gamma')", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("ALTER TABLE accounts RENAME TO customers", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("ALTER TABLE accounts RENAME TO ledger", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO accounts VALUES (3, 'gamma')", result));
    assertEquals(2, countRows(session, "SELECT id FROM ledger"));
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO ledger VALUES (3, 'alpha')", result));
    assertEquals(
        StatusCode.OK,
        session.execute("DROP INDEX accounts_code ON ledger", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO ledger VALUES (3, 'alpha')", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE TABLE accounts (id BIGINT PRIMARY KEY, value BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(3, countRows(session, "SELECT id FROM ledger"));
    assertEquals(0, countRows(session, "SELECT id FROM accounts"));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static int countRows(RiverSession session, String sql) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    int count = 0;
    StatusCode status = query.next(row);
    while (status.isOk() && row.isAvailable()) {
      count++;
      status = query.next(row);
    }
    assertEquals(StatusCode.OK, status);
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
    return count;
  }
}
