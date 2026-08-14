package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

final class ProtocolFrameFuzzTest {
  private static final int MUTATIONS = 50_000;

  @Test
  void boundedMutationsNeverEscapeCodecValidation() {
    ProtocolFrameCodec codec = new ProtocolFrameCodec();
    ProtocolFrame frame = new ProtocolFrame();
    ProtocolResponse response = new ProtocolResponse();
    ByteBuffer seed = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    ByteBuffer candidate = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    byte[] seedBytes = seed.array();
    byte[] candidateBytes = candidate.array();
    SplittableRandom random = new SplittableRandom(0x524956455246555aL);

    assertEquals(StatusCode.OK, codec.encodeTextRequest(
        seed,
        ProtocolMessageType.EXECUTE,
        1,
        "SELECT id FROM rows WHERE id=1"));
    int requestBytes = seed.remaining();
    for (int iteration = 0; iteration < MUTATIONS; iteration++) {
      System.arraycopy(seedBytes, 0, candidateBytes, 0, requestBytes);
      mutate(candidateBytes, requestBytes, random);
      candidate.position(0);
      candidate.limit(random.nextInt(requestBytes + 1));
      StatusCode status = codec.decode(candidate, frame);
      assertNotNull(status);
    }

    ByteBuffer responseSeed = ByteBuffer.allocate(
        ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(StatusCode.OK, codec.encodeStatusResponse(
        responseSeed,
        ProtocolMessageType.EXECUTE,
        1,
        StatusCode.ACCESS_DENIED,
        false));
    int responseBytes = responseSeed.remaining();
    byte[] responseSeedBytes = responseSeed.array();
    for (int iteration = 0; iteration < MUTATIONS; iteration++) {
      System.arraycopy(responseSeedBytes, 0, candidateBytes, 0, responseBytes);
      mutate(candidateBytes, responseBytes, random);
      candidate.position(0);
      candidate.limit(random.nextInt(responseBytes + 1));
      StatusCode status = codec.decodeResponse(candidate, frame, response);
      assertNotNull(status);
    }
  }

  private static void mutate(
      byte[] bytes,
      int length,
      SplittableRandom random) {
    int changes = 1 + random.nextInt(4);
    for (int index = 0; index < changes; index++) {
      int position = random.nextInt(length);
      bytes[position] ^= (byte) (1 << random.nextInt(8));
    }
  }
}
