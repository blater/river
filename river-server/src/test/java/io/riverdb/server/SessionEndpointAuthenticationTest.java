package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RiverDatabase;
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
import org.junit.jupiter.api.Test;

final class SessionEndpointAuthenticationTest {
  @Test
  void requiresAuthenticationAndFencesThreeFailedProofs() {
    byte[] token = new byte[TokenProof.MINIMUM_TOKEN_BYTES];
    Arrays.fill(token, (byte) 7);
    TokenAuthenticatorOpenResult opened = new TokenAuthenticatorOpenResult();
    assertEquals(StatusCode.OK, TokenAuthenticator.create(token, token.length, opened));
    SessionEndpoint endpoint = new SessionEndpoint(
        new UnusedDatabase(),
        opened.authenticator(),
        11,
        12,
        new byte[] {1, 2, 3});
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ProtocolFrame frame = new ProtocolFrame();
    ProtocolResponse decoded = new ProtocolResponse();
    ByteBuffer request = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    ByteBuffer response = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);

    assertEquals(
        StatusCode.OK,
        codec.encodeRequest(request, ProtocolMessageType.HELLO, 1));
    assertEquals(StatusCode.OK, endpoint.process(request, response));
    assertEquals(StatusCode.OK, codec.decodeResponse(response, frame, decoded));
    assertEquals(StatusCode.OK, decoded.status());
    assertEquals(11, decoded.challengeHigh());
    assertEquals(12, decoded.challengeLow());

    assertStatus(
        codec,
        endpoint,
        request,
        response,
        frame,
        decoded,
        2,
        StatusCode.INVALID_EXTERNAL_INPUT);
    assertStatus(
        codec,
        endpoint,
        request,
        response,
        frame,
        decoded,
        3,
        StatusCode.INVALID_EXTERNAL_INPUT);
    assertStatus(
        codec,
        endpoint,
        request,
        response,
        frame,
        decoded,
        4,
        StatusCode.FENCED);
    assertEquals(3, endpoint.authenticationFailures());
    assertEquals(StatusCode.CLOSED, endpoint.close());
  }

  private static void assertStatus(
      ProtocolFrameCodec codec,
      SessionEndpoint endpoint,
      ByteBuffer request,
      ByteBuffer response,
      ProtocolFrame frame,
      ProtocolResponse decoded,
      long requestId,
      StatusCode expected) {
    byte[] wrongProof = new byte[TokenProof.PROOF_BYTES];
    assertEquals(
        StatusCode.OK,
        codec.encodeBinaryRequest(
            request,
            ProtocolMessageType.AUTHENTICATE,
            requestId,
            wrongProof,
            wrongProof.length));
    assertEquals(StatusCode.OK, endpoint.process(request, response));
    assertEquals(StatusCode.OK, codec.decodeResponse(response, frame, decoded));
    assertEquals(expected, decoded.status());
    for (int index = 0; index < wrongProof.length; index++) {
      assertEquals(0, request.get(ProtocolFrameCodec.HEADER_BYTES + index));
    }
  }

  private static final class UnusedDatabase implements RiverDatabase {
    @Override
    public StatusCode createSession(SessionOpenResult result) {
      return StatusCode.INVARIANT_BROKEN;
    }

    @Override
    public StatusCode close() {
      return StatusCode.INVARIANT_BROKEN;
    }
  }
}
