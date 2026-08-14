package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.ProtocolFrame;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolFrameHeader;
import io.riverdb.protocol.ProtocolMessageType;
import io.riverdb.protocol.ProtocolResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LoopbackRiverServerTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4e4554574f524b44L, 0x4154414241534531L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void executesDurableSqlAndStreamsRowsOverLoopbackTcp(@TempDir Path root)
      throws IOException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);

    try (TestClient client = new TestClient(server.port())) {
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.HELLO));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.OPEN_SESSION));
      assertStatus(
          StatusCode.OK,
          client.send(
              ProtocolMessageType.EXECUTE,
              "CREATE TABLE accounts "
                  + "(id BIGINT PRIMARY KEY, balance BIGINT, region BIGINT)"));
      ProtocolResponse inserted = client.send(
          ProtocolMessageType.EXECUTE,
          "INSERT INTO accounts VALUES (1, 100, 7), (2, 200, 7), (3, 300, 8)");
      assertStatus(StatusCode.OK, inserted);
      assertEquals(3, inserted.affectedRows());

      ProtocolResponse begun = client.send(
          ProtocolMessageType.BEGIN_QUERY,
          "SELECT id, balance FROM accounts WHERE id >= 1 AND id < 4");
      assertStatus(StatusCode.OK, begun);
      assertTrue(begun.queryActive());
      assertRow(client.send(ProtocolMessageType.FETCH), 1, 1, 100);
      assertRow(client.send(ProtocolMessageType.FETCH), 2, 2, 200);
      assertRow(client.send(ProtocolMessageType.FETCH), 3, 3, 300);
      ProtocolResponse end = client.send(ProtocolMessageType.FETCH);
      assertStatus(StatusCode.OK, end);
      assertFalse(end.rowAvailable());
      assertEquals(3, end.rowsReturned());
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.CLOSE_QUERY));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.CLOSE_SESSION));
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    server = start(database);
    try (TestClient client = new TestClient(server.port())) {
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.HELLO));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.OPEN_SESSION));
      ProtocolResponse selected = client.send(
          ProtocolMessageType.EXECUTE,
          "SELECT balance FROM accounts WHERE id=2");
      assertStatus(StatusCode.OK, selected);
      assertTrue(selected.rowAvailable());
      assertEquals(200, selected.valueAt(0));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.CLOSE_SESSION));
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, server.lastStatus());
    assertTrue(server.completedRequests() >= 4);
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rejectsIllegalStateAndBadUtf8WithoutDispatch(@TempDir Path root)
      throws IOException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    try (TestClient client = new TestClient(server.port())) {
      assertStatus(StatusCode.CONFLICT, client.send(ProtocolMessageType.FETCH));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.HELLO));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.OPEN_SESSION));
      assertStatus(StatusCode.INVALID_EXTERNAL_INPUT, client.sendBadUtf8());
      assertStatus(StatusCode.CONFLICT, client.send(ProtocolMessageType.FETCH));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.CLOSE_SESSION));
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void disconnectAbortsOwnedTransactionAndOversizeFrameIsBounded(@TempDir Path root)
      throws IOException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    try (TestClient client = new TestClient(server.port())) {
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.HELLO));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.OPEN_SESSION));
      assertStatus(
          StatusCode.OK,
          client.send(ProtocolMessageType.EXECUTE, "CREATE TABLE ledger"));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.EXECUTE, "BEGIN"));
      assertStatus(
          StatusCode.OK,
          client.send(
              ProtocolMessageType.EXECUTE,
              "INSERT INTO ledger VALUES (9, 900)"));
    }
    try (TestClient client = new TestClient(server.port())) {
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.HELLO));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.OPEN_SESSION));
      assertStatus(
          StatusCode.CONFLICT,
          client.send(
              ProtocolMessageType.EXECUTE,
              "SELECT value FROM ledger WHERE key=9"));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.CLOSE_SESSION));
    }
    try (Socket socket = connect(server.port())) {
      byte[] header = new byte[ProtocolFrameCodec.HEADER_BYTES];
      ByteBuffer bytes = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
      ProtocolFrameCodec codec = new ProtocolFrameCodec();
      assertEquals(
          StatusCode.OK,
          codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 99));
      bytes.putInt(24, ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES + 1);
      socket.getOutputStream().write(header);
      socket.getOutputStream().flush();
      socket.setSoTimeout(2_000);
      assertEquals(-1, socket.getInputStream().read());
    }
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, server.lastStatus());
    assertEquals(1, server.rejectedFrames());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rejectsTruncatedVersionAndWrongDirectionHeadersBeforePayloadRead(
      @TempDir Path root) throws IOException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database);
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    byte[] header = new byte[ProtocolFrameCodec.HEADER_BYTES];
    ByteBuffer bytes = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    sendRejectedHeader(server, header, ProtocolFrameCodec.HEADER_BYTES - 1, 1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, server.lastStatus());

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 2));
    bytes.putInt(4, ProtocolFrameCodec.VERSION + 1);
    sendRejectedHeader(server, header, header.length, 2);
    assertEquals(StatusCode.CONFLICT, server.lastStatus());

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 3));
    bytes.putInt(12, 1);
    bytes.putInt(24, 64);
    sendRejectedHeader(server, header, header.length, 3);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, server.lastStatus());

    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void idleConnectionDoesNotBlockUsefulSql(@TempDir Path root) throws IOException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database, 2);

    try (Socket idle = connect(server.port())) {
      assertTrue(idle.isConnected());
      awaitConnections(server, 1);
      try (TestClient client = new TestClient(server.port())) {
        assertStatus(StatusCode.OK, client.send(ProtocolMessageType.HELLO));
        assertStatus(StatusCode.OK, client.send(ProtocolMessageType.OPEN_SESSION));
        assertStatus(
            StatusCode.OK,
            client.send(ProtocolMessageType.EXECUTE, "CREATE TABLE live"));
        assertStatus(StatusCode.OK, client.send(ProtocolMessageType.CLOSE_SESSION));
      }
      awaitConnections(server, 1);
      assertEquals(2, server.acceptedConnections());
      assertEquals(0, server.rejectedConnections());
    }
    awaitConnections(server, 0);
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void connectionCapRejectsExcessWithoutDisturbingIncumbent(@TempDir Path root)
      throws IOException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database, 1);

    try (Socket incumbent = connect(server.port());
        Socket excess = connect(server.port())) {
      incumbent.setSoTimeout(2_000);
      excess.setSoTimeout(2_000);
      awaitConnections(server, 1);
      awaitRejectedConnections(server, 1);
      assertEquals(1, server.maximumConnections());
      assertEquals(1, server.activeConnections());
      assertEquals(1, server.acceptedConnections());
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, server.lastStatus());

      boolean disconnected;
      try {
        disconnected = excess.getInputStream().read() < 0;
      } catch (IOException closed) {
        disconnected = true;
      }
      assertTrue(disconnected);

      TestClient incumbentClient = new TestClient(incumbent);
      assertStatus(StatusCode.OK, incumbentClient.send(ProtocolMessageType.HELLO));
      assertStatus(StatusCode.OK, incumbentClient.send(ProtocolMessageType.OPEN_SESSION));
      assertStatus(StatusCode.OK, incumbentClient.send(ProtocolMessageType.CLOSE_SESSION));
    }
    awaitConnections(server, 0);
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static LoopbackRiverServer start(RiverDatabase database) {
    return start(database, LoopbackRiverServer.DEFAULT_MAXIMUM_CONNECTIONS);
  }

  private static LoopbackRiverServer start(
      RiverDatabase database,
      int maximumConnections) {
    LoopbackServerOpenResult started = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.start(database, 0, maximumConnections, started));
    assertTrue(InetAddress.getLoopbackAddress().isLoopbackAddress());
    return started.server();
  }

  private static void awaitConnections(
      LoopbackRiverServer server,
      int expected) {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (server.activeConnections() != expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, server.activeConnections());
  }

  private static void awaitRejectedConnections(
      LoopbackRiverServer server,
      long expected) {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (server.rejectedConnections() != expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, server.rejectedConnections());
  }

  private static void sendRejectedHeader(
      LoopbackRiverServer server,
      byte[] header,
      int bytes,
      long expectedRejectedFrames) throws IOException {
    try (Socket socket = connect(server.port())) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write(header, 0, bytes);
      socket.getOutputStream().flush();
      socket.shutdownOutput();
      assertEquals(-1, socket.getInputStream().read());
    }
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (server.rejectedFrames() != expectedRejectedFrames
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expectedRejectedFrames, server.rejectedFrames());
  }

  private static void assertStatus(StatusCode expected, ProtocolResponse response) {
    assertEquals(expected, response.status());
  }

  private static void assertRow(
      ProtocolResponse response,
      long key,
      long first,
      long second) {
    assertStatus(StatusCode.OK, response);
    assertTrue(response.rowAvailable());
    assertTrue(response.queryActive());
    assertEquals(key, response.key());
    assertEquals(2, response.columnCount());
    assertEquals(first, response.valueAt(0));
    assertEquals(second, response.valueAt(1));
  }

  private static Socket connect(int port) throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    return socket;
  }

  private static final class TestClient implements AutoCloseable {
    private final ProtocolFrameCodec codec = new ProtocolFrameCodec();
    private final ProtocolFrame frame = new ProtocolFrame();
    private final ProtocolFrameHeader responseHeader = new ProtocolFrameHeader();
    private final ProtocolResponse response = new ProtocolResponse();
    private final ByteBuffer request =
        ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    private final byte[] responseBytes = new byte[ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES];
    private final ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private long requestId = 1;

    private TestClient(int port) throws IOException {
      this(connect(port));
    }

    private TestClient(Socket connection) throws IOException {
      socket = connection;
      input = socket.getInputStream();
      output = socket.getOutputStream();
    }

    private ProtocolResponse send(ProtocolMessageType type) throws IOException {
      assertEquals(StatusCode.OK, codec.encodeRequest(request, type, requestId++));
      return exchange();
    }

    private ProtocolResponse send(ProtocolMessageType type, String sql) throws IOException {
      assertEquals(StatusCode.OK, codec.encodeTextRequest(request, type, requestId++, sql));
      return exchange();
    }

    private ProtocolResponse sendBadUtf8() throws IOException {
      assertEquals(
          StatusCode.OK,
          codec.encodeTextRequest(request, ProtocolMessageType.EXECUTE, requestId++, "A"));
      request.put(ProtocolFrameCodec.HEADER_BYTES, (byte) 0xc0);
      return exchange();
    }

    private ProtocolResponse exchange() throws IOException {
      output.write(request.array(), 0, request.remaining());
      output.flush();
      readExact(input, responseBytes, 0, ProtocolFrameCodec.HEADER_BYTES);
      responseBuffer.position(0);
      responseBuffer.limit(ProtocolFrameCodec.HEADER_BYTES);
      assertEquals(
          StatusCode.OK,
          codec.inspectResponseHeader(responseBuffer, responseHeader));
      int payload = responseHeader.payloadBytes();
      readExact(input, responseBytes, ProtocolFrameCodec.HEADER_BYTES, payload);
      responseBuffer.position(0);
      responseBuffer.limit(ProtocolFrameCodec.HEADER_BYTES + payload);
      assertEquals(StatusCode.OK, codec.decodeResponse(responseBuffer, frame, response));
      return response;
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }

    private static void readExact(
        InputStream input,
        byte[] target,
        int offset,
        int length) throws IOException {
      int read = 0;
      while (read < length) {
        int count = input.read(target, offset + read, length - read);
        if (count < 0) {
          throw new IOException("unexpected end of stream");
        }
        read += count;
      }
    }
  }
}
