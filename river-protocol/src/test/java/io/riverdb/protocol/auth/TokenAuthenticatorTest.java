package io.riverdb.protocol.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.protocol.ProtocolFrame;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class TokenAuthenticatorTest {
  @Test
  void acceptsBoundProofThenRejectsReplayOnAnotherChannel() {
    byte[] token = "river-authentication-test-token".getBytes(StandardCharsets.UTF_8);
    byte[] firstBinding = new byte[] {1, 2, 3, 4};
    byte[] secondBinding = new byte[] {5, 6, 7, 8};
    TokenAuthenticatorOpenResult opened = new TokenAuthenticatorOpenResult();
    assertEquals(
        StatusCode.OK,
        TokenAuthenticator.create(token, token.length, opened));
    TokenAuthenticator authenticator = opened.authenticator();
    byte[] proof = new byte[TokenProof.PROOF_BYTES];
    assertEquals(
        StatusCode.OK,
        TokenProof.compute(token, token.length, 11, 12, firstBinding, proof));

    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ProtocolFrame frame = new ProtocolFrame();
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(
        StatusCode.OK,
        codec.encodeBinaryRequest(
            bytes,
            ProtocolMessageType.AUTHENTICATE,
            1,
            proof,
            proof.length));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(StatusCode.OK, authenticator.verify(frame, 11, 12, firstBinding));
    assertPayloadErased(bytes, proof.length);

    assertEquals(
        StatusCode.OK,
        codec.encodeBinaryRequest(
            bytes,
            ProtocolMessageType.AUTHENTICATE,
            2,
            proof,
            proof.length));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        authenticator.verify(frame, 21, 22, secondBinding));
    assertPayloadErased(bytes, proof.length);
    Arrays.fill(token, (byte) 0);
    Arrays.fill(proof, (byte) 0);
  }

  @Test
  void rejectsShortTokensAndMalformedProofs() {
    TokenAuthenticatorOpenResult opened = new TokenAuthenticatorOpenResult();
    byte[] shortToken = new byte[TokenProof.MINIMUM_TOKEN_BYTES - 1];
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        TokenAuthenticator.create(shortToken, shortToken.length, opened));

    byte[] token = new byte[TokenProof.MINIMUM_TOKEN_BYTES];
    Arrays.fill(token, (byte) 7);
    assertEquals(StatusCode.OK, TokenAuthenticator.create(token, token.length, opened));
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ProtocolFrame frame = new ProtocolFrame();
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    byte[] malformed = new byte[TokenProof.PROOF_BYTES - 1];
    assertEquals(
        StatusCode.OK,
        codec.encodeBinaryRequest(
            bytes,
            ProtocolMessageType.AUTHENTICATE,
            1,
            malformed,
            malformed.length));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        opened.authenticator().verify(frame, 1, 2, new byte[] {1}));
    assertPayloadErased(bytes, malformed.length);
  }

  private static void assertPayloadErased(ByteBuffer bytes, int length) {
    for (int index = 0; index < length; index++) {
      assertEquals(0, bytes.get(ProtocolFrameCodec.HEADER_BYTES + index));
    }
  }
}
