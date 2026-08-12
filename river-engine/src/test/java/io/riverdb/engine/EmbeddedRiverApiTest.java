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

final class EmbeddedRiverApiTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x454e47494e454150L, 0x4954455354444231L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void executesAndStreamsThroughEngineApiThenReopens(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
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
                + "(1, 100, 7), (2, 200, 7), (3, 300, 8)",
            command));
    assertEquals(3, command.affectedRows());
    assertEquals(StatusCode.OK, session.execute("BEGIN SERIALIZABLE", command));
    assertEquals(true, command.transactionActive());
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE accounts SET balance=250 WHERE id=2", command));
    assertEquals(StatusCode.OK, session.execute("COMMIT", command));
    assertEquals(false, command.transactionActive());

    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, balance FROM accounts WHERE id >= 1 AND id < 4",
            queryResult));
    RiverQuery query = queryResult.query();
    RowResult row = new RowResult();
    assertRow(query, row, 1, 1, 100);
    assertRow(query, row, 2, 2, 250);
    assertRow(query, row, 3, 3, 300);
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(3, query.rowsReturned());
    assertEquals(StatusCode.OK, query.close(command));
    assertEquals(false, query.isActive());

    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, NULL FROM accounts WHERE id >= 1 AND id < 2",
            queryResult));
    query = queryResult.query();
    assertEquals("null", query.columnName(1).toString());
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(0, row.valueAt(1));
    assertEquals(false, row.isNull(0));
    assertEquals(true, row.isNull(1));
    assertEquals(StatusCode.OK, query.close(command));

    assertEquals(
        StatusCode.OK,
        session.execute("SELECT NULL FROM accounts WHERE id=2", command));
    assertEquals(true, command.rowAvailable());
    assertEquals(0, command.valueAt(0));
    assertEquals(true, command.isNull(0));
    assertEquals(StatusCode.CONFLICT, database.close());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT balance FROM accounts WHERE id=2", command));
    assertEquals(true, command.rowAvailable());
    assertEquals(250, command.valueAt(0));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void closingSessionAbortsTransactionAndClosesActiveQuery(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE ledger", command));
    assertEquals(StatusCode.OK, session.execute("BEGIN", command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO ledger VALUES (9, 900)", command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.CLOSED, session.execute("COMMIT", command));

    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM ledger WHERE key=9", command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO ledger VALUES (1, 100), (2, 200)", command));
    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery("SELECT key, value FROM ledger", queryResult));
    RiverQuery query = queryResult.query();
    assertEquals(StatusCode.OK, session.close());
    assertEquals(false, query.isActive());
    assertEquals(StatusCode.CLOSED, query.next(new RowResult()));
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertRow(
      RiverQuery query,
      RowResult row,
      long key,
      long first,
      long second) {
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(true, row.isAvailable());
    assertEquals(key, row.key());
    assertEquals(2, row.columnCount());
    assertEquals(first, row.valueAt(0));
    assertEquals(second, row.valueAt(1));
  }
}
