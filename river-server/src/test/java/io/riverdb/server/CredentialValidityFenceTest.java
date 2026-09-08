package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.MutableCancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.protocol.ProtocolFrame;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import io.riverdb.protocol.ProtocolResponse;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.protocol.auth.TokenProof;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class CredentialValidityFenceTest {
  @Test
  void validatesExclusiveWindow() {
    CredentialValidityFenceOpenResult result = new CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        CredentialValidityFence.create(now, now, result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        CredentialValidityFence.create(now + 1, now, result));
    assertEquals(StatusCode.OK,
        CredentialValidityFence.create(now - 1_000L, now + 60_000L, result));
    assertNotNull(result.fence());
  }

  @Test
  void closesPermanentlyAfterExplicitClose() {
    CredentialValidityFenceOpenResult result = new CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    assertEquals(StatusCode.OK,
        CredentialValidityFence.create(now - 1_000L, now + 60_000L, result));
    CredentialValidityFence fence = result.fence();
    assertFalse(fence.isClosed());
    assertEquals(StatusCode.OK, fence.checkNow());
    assertEquals(StatusCode.OK, fence.close());
    assertTrue(fence.isClosed());
    assertEquals(StatusCode.ACCESS_DENIED, fence.checkNow());
  }

  @Test
  void openSessionAfterFenceClosureDoesNotCreateEngineState() {
    byte[] token = new byte[TokenProof.MINIMUM_TOKEN_BYTES];
    Arrays.fill(token, (byte) 7);
    TokenAuthenticatorOpenResult authenticatorResult = new TokenAuthenticatorOpenResult();
    assertEquals(StatusCode.OK,
        TokenAuthenticator.create(token, token.length, authenticatorResult));
    CredentialValidityFenceOpenResult fenceResult = new CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    assertEquals(StatusCode.OK,
        CredentialValidityFence.create(now - 2_000L, now + 60_000L, fenceResult));
    CountingDatabase database = new CountingDatabase();
    byte[] binding = new byte[] {1, 2, 3};
    SessionEndpoint endpoint = new SessionEndpoint(
        database, authenticatorResult.authenticator(), fenceResult.fence(),
        11, 12, binding, null, null, new MutableCancellationToken());
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ProtocolFrame frame = new ProtocolFrame();
    ProtocolResponse decoded = new ProtocolResponse();
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    ByteBuffer response = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    try {
      assertEquals(StatusCode.OK,
          codec.encodeRequest(request, ProtocolMessageType.HELLO, 1));
      assertEquals(StatusCode.OK, endpoint.process(request, response));
      assertEquals(StatusCode.OK, codec.decodeResponse(response, frame, decoded));
      assertEquals(StatusCode.OK, decoded.status());

      byte[] proof = new byte[TokenProof.PROOF_BYTES];
      assertEquals(StatusCode.OK,
          TokenProof.compute(token, token.length, 11, 12, binding, proof));
      assertEquals(StatusCode.OK,
          codec.encodeBinaryRequest(request, ProtocolMessageType.AUTHENTICATE, 2,
              proof, proof.length));
      assertEquals(StatusCode.OK, endpoint.process(request, response));
      assertEquals(StatusCode.OK, codec.decodeResponse(response, frame, decoded));
      assertEquals(StatusCode.OK, decoded.status());

      assertEquals(StatusCode.OK, fenceResult.fence().close());
      assertEquals(StatusCode.OK,
          codec.encodeRequest(request, ProtocolMessageType.OPEN_SESSION, 3));
      assertEquals(StatusCode.OK, endpoint.process(request, response));
      assertEquals(StatusCode.OK, codec.decodeResponse(response, frame, decoded));
      assertEquals(StatusCode.ACCESS_DENIED, decoded.status());
      assertEquals(0, database.createCalls.get());
    } finally {
      endpoint.close();
      assertEquals(StatusCode.OK, authenticatorResult.authenticator().destroy());
      Arrays.fill(token, (byte) 0);
      Arrays.fill(binding, (byte) 0);
    }
  }

  @Test
  void expiredStatementIsDenied() {
    CredentialValidityFenceOpenResult result = new CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    assertEquals(StatusCode.OK,
        CredentialValidityFence.create(now - 2_000L, now - 1L, result));
    RemoteSessionAuthorizer authorizer = new RemoteSessionAuthorizer(
        io.riverdb.engine.api.SessionPermissions.READ, result.fence());
    assertEquals(StatusCode.ACCESS_DENIED,
        authorizer.authorize(io.riverdb.engine.api.SessionPermissions.READ));
    assertEquals(1, authorizer.denials());
  }

  private static final class CountingDatabase implements RiverDatabase {
    private final AtomicInteger createCalls = new AtomicInteger();

    @Override
    public StatusCode createSession(SessionOpenResult result) {
      createCalls.incrementAndGet();
      return StatusCode.INVARIANT_BROKEN;
    }

    @Override
    public StatusCode createSession(SessionAuthorizer authorizer, SessionOpenResult result) {
      createCalls.incrementAndGet();
      return StatusCode.INVARIANT_BROKEN;
    }

    @Override
    public StatusCode deferTerminalClose(RiverSession session) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    @Override
    public StatusCode close() {
      return StatusCode.OK;
    }
  }

}
