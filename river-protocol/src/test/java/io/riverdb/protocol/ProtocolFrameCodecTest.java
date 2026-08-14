package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

final class ProtocolFrameCodecTest {
  private final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  private final ProtocolFrame frame = new ProtocolFrame();

  @Test
  void preservesGoldenRequestAndResponseBytesForEveryMessageKind() {
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    for (ProtocolMessageType type : ProtocolMessageType.values()) {
      int wireCode = expectedWireCode(type);
      byte[] payload;
      StatusCode encoded;
      switch (type) {
        case AUTHENTICATE -> {
          payload = new byte[] {0x5a};
          encoded = codec.encodeBinaryRequest(bytes, type, 9, payload, payload.length);
        }
        case EXECUTE, BEGIN_QUERY -> {
          payload = new byte[] {'A'};
          encoded = codec.encodeTextRequest(bytes, type, 9, "A");
        }
        case HELLO, OPEN_SESSION, FETCH, CLOSE_QUERY, CLOSE_SESSION -> {
          payload = new byte[0];
          encoded = codec.encodeRequest(bytes, type, 9);
        }
        default -> throw new AssertionError("uncovered message kind " + type);
      }
      assertEquals(StatusCode.OK, encoded);
      assertArrayEquals(goldenFrame(wireCode, 0, 9, payload), encodedBytes(bytes));

      assertEquals(
          StatusCode.OK,
          codec.encodeStatusResponse(bytes, type, 9, StatusCode.OK, false));
      assertArrayEquals(
          goldenFrame(wireCode, 1, 9, new byte[64]),
          encodedBytes(bytes));
    }
    assertEquals(8, ProtocolMessageType.values().length);
  }

