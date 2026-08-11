package io.riverdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverClientConnectionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x434c49454e544442L, 0x5445535430303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void exposesRemoteDatabaseSessionAndQueryOverRealStorage(@TempDir Path root) {
    DatabaseOpenResult engineResult = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, engineResult));
    RiverDatabase engine = engineResult.database();
    LoopbackRiverServer server = start(engine);
    RiverClientConnection client = connect(server);
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
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

    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, balance FROM accounts WHERE id >= 1 AND id < 4",
            queryResult));
    RiverQuery query = queryResult.query();
    RowResult row = new RowResult();
    assertRow(query, row, 1, 1, 100);
    assertRow(query, row, 2, 2, 200);
    assertRow(query, row, 3, 3, 300);
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(3, query.rowsReturned());
    assertEquals(StatusCode.OK, query.close(command));
    assertEquals(StatusCode.CONFLICT, client.close());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertTrue(client.completedRequests() >= 10);
    assertTrue(client.bytesSent() > 0);
    assertTrue(client.bytesReceived() > 0);
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, engine.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, engineResult));
    engine = engineResult.database();
    server = start(engine);
    client = connect(server);
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT balance FROM accounts WHERE id=2", command));
    assertTrue(command.rowAvailable());
    assertEquals(200, command.valueAt(0));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, engine.close());
  }

  @Test
  void serverDisconnectFencesTheClientAndReleasesItsTransaction(@TempDir Path root) {
    DatabaseOpenResult engineResult = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, engineResult));
    RiverDatabase engine = engineResult.database();
    LoopbackRiverServer server = start(engine);
    RiverClientConnection client = connect(server);
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, session.execute("CREATE TABLE ledger", command));
    assertEquals(StatusCode.OK, session.execute("BEGIN", command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO ledger VALUES (9, 900)", command));

    assertEquals(StatusCode.OK, server.close());
    assertEquals(
        StatusCode.IO_FAILURE,
        session.execute("INSERT INTO ledger VALUES (10, 1000)", command));
    assertEquals(StatusCode.IO_FAILURE, client.lastStatus());
    assertEquals(StatusCode.CLOSED, session.execute("COMMIT", command));
    assertEquals(StatusCode.CLOSED, client.close());

    server = start(engine);
    client = connect(server);
    assertEquals(StatusCode.OK, client.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT value FROM ledger WHERE key=9", command));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, client.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, engine.close());
  }

  private static LoopbackRiverServer start(RiverDatabase database) {
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
    assertEquals(StatusCode.OK, LoopbackRiverServer.start(database, 0, result));
    return result.server();
  }

  private static RiverClientConnection connect(LoopbackRiverServer server) {
    RiverClientOpenResult result = new RiverClientOpenResult();
    assertEquals(StatusCode.OK, RiverClientConnection.connectLoopback(server.port(), result));
    return result.connection();
  }

  private static void assertRow(
      RiverQuery query,
      RowResult row,
      long key,
      long first,
      long second) {
    assertEquals(StatusCode.OK, query.next(row));
    assertTrue(row.isAvailable());
    assertEquals(key, row.key());
    assertEquals(2, row.columnCount());
    assertEquals(first, row.valueAt(0));
    assertEquals(second, row.valueAt(1));
  }
}
