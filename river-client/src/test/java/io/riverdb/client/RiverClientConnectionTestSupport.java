package io.riverdb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.LoopbackServerOpenResult;
import io.riverdb.testsupport.TestTlsContexts;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;
import java.util.concurrent.atomic.AtomicReference;

final class RiverClientConnectionTestSupport {
  static final byte[] TOKEN =
      "river-client-connection-test-token".getBytes(StandardCharsets.UTF_8);
  static DatabaseResourcePlanRequest databaseRequest(int owners) {
    return new DatabaseResourcePlanRequest()
        .memory(256_000_000L, 0, 0, 0, 64_000_000L)
        .lockProviderBytes(8_000_000L)
        .versionWorkspaceBytes(8_000_000L)
        .indexedPageCache(32_000_000L, 8_000_000L)
        .capacity(owners, Integer.MAX_VALUE, 800, 64_000_000L)
        .maximumDelivery(Integer.MAX_VALUE, 800, 64_000_000L);
  }

  static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x434c49454e544442L, 0x5445535430303031L);
  static final WalGeneration GENERATION = WalGeneration.of(1);

  static void appendArgument(TransactionProgram program, int slot) {
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.argument(slot, SqlTypeDescriptor.BIGINT));
    assertEquals(StatusCode.OK, program.endExpression());
  }

  static final class DenyingLease implements RetainedMemoryLease {
    @Override
    public StatusCode resize(long bytes) {
      return bytes == 0 ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
    }

    @Override
    public StatusCode awaitResize(long bytes) { return resize(bytes); }

    @Override
    public long retainedBytes() { return 0; }
  }

  static void assertText(CommandResult result, int column, String expected) {
    char[] characters = new char[7];
    assertTrue(result.isVarchar(column));
    assertEquals(expected.length(), result.copyTextAt(column, characters, 0));
    assertEquals(expected, new String(characters, 0, expected.length()));
  }

  static void assertText(RowResult result, int column, String expected) {
    char[] characters = new char[7];
    assertTrue(result.isVarchar(column));
    assertEquals(expected.length(), result.copyTextAt(column, characters, 0));
    assertEquals(expected, new String(characters, 0, expected.length()));
  }

  static StatusCode connectToCorruptResponse(int corruption) throws Exception {
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    try (ServerSocket server = tlsServer()) {
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      Thread responder = Thread.ofPlatform().start(() -> {
        try (SSLSocket connection = (SSLSocket) server.accept()) {
          connection.startHandshake();
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
      StatusCode status = RiverClientConnection.connectAuthenticatedLoopback(
          server.getLocalPort(), trustedClientContext(),
          TOKEN, TOKEN.length, result);
      responder.join(2_000);
      assertFalse(responder.isAlive());
      if (serverFailure.get() != null) {
        throw new AssertionError(serverFailure.get());
      }
      return status;
    }
  }

  static ServerSocket tlsServer() throws IOException {
    return serverContext().getServerSocketFactory().createServerSocket();
  }

  static void expectAuthentication(
      InputStream input,
      OutputStream output,
      ByteBuffer response,
      ProtocolFrameCodec codec) throws IOException {
    expectRequest(input, ProtocolMessageType.AUTHENTICATE, 2);
    assertEncoded(codec.encodeStatusResponse(
        response, ProtocolMessageType.AUTHENTICATE, 2, StatusCode.OK, false));
    writeResponse(output, response);
  }

  static boolean readExact(InputStream input, byte[] target, int length)
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

  static void expectRequest(
      InputStream input, ProtocolMessageType type, long requestId)
      throws IOException {
    byte[] header = new byte[ProtocolFrameCodec.HEADER_BYTES];
    if (!readExact(input, header, header.length)) {
      throw new IOException("client request header was truncated");
    }
    ByteBuffer bytes = ByteBuffer.wrap(header);
    if (bytes.getInt(8) != type.wireCode()
        || bytes.getLong(16) != requestId) {
      throw new IOException("client request header did not match the script");
    }
    int payloadBytes = bytes.getInt(24);
    if (payloadBytes < 0
        || payloadBytes > ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES) {
      throw new IOException("client request payload was invalid");
    }
    if (payloadBytes > 0
        && !readExact(input, new byte[payloadBytes], payloadBytes)) {
      throw new IOException("client request payload was truncated");
    }
  }

  static void writeResponse(OutputStream output, ByteBuffer response)
      throws IOException {
    output.write(response.array(), response.position(), response.remaining());
    output.flush();
  }

  static void assertEncoded(StatusCode status) throws IOException {
    if (!status.isOk()) throw new IOException("script response encoding failed");
  }

  static final class OneColumnQuery implements RiverQuery {
    private final int descriptor;

    OneColumnQuery(int typeDescriptor) {
      descriptor = typeDescriptor;
    }

    @Override
    public StatusCode next(RowResult result) {
      return StatusCode.CONFLICT;
    }

    @Override
    public StatusCode close(CommandResult result) {
      return StatusCode.OK;
    }

    @Override
    public boolean isActive() {
      return true;
    }

    @Override
    public int columnCount() {
      return 1;
    }

    @Override
    public CharSequence columnName(int index) {
      return index == 0 ? "captured" : null;
    }

    @Override
    public int columnTypeDescriptor(int index) {
      return index == 0 ? descriptor : 0;
    }

    @Override
    public boolean columnIsNullable(int index) {
      return false;
    }

    @Override
    public long rowsReturned() {
      return 0;
    }
  }

  static LoopbackRiverServer start(RiverDatabase database) {
    return start(database, LoopbackRiverServer.DEFAULT_MAXIMUM_CONNECTIONS);
  }

  static LoopbackRiverServer start(
      RiverDatabase database,
      int maximumConnections) {
    TokenAuthenticatorOpenResult authenticator = new TokenAuthenticatorOpenResult();
    assertEquals(StatusCode.OK, TokenAuthenticator.create(TOKEN, TOKEN.length, authenticator));
    io.riverdb.server.CredentialValidityFenceOpenResult fence =
        new io.riverdb.server.CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    assertEquals(
        StatusCode.OK,
        io.riverdb.server.CredentialValidityFence.create(now - 1_000L, now + 60_000L, fence));
    LoopbackServerOpenResult result = new LoopbackServerOpenResult();
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
            result));
    return result.server();
  }

  static RiverClientConnection connect(LoopbackRiverServer server) {
    RiverClientOpenResult result = new RiverClientOpenResult();
    assertEquals(
        StatusCode.OK,
        RiverClientConnection.connectAuthenticatedLoopback(
            server.port(), trustedClientContext(), TOKEN, TOKEN.length, result));
    return result.connection();
  }

  static void assertRow(
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

  static javax.net.ssl.SSLContext serverContext() {
    try {
      return TestTlsContexts.server();
    } catch (java.security.GeneralSecurityException | java.io.IOException failure) {
      throw new AssertionError("TLS test context", failure);
    }
  }

  static javax.net.ssl.SSLContext trustedClientContext() {
    try {
      return TestTlsContexts.trustedClient();
    } catch (java.security.GeneralSecurityException | java.io.IOException failure) {
      throw new AssertionError("TLS test context", failure);
    }
  }
}
