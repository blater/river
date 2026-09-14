package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.protocol.auth.TokenProof;
import io.riverdb.protocol.auth.TlsChannelBinding;
import io.riverdb.testsupport.TestTlsContexts;
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LoopbackRiverServerTest {
  private static final byte[] TOKEN =
      "loopback-server-test-token".getBytes(StandardCharsets.UTF_8);
  private static DatabaseResourcePlanRequest databaseRequest(int owners) {
    return new DatabaseResourcePlanRequest()
        .memory(256_000_000L, 0, 0, 0, 64_000_000L)
        .lockProviderBytes(8_000_000L)
        .versionWorkspaceBytes(8_000_000L)
        .indexedPageCache(32_000_000L, 8_000_000L)
        .capacity(owners, Integer.MAX_VALUE, 800, 64_000_000L)
        .maximumDelivery(Integer.MAX_VALUE, 800, 64_000_000L);
  }

  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4e4554574f524b44L, 0x4154414241534531L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void executesDurableSqlAndStreamsRowsOverLoopbackTcp(@TempDir Path root)
      throws IOException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database, root);

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

      long beforeQuery = client.completedRequests();
      ProtocolResponse begun = client.send(
          ProtocolMessageType.BEGIN_QUERY,
          "SELECT id, balance FROM accounts WHERE id >= 1 AND id < 4");
      assertStatus(StatusCode.OK, begun);
      assertTrue(begun.queryActive());
      assertRow(begun, 1, 1, 100, true);
      assertRow(client.send(ProtocolMessageType.FETCH), 2, 2, 200, true);
      ProtocolResponse finalRow = client.send(ProtocolMessageType.FETCH);
      assertRow(finalRow, 3, 3, 300, false);
      assertTrue(finalRow.endOfStream());
      assertEquals(3, finalRow.rowsReturned());
      assertEquals(beforeQuery + 3, client.completedRequests());
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.CLOSE_SESSION));
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            io.riverdb.engine.EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    server = start(database, root);
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
  void completesEmptySingletonAndEarlyClosedQueriesWithoutRedundantRequests(
      @TempDir Path root) throws IOException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database, root);
    try (TestClient client = new TestClient(server.port())) {
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.HELLO));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.OPEN_SESSION));
      assertStatus(StatusCode.OK, client.send(
          ProtocolMessageType.EXECUTE,
          "CREATE TABLE stream_rows (id BIGINT PRIMARY KEY, value BIGINT)"));

      long before = client.completedRequests();
      ProtocolResponse empty = client.send(
          ProtocolMessageType.BEGIN_QUERY,
          "SELECT id, value FROM stream_rows WHERE id=99");
      assertStatus(StatusCode.OK, empty);
      assertTrue(empty.endOfStream());
      assertFalse(empty.queryActive());
      assertFalse(empty.rowAvailable());
      assertEquals(before + 1, client.completedRequests());

      assertStatus(StatusCode.OK, client.send(
          ProtocolMessageType.EXECUTE,
          "INSERT INTO stream_rows VALUES (1, 10)"));
      before = client.completedRequests();
      ProtocolResponse singleton = client.send(
          ProtocolMessageType.BEGIN_QUERY,
          "SELECT id, value FROM stream_rows WHERE id=1");
      assertRow(singleton, 1, 1, 10, false);
      assertTrue(singleton.endOfStream());
      assertEquals(before + 1, client.completedRequests());

      assertStatus(StatusCode.OK, client.send(
          ProtocolMessageType.EXECUTE,
          "INSERT INTO stream_rows VALUES (2, 20)"));
      before = client.completedRequests();
      ProtocolResponse first = client.send(
          ProtocolMessageType.BEGIN_QUERY,
          "SELECT id, value FROM stream_rows ORDER BY id");
      assertRow(first, 1, 1, 10, true);
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.CLOSE_QUERY));
      assertEquals(before + 2, client.completedRequests());
      assertStatus(StatusCode.OK, client.send(
          ProtocolMessageType.EXECUTE,
          "INSERT INTO stream_rows VALUES (3, 30)"));

      assertStatus(StatusCode.INVALID_EXTERNAL_INPUT, client.send(
          ProtocolMessageType.BEGIN_QUERY,
          "SELECT absent FROM stream_rows"));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.CLOSE_SESSION));
    }
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void rejectsIllegalStateAndBadUtf8WithoutDispatch(@TempDir Path root)
      throws IOException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database, root);
    try (TestClient client = new TestClient(server.port())) {
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.HELLO));
      assertStatus(StatusCode.CONFLICT, client.send(ProtocolMessageType.FETCH));
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
        EmbeddedRiver.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database, root);
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
    try (TestClient client = new TestClient(server.port())) {
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.HELLO));
      byte[] header = new byte[ProtocolFrameCodec.HEADER_BYTES];
      ByteBuffer bytes = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
      ProtocolFrameCodec codec = new ProtocolFrameCodec();
      assertEquals(
          StatusCode.OK,
          codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 99));
      bytes.putInt(24, ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES + 1);
      client.writeRaw(header, header.length);
      assertEquals(-1, client.read());
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
        EmbeddedRiver.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database, root);
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
        EmbeddedRiver.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database, root, 2);

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
        EmbeddedRiver.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database, root, 1);

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

  @Test
  void partialContinuationIsScrubbedAndShedBeforeSlotReuse(@TempDir Path root)
      throws IOException {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK,
        EmbeddedRiver.create(databaseRequest(4), root, DATABASE, GENERATION, 4, opened));
    RiverDatabase database = opened.database();
    LoopbackRiverServer server = start(database, root, 1);
    long warmBytes = 2L * ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
    assertEquals(warmBytes, server.retainedProtocolBufferBytes());
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ByteBuffer continued = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_REQUEST_BYTES);
    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        continued,
        ProtocolMessageType.EXECUTE,
        1,
        " ".repeat(20_000) + "SELECT 1",
        null, 0, 0, 0));
    int firstFrameBytes = ProtocolFrameCodec.HEADER_BYTES + continued.getInt(24);
    try (TestClient partial = new TestClient(server.port())) {
      awaitConnections(server, 1);
      partial.send(ProtocolMessageType.HELLO);
      partial.writeRaw(continued.array(), firstFrameBytes);
    }
    awaitConnections(server, 0);
    assertEquals(warmBytes, server.retainedProtocolBufferBytes());

    try (TestClient client = new TestClient(server.port())) {
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.HELLO));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.OPEN_SESSION));
      assertStatus(StatusCode.OK,
          client.send(ProtocolMessageType.EXECUTE, "SELECT 1"));
      assertStatus(StatusCode.OK, client.send(ProtocolMessageType.CLOSE_SESSION));
    }
    awaitConnections(server, 0);
    assertEquals(warmBytes, server.retainedProtocolBufferBytes());
    assertEquals(StatusCode.OK, server.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static LoopbackRiverServer start(RiverDatabase database, Path root)
      throws IOException {
    return start(database, root, LoopbackRiverServer.DEFAULT_MAXIMUM_CONNECTIONS);
  }

  private static LoopbackRiverServer start(
      RiverDatabase database,
      Path root,
      int maximumConnections) {
    TokenAuthenticatorOpenResult authenticator = new TokenAuthenticatorOpenResult();
    assertEquals(
        StatusCode.OK,
        TokenAuthenticator.create(TOKEN, TOKEN.length, authenticator));
    CredentialValidityFenceOpenResult fence = new CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    assertEquals(
        StatusCode.OK,
        CredentialValidityFence.create(now - 1_000L, now + 60_000L, fence));
    LoopbackServerOpenResult started = new LoopbackServerOpenResult();
    assertEquals(
        StatusCode.OK,
        LoopbackRiverServer.startAuthenticated(
            database,
            InetAddress.getLoopbackAddress(),
            0,
            serverContext(),
            authenticator.authenticator(),
            fence.fence(),
            LoopbackServerLimits.defaults(maximumConnections),
            started));
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
    try (TestClient client = new TestClient(server.port())) {
      client.send(ProtocolMessageType.HELLO);
      client.writeRaw(header, bytes);
      client.shutdownOutput();
      assertEquals(-1, client.read());
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
      long second,
      boolean queryActive) {
    assertStatus(StatusCode.OK, response);
    assertTrue(response.rowAvailable());
    assertEquals(queryActive, response.queryActive());
    assertEquals(key, response.key());
    assertEquals(2, response.columnCount());
    assertEquals(first, response.valueAt(0));
    assertEquals(second, response.valueAt(1));
  }

  private static Socket connect(int port) throws IOException {
    SSLSocket socket = (SSLSocket) trustedClientContext()
        .getSocketFactory().createSocket();
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
      if (socket instanceof SSLSocket secure) {
        secure.setEnabledProtocols(new String[] {"TLSv1.3"});
        secure.startHandshake();
      }
      input = socket.getInputStream();
      output = socket.getOutputStream();
    }

    private ProtocolResponse send(ProtocolMessageType type) throws IOException {
      assertEquals(StatusCode.OK, codec.encodeRequest(request, type, requestId++));
      ProtocolResponse exchanged = exchange();
      if (type == ProtocolMessageType.HELLO && exchanged.status() == StatusCode.OK) {
        long challengeHigh = exchanged.challengeHigh();
        long challengeLow = exchanged.challengeLow();
        byte[] binding = new byte[TlsChannelBinding.BINDING_BYTES];
        byte[] proof = new byte[TokenProof.PROOF_BYTES];
        try {
          assertTrue(socket instanceof SSLSocket);
          assertEquals(
              StatusCode.OK,
              TlsChannelBinding.export(
                  ((SSLSocket) socket).getSession(), binding));
          assertEquals(
              StatusCode.OK,
              TokenProof.compute(
                  TOKEN, TOKEN.length, challengeHigh, challengeLow, binding, proof));
          assertEquals(
              StatusCode.OK,
              codec.encodeBinaryRequest(
                  request, ProtocolMessageType.AUTHENTICATE, requestId++, proof, proof.length));
          exchanged = exchange();
          assertEquals(StatusCode.OK, exchanged.status());
        } finally {
          Arrays.fill(binding, (byte) 0);
          Arrays.fill(proof, (byte) 0);
        }
      }
      return exchanged;
    }

    private ProtocolResponse send(ProtocolMessageType type, String sql) throws IOException {
      assertEquals(StatusCode.OK,
          codec.encodeSqlRequest(request, type, requestId++, sql, null, 0, 0, 0));
      return exchange();
    }

    private ProtocolResponse sendBadUtf8() throws IOException {
      assertEquals(
          StatusCode.OK,
          codec.encodeSqlRequest(
              request, ProtocolMessageType.EXECUTE, requestId++, "A", null, 0, 0, 0));
      request.put(ProtocolFrameCodec.HEADER_BYTES + 32, (byte) 0xc0);
      return exchange();
    }

    private long completedRequests() {
      return requestId - 1;
    }

    private void writeRaw(byte[] bytes, int length) throws IOException {
      output.write(bytes, 0, length);
      output.flush();
    }

    private int read() throws IOException {
      return input.read();
    }

    private void shutdownOutput() throws IOException {
      socket.shutdownOutput();
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

  private static javax.net.ssl.SSLContext serverContext() {
    try {
      return TestTlsContexts.server();
    } catch (java.security.GeneralSecurityException | java.io.IOException failure) {
      throw new AssertionError("TLS test context", failure);
    }
  }

  private static javax.net.ssl.SSLContext trustedClientContext() {
    try {
      return TestTlsContexts.trustedClient();
    } catch (java.security.GeneralSecurityException | java.io.IOException failure) {
      throw new AssertionError("TLS test context", failure);
    }
  }
}
