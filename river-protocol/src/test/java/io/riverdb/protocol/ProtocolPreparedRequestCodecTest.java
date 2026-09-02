package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class ProtocolPreparedRequestCodecTest {
  private final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  private final ProtocolFrame frame = new ProtocolFrame();
  private final ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);

  @Test
  void roundTripsHandleAndTypedParametersWithoutSqlText() {
    ParameterSet source = new ParameterSet(2, 0);
    assertEquals(StatusCode.OK, source.appendInteger(17));
    assertEquals(StatusCode.OK, source.appendNull(0));
    assertEquals(StatusCode.OK, codec.encodePreparedRequest(
        bytes, ProtocolMessageType.EXECUTE_PREPARED, 31, 0x100000002L, source));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    ProtocolPreparedRequestDecoder decoder = new ProtocolPreparedRequestDecoder();
    assertEquals(StatusCode.OK, decoder.decode(frame));
    assertEquals(0x100000002L, decoder.handle());
    assertEquals(2, decoder.parameters().count());
    assertEquals(17, decoder.parameters().integerAt(0));
    assertTrue(decoder.parameters().isNull(1));
  }

  @Test
  void prepareResponseCarriesHandleShapeAndParameterCount() {
    PreparedOpenResult prepared = new PreparedOpenResult();
    assertEquals(StatusCode.OK, prepared.complete(0x200000001L, 3, true));
    assertEquals(StatusCode.OK,
        codec.encodePrepareResponse(bytes, 41, StatusCode.OK, prepared));
    ProtocolResponse response = new ProtocolResponse();
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(0x200000001L, response.key());
    assertEquals(3, response.affectedRows());
    assertTrue((response.flags() & ProtocolFrameCodec.FLAG_PREPARED_QUERY) != 0);
  }

  @Test
  void rejectsTruncatedMaximumCountBeforeRetainedMemoryAdmission() {
    ParameterSet empty = new ParameterSet(0, 0);
    assertEquals(StatusCode.OK, codec.encodePreparedRequest(
        bytes, ProtocolMessageType.EXECUTE_PREPARED, 51, 7, empty));
    bytes.putShort(ProtocolFrameCodec.HEADER_BYTES + Long.BYTES, (short) 0xffff);
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    ProtocolMemoryBudget budget = new ProtocolMemoryBudget(
        ParameterSet.maximumRetainedBytes());
    ProtocolPreparedRequestDecoder decoder =
        new ProtocolPreparedRequestDecoder(budget.lease());

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decoder.decode(frame));
    assertEquals(0, budget.retainedBytes());
  }
}