  @Test
  void preservesGoldenSpecializedResponsePayloads() {
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(
        StatusCode.OK,
        codec.encodeHelloResponse(
            bytes,
            11,
            StatusCode.OK,
            0x0102_0304_0506_0708L,
            0x1112_1314_1516_1718L));
    byte[] helloPayload = new byte[64];
    ByteBuffer hello = ByteBuffer.wrap(helloPayload).order(ByteOrder.BIG_ENDIAN);
    hello.putLong(40, 0x0102_0304_0506_0708L);
    hello.putLong(48, 0x1112_1314_1516_1718L);
    assertArrayEquals(goldenFrame(1, 1, 11, helloPayload), encodedBytes(bytes));

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, 12, StatusCode.OK, new MetadataQuery("id", "balance", "region")));
    byte[] metadataPayload = new byte[94];
    ByteBuffer metadata = ByteBuffer.wrap(metadataPayload).order(ByteOrder.BIG_ENDIAN);
    metadata.putInt(4, 12);
    metadata.putInt(12, 3);
    metadata.putInt(64, 1);
    metadata.putInt(68, 0x0000_0702);
    metadata.putInt(72, 3);
    int offset = 76;
    offset = putAsciiName(metadata, offset, "id");
    offset = putAsciiName(metadata, offset, "balance");
    assertEquals(94, putAsciiName(metadata, offset, "region"));
    assertArrayEquals(goldenFrame(5, 1, 12, metadataPayload), encodedBytes(bytes));
  }

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
    char[] text = "catalog_table_identifier_longer_than_seven".toCharArray();
    assertEquals(
        StatusCode.OK,
        command.complete(
            3,
            19,
            true,
            true,
            7,
            new long[] {11, 0},
            2,
            new int[] {SqlTypeDescriptor.varchar(64), SqlTypeDescriptor.BIGINT},
            2));
    assertEquals(StatusCode.OK, command.setTextAt(0, text, 0, text.length));
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
    assertEquals(0, response.valueAt(0));
    assertEquals(0, response.valueAt(1));
    assertFalse(response.isNull(0));
    assertTrue(response.isNull(1));
    assertTrue(response.isVarchar(0));
    assertFalse(response.isVarchar(1));
    assertEquals(SqlTypeDescriptor.varchar(64), response.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.BIGINT, response.typeDescriptorAt(1));
    char[] decoded = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
    assertEquals(text.length, response.copyTextAt(0, decoded, 0));
    assertEquals(new String(text), new String(decoded, 0, text.length));

    bytes.putLong(ProtocolFrameCodec.HEADER_BYTES + 56, 1L << 2);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));
    assertEquals(
        StatusCode.OK,
        codec.encodeCommandResponse(
            bytes, ProtocolMessageType.EXECUTE, 8, StatusCode.OK, command, false));
    bytes.putInt(ProtocolFrameCodec.HEADER_BYTES + 64, 9);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));
    assertEquals(
        StatusCode.OK,
        codec.encodeCommandResponse(
            bytes, ProtocolMessageType.EXECUTE, 8, StatusCode.OK, command, false));
    bytes.put(ProtocolFrameCodec.HEADER_BYTES + 72, (byte) 65);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));
    assertEquals(
        StatusCode.OK,
        codec.encodeCommandResponse(
            bytes, ProtocolMessageType.EXECUTE, 8, StatusCode.OK, command, false));
    bytes.put(ProtocolFrameCodec.HEADER_BYTES + 73, (byte) 0x1f);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        command.complete(
            0,
            0,
            false,
            true,
            0,
            new long[] {1},
            2,
            new int[] {SqlTypeDescriptor.BIGINT},
            1));
  }

  @Test
  void roundTripsQueryMetadataWithoutClaimingARow() {
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    MetadataQuery query = new MetadataQuery("id", "balance", "region", "amount");
    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(bytes, 9, StatusCode.OK, query));

    ProtocolResponse response = new ProtocolResponse();
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.OK, response.status());
    assertTrue(response.queryActive());
    assertFalse(response.rowAvailable());
    assertEquals(4, response.columnCount());
    assertEquals("id", response.columnName(0));
    assertEquals("balance", response.columnName(1));
    assertEquals("region", response.columnName(2));
    assertEquals("amount", response.columnName(3));
    assertEquals(0, response.valueAt(0));
    assertFalse(response.isVarchar(0));
    assertTrue(response.isVarchar(1));
    assertEquals(SqlTypeDescriptor.BIGINT, response.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.varchar(7), response.typeDescriptorAt(1));
    assertEquals(SqlTypeDescriptor.BOOLEAN, response.typeDescriptorAt(2));
    assertEquals(
        SqlTypeDescriptor.decimal(18, 6), response.typeDescriptorAt(3));

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, 10, StatusCode.INVALID_EXTERNAL_INPUT, null));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, response.status());
    assertFalse(response.queryActive());
    assertEquals(0, response.columnCount());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, 11, StatusCode.QUERY_TOO_COMPLEX, null));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.QUERY_TOO_COMPLEX, response.status());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, 12, StatusCode.CARDINALITY_VIOLATION, null));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.CARDINALITY_VIOLATION, response.status());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, 12, StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, null));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, response.status());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, 12, StatusCode.DATATYPE_MISMATCH, null));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.DATATYPE_MISMATCH, response.status());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(bytes, 13, StatusCode.OK, query));
    bytes.put(ProtocolFrameCodec.HEADER_BYTES + 80, (byte) 0);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));
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
  void inspectsRoleSpecificHeadersBeforePayloadUse() {
    ProtocolFrameHeader header = new ProtocolFrameHeader();
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(
        StatusCode.OK,
        codec.encodeTextRequest(bytes, ProtocolMessageType.EXECUTE, 41, "SELECT 1"));
    int requestLimit = bytes.limit();
    bytes.limit(ProtocolFrameCodec.HEADER_BYTES);
    assertEquals(StatusCode.OK, codec.inspectRequestHeader(bytes, header));
    assertTrue(header.isAvailable());
    assertFalse(header.isResponse());
    assertEquals(ProtocolMessageType.EXECUTE.wireCode(), header.typeWireCode());
    assertEquals(41, header.requestId());
    assertEquals(requestLimit - ProtocolFrameCodec.HEADER_BYTES, header.payloadBytes());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectResponseHeader(bytes, header));
    assertFalse(header.isAvailable());

    assertEquals(
        StatusCode.OK,
        codec.encodeStatusResponse(
            bytes, ProtocolMessageType.EXECUTE, 41, StatusCode.OK, false));
    int responseLimit = bytes.limit();
    bytes.limit(ProtocolFrameCodec.HEADER_BYTES);
    assertEquals(StatusCode.OK, codec.inspectResponseHeader(bytes, header));
    assertTrue(header.isResponse());
    assertEquals(responseLimit - ProtocolFrameCodec.HEADER_BYTES, header.payloadBytes());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectRequestHeader(bytes, header));
  }

  @Test
  void headerInspectionPreservesCallerBufferStateAtNonZeroPosition() {
    ProtocolFrameHeader header = new ProtocolFrameHeader();
    ByteBuffer encoded = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    assertEquals(
        StatusCode.OK,
        codec.encodeTextRequest(encoded, ProtocolMessageType.EXECUTE, 17, "SELECT 1"));
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.HEADER_BYTES + 7)
        .order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < ProtocolFrameCodec.HEADER_BYTES; index++) {
      bytes.put(3 + index, encoded.get(index));
    }
    bytes.position(3);
    bytes.limit(3 + ProtocolFrameCodec.HEADER_BYTES);

    assertEquals(StatusCode.OK, codec.inspectRequestHeader(bytes, header));
    assertEquals(3, bytes.position());
    assertEquals(3 + ProtocolFrameCodec.HEADER_BYTES, bytes.limit());
    assertEquals(ByteOrder.LITTLE_ENDIAN, bytes.order());
    assertEquals(17, header.requestId());

    bytes.put(3, (byte) 0);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectRequestHeader(bytes, header));
    assertFalse(header.isAvailable());
    assertEquals(3, bytes.position());
    assertEquals(3 + ProtocolFrameCodec.HEADER_BYTES, bytes.limit());
    assertEquals(ByteOrder.LITTLE_ENDIAN, bytes.order());
  }

  @Test
  void rejectsCorruptHeaderFieldsDuringRoleSpecificInspection() {
    ProtocolFrameHeader header = new ProtocolFrameHeader();
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.limit(ProtocolFrameCodec.HEADER_BYTES - 1);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectRequestHeader(bytes, header));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.putInt(0, 0);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectRequestHeader(bytes, header));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.putInt(4, ProtocolFrameCodec.VERSION + 1);
    assertEquals(StatusCode.CONFLICT, codec.inspectRequestHeader(bytes, header));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.putInt(8, Integer.MAX_VALUE);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectRequestHeader(bytes, header));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.putInt(12, 2);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectRequestHeader(bytes, header));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.putLong(16, 0);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectRequestHeader(bytes, header));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.putInt(28, 1);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectRequestHeader(bytes, header));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.putInt(24, ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES + 1);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        codec.inspectRequestHeader(bytes, header));

    assertEquals(StatusCode.OK, codec.encodeRequest(bytes, ProtocolMessageType.HELLO, 1));
    bytes.putInt(24, 1);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectRequestHeader(bytes, header));

    assertEquals(
        StatusCode.OK,
        codec.encodeStatusResponse(
            bytes, ProtocolMessageType.HELLO, 1, StatusCode.OK, false));
    bytes.putInt(24, 63);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.inspectResponseHeader(bytes, header));

    assertEquals(
        StatusCode.OK,
        codec.encodeStatusResponse(
            bytes, ProtocolMessageType.HELLO, 1, StatusCode.OK, false));
    assertEquals(StatusCode.OK, codec.inspectResponseHeader(bytes, header));
    assertEquals(64, header.payloadBytes());

    bytes.putInt(24, ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES
        - ProtocolFrameCodec.HEADER_BYTES);
    assertEquals(StatusCode.OK, codec.inspectResponseHeader(bytes, header));

    bytes.putInt(24, ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        codec.inspectResponseHeader(bytes, header));
    assertFalse(header.isAvailable());
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

  private static final class MetadataQuery implements RiverQuery {
    private final String[] names;

    private MetadataQuery(String... columnNames) {
      names = columnNames;
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
      return names.length;
    }

    @Override
    public CharSequence columnName(int index) {
      return index >= 0 && index < names.length ? names[index] : null;
    }

    @Override
    public int columnTypeDescriptor(int index) {
      return switch (index) {
        case 0 -> SqlTypeDescriptor.BIGINT;
        case 1 -> SqlTypeDescriptor.varchar(7);
        case 2 -> SqlTypeDescriptor.BOOLEAN;
        case 3 -> SqlTypeDescriptor.decimal(18, 6);
        default -> 0;
      };
    }

    @Override
    public long rowsReturned() {
      return 0;
    }
  }

  private static byte[] encodedBytes(ByteBuffer source) {
    byte[] result = new byte[source.remaining()];
    for (int index = 0; index < result.length; index++) {
      result[index] = source.get(source.position() + index);
    }
    return result;
  }

  private static byte[] goldenFrame(
      int typeWireCode,
      int flags,
      long requestId,
      byte[] payload) {
    byte[] result = new byte[32 + payload.length];
    ByteBuffer expected = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);
    expected.putInt(0, 0x52495652);
    expected.putInt(4, 2);
    expected.putInt(8, typeWireCode);
    expected.putInt(12, flags);
    expected.putLong(16, requestId);
    expected.putInt(24, payload.length);
    expected.putInt(28, 0);
    for (int index = 0; index < payload.length; index++) {
      expected.put(32 + index, payload[index]);
    }
    return result;
  }

  private static int expectedWireCode(ProtocolMessageType type) {
    return switch (type) {
      case HELLO -> 1;
      case AUTHENTICATE -> 2;
      case OPEN_SESSION -> 3;
      case EXECUTE -> 4;
      case BEGIN_QUERY -> 5;
      case FETCH -> 6;
      case CLOSE_QUERY -> 7;
      case CLOSE_SESSION -> 8;
    };
  }

  private static int putAsciiName(ByteBuffer target, int offset, String name) {
    target.put(offset++, (byte) name.length());
    for (int index = 0; index < name.length(); index++) {
      target.put(offset++, (byte) name.charAt(index));
    }
    return offset;
  }
}
