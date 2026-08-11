package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class ProtocolFrameCodecTest {
  private final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  private final ProtocolFrame frame = new ProtocolFrame();

  @Test
  void roundTripsStrictUtf8WithoutPayloadCopies() {
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    String sql = "SELECT 'λ😀'";
    assertEquals(
        StatusCode.OK,
        codec.encodeTextRequest(bytes, ProtocolMessageType.EXECUTE, 71, sql));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(ProtocolMessageType.EXECUTE, frame.type());
    assertEquals(71, frame.requestId());
    assertFalse(frame.isResponse());

    ProtocolTextDecoder text =
        new ProtocolTextDecoder(ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, text.decode(frame));
    assertEquals(sql, text.text());
  }

  @Test
  void roundTripsBoundedCommandResponse() {
    CommandResult command = new CommandResult();
    assertEquals(
        StatusCode.OK,
        command.complete(3, 19, true, true, 7, new long[] {11, 12}, 2));
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(
        StatusCode.OK,
        codec.encodeCommandResponse(
            bytes, ProtocolMessageType.EXECUTE, 8, StatusCode.OK, command, false));

    ProtocolResponse response = new ProtocolResponse();
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.OK, response.status());
    assertEquals(3, response.affectedRows());
    assertEquals(19, response.commitSequence());
    assertTrue(response.transactionActive());
    assertTrue(response.rowAvailable());
    assertEquals(7, response.key());
    assertEquals(2, response.columnCount());
    assertEquals(11, response.valueAt(0));
    assertEquals(12, response.valueAt(1));
  }

  @Test
  void rejectsMalformedAndUnboundedFramesBeforePayloadUse() {
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES + 1);
    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));

    bytes.limit(ProtocolFrameCodec.HEADER_BYTES - 1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, codec.decode(bytes, frame));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.putInt(4, ProtocolFrameCodec.VERSION + 1);
    assertEquals(StatusCode.CONFLICT, codec.decode(bytes, frame));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.putInt(24, ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES + 1);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, codec.decode(bytes, frame));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.limit(ProtocolFrameCodec.HEADER_BYTES + 1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, codec.decode(bytes, frame));

    ByteBuffer tooSmall = ByteBuffer.allocate(ProtocolFrameCodec.HEADER_BYTES);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        codec.encodeTextRequest(tooSmall, ProtocolMessageType.EXECUTE, 1, "SELECT 1"));
    assertEquals(0, tooSmall.remaining());

    ByteBuffer readOnly = ByteBuffer.allocate(128).asReadOnlyBuffer();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.encodeRequest(readOnly, ProtocolMessageType.HELLO, 1));
    assertEquals(0, readOnly.remaining());
  }

  @Test
  void rejectsMalformedUtf8AndSurrogateInput() {
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(
        StatusCode.OK,
        codec.encodeTextRequest(bytes, ProtocolMessageType.EXECUTE, 1, "A"));
    bytes.put(ProtocolFrameCodec.HEADER_BYTES, (byte) 0xc0);
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    ProtocolTextDecoder text =
        new ProtocolTextDecoder(ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, text.decode(frame));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.encodeTextRequest(
            bytes,
            ProtocolMessageType.EXECUTE,
            2,
            String.valueOf((char) 0xd800)));
    assertEquals(0, bytes.remaining());
  }
}
