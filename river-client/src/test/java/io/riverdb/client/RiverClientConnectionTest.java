package io.riverdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
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
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE labels "
                + "(id BIGINT PRIMARY KEY, name VARCHAR(7) NOT NULL)",
            command));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO labels VALUES (1, 'beta'), (2, 'alpha')", command));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX labels_name ON labels(name)", command));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT name FROM labels WHERE name='alpha'", command));
    assertText(command, 0, "alpha");

    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery("SELECT name FROM labels ORDER BY name", queryResult));
    RiverQuery query = queryResult.query();
    assertTrue(query.columnIsVarchar(0));
    assertEquals(SqlTypeDescriptor.varchar(7), query.columnTypeDescriptor(0));
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertText(row, 0, "alpha");
    assertEquals(StatusCode.OK, query.next(row));
    assertText(row, 0, "beta");
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(command));
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, balance FROM accounts WHERE id >= 1 AND id < 4",
            queryResult));
    query = queryResult.query();
    assertEquals(2, query.columnCount());
    assertEquals("id", query.columnName(0));
    assertEquals("balance", query.columnName(1));
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

  private static void assertText(CommandResult result, int column, String expected) {
    char[] characters = new char[7];
    assertTrue(result.isVarchar(column));
    assertEquals(expected.length(), result.copyTextAt(column, characters, 0));
    assertEquals(expected, new String(characters, 0, expected.length()));
  }

  private static void assertText(RowResult result, int column, String expected) {
    char[] characters = new char[7];
    assertTrue(result.isVarchar(column));
    assertEquals(expected.length(), result.copyTextAt(column, characters, 0));
    assertEquals(expected, new String(characters, 0, expected.length()));
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
    assertEquals(0, server.activeConnections());
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

  @Test
  void twoRemoteSessionsRemainUsableTogether(@TempDir Path root) {
    DatabaseOpenResult engineResult = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, engineResult));
    RiverDatabase engine = engineResult.database();
    LoopbackRiverServer server = start(engine, 2);
    RiverClientConnection first = connect(server);
    RiverClientConnection second = connect(server);
    SessionOpenResult firstResult = new SessionOpenResult();
    SessionOpenResult secondResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, first.createSession(firstResult));
    assertEquals(StatusCode.OK, second.createSession(secondResult));
    RiverSession firstSession = firstResult.session();
    RiverSession secondSession = secondResult.session();
    CommandResult command = new CommandResult();

    assertEquals(
        StatusCode.OK,
        firstSession.execute("CREATE TABLE concurrent_sessions", command));
    assertEquals(
        StatusCode.OK,
        firstSession.execute(
            "INSERT INTO concurrent_sessions VALUES (1, 100)", command));
    assertEquals(
        StatusCode.OK,
        secondSession.execute(
            "INSERT INTO concurrent_sessions VALUES (2, 200)", command));
    assertEquals(
        StatusCode.OK,
        firstSession.execute(
            "SELECT value FROM concurrent_sessions WHERE key=2", command));
    assertEquals(200, command.valueAt(0));
    assertEquals(
        StatusCode.OK,
        secondSession.execute(
            "SELECT value FROM concurrent_sessions WHERE key=1", command));
    assertEquals(100, command.valueAt(0));
    assertEquals(2, server.activeConnections());

    assertEquals(StatusCode.OK, firstSession.close());
    assertEquals(StatusCode.OK, secondSession.close());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, second.close());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(0, server.activeConnections());
    assertEquals(StatusCode.OK, engine.close());
  }

  @Test
  void rejectsCorruptPeerHeadersBeforeReadingTheirPayload() throws Exception {
    assertEquals(StatusCode.CORRUPTION, connectToCorruptResponse(1));
    assertEquals(StatusCode.CORRUPTION, connectToCorruptResponse(2));
    assertEquals(StatusCode.CORRUPTION, connectToCorruptResponse(3));
    assertEquals(StatusCode.IO_FAILURE, connectToCorruptResponse(4));
    assertEquals(StatusCode.CORRUPTION, connectToCorruptResponse(5));
    assertEquals(StatusCode.CORRUPTION, connectToCorruptResponse(6));
  }

  private static StatusCode connectToCorruptResponse(int corruption) throws Exception {
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    try (ServerSocket server = new ServerSocket()) {
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      Thread responder = Thread.ofPlatform().start(() -> {
        try (Socket connection = server.accept()) {
          byte[] requestHeader = new byte[ProtocolFrameCodec.HEADER_BYTES];
          if (!readExact(connection.getInputStream(), requestHeader, requestHeader.length)) {
            throw new IOException("client request header was truncated");
          }
          ByteBuffer response = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
          ProtocolFrameCodec codec = new ProtocolFrameCodec();
          if (corruption == 1) {
            codec.encodeRequest(response, ProtocolMessageType.HELLO, 1);
          } else {
            codec.encodeHelloResponse(response, 1, StatusCode.OK, 0, 0);
            if (corruption == 2) {
              response.putInt(4, ProtocolFrameCodec.VERSION + 1);
            } else if (corruption == 3) {
              response.putInt(24, ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
            } else if (corruption == 5) {
              response.putInt(8, ProtocolMessageType.FETCH.wireCode());
            } else if (corruption == 6) {
              response.putLong(16, 2);
            }
          }
          int bytes = corruption == 4
              ? ProtocolFrameCodec.HEADER_BYTES - 1 : ProtocolFrameCodec.HEADER_BYTES;
          connection.getOutputStream().write(response.array(), 0, bytes);
          connection.getOutputStream().flush();
        } catch (Throwable failure) {
          serverFailure.set(failure);
        }
      });
      RiverClientOpenResult result = new RiverClientOpenResult();
      StatusCode status = RiverClientConnection.connectLoopback(server.getLocalPort(), result);
      responder.join(2_000);
      assertFalse(responder.isAlive());
      if (serverFailure.get() != null) {
        throw new AssertionError(serverFailure.get());
      }
      return status;
    }
  }

  private static boolean readExact(InputStream input, byte[] target, int length)
      throws IOException {
    int read = 0;
    while (read < length) {
      int count = input.read(target, read, length - read);
      if (count < 0) {
        return false;
      }
      read += count;
    }
    return true;
  }

  private static LoopbackRiverServer start(RiverDatabase database) {
    return start(database, LoopbackRiverServer.DEFAULT_MAXIMUM_CONNECTIONS);
  }

  private static LoopbackRiverServer start(
      RiverDatabase database,
      int maximumConnections) {
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.start(database, 0, maximumConnections, result));
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
