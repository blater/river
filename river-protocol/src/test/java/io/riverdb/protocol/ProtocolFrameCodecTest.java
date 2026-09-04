package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
        case EXECUTE, BEGIN_QUERY, PREPARE -> {
          payload = new byte[33];
          payload[3] = 1;
          payload[32] = 'A';
          encoded = codec.encodeSqlRequest(bytes, type, 9, "A", null, 0, 0, 0);
        }
        case EXECUTE_PREPARED, BEGIN_PREPARED_QUERY -> {
          payload = new byte[36];
          payload[7] = 1;
          encoded = codec.encodePreparedRequest(
              bytes, type, 9, 1, new io.riverdb.engine.api.ParameterSet(0, 0),
              0, 0, 0);
        }
        case CLOSE_PREPARED -> {
          payload = new byte[] {0, 0, 0, 0, 0, 0, 0, 1};
          encoded = codec.encodePreparedRequest(bytes, type, 9, 1, null, 0, 0, 0);
        }
        case HELLO, OPEN_SESSION, FETCH, CLOSE_QUERY, CLOSE_SESSION -> {
          payload = new byte[0];
          encoded = codec.encodeRequest(bytes, type, 9);
        }
        case PREPARE_PROGRAM, EXECUTE_PROGRAM, CLOSE_PROGRAM -> {
          continue;
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
    assertEquals(15, ProtocolMessageType.values().length);
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
            bytes, ProtocolMessageType.BEGIN_QUERY, 12, StatusCode.OK,
            metadata(new MetadataQuery("id", "balance", "region")),
            row(7, 0b010, 7, 0, 1), 1, completion(), false));
    byte[] metadataPayload = new byte[116];
    ByteBuffer metadata = ByteBuffer.wrap(metadataPayload).order(ByteOrder.BIG_ENDIAN);
    metadata.putInt(4, 41);
    metadata.putInt(12, 3);
    metadata.putLong(24, 7);
    metadata.putLong(32, 1);
    metadata.putInt(56, 1);
    metadata.putInt(60, 31);
    metadata.put(64, (byte) 0b010);
    metadata.putInt(65, 1);
    metadata.putInt(69, 0x0000_0702);
    metadata.putInt(73, 3);
    int offset = 77;
    offset = putAsciiName(metadata, offset, "id");
    offset = putAsciiName(metadata, offset, "balance");
    offset = putAsciiName(metadata, offset, "region");
    metadata.put(offset++, (byte) 0b010);
    metadata.putLong(offset, 7);
    offset += Long.BYTES;
    metadata.putInt(offset, 0);
    offset += Integer.BYTES;
    metadata.putLong(offset, 1);
    offset += Long.BYTES;
    assertEquals(116, offset);
    assertArrayEquals(goldenFrame(5, 1, 12, metadataPayload), encodedBytes(bytes));
  }

  @Test
  void roundTripsStrictUtf8WithoutPayloadCopies() {
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    String sql = "SELECT 'λ😀'";
    assertEquals(
        StatusCode.OK,
        codec.encodeSqlRequest(
            bytes, ProtocolMessageType.EXECUTE, 71, sql, null, 0, 0, 0));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(ProtocolMessageType.EXECUTE, frame.type());
    assertEquals(71, frame.requestId());
    assertFalse(frame.isResponse());

    ProtocolSqlRequestDecoder request = new ProtocolSqlRequestDecoder();
    assertEquals(StatusCode.OK, request.decode(frame));
    assertEquals(sql, request.sql());
  }

  @Test
  void roundTripsBoundedCommandResponse() {
    CommandResult command = new CommandResult();
    char[] text = "catalog_table_identifier_longer_than_seven".toCharArray();
    assertEquals(StatusCode.OK, command.reserve(2, text.length));
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
  void decodesMaximumDescriptorVarcharResponseBeyondUnsignedShortLength() {
    // Ordinary response text is bounded by the result-row byte contract and VARCHAR scalar
    // declaration, independently of the 16 KiB physical frame and legacy u16 length width.
    String value = "\u0800".repeat(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS);
    ByteBuffer bytes = encodedTextResponse(value);
    int utf8Bytes = bytes.getInt(responseTextLengthOffset());
    assertTrue(utf8Bytes > Short.toUnsignedInt((short) -1));
    assertTrue(utf8Bytes <= SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES);
    assertTrue(bytes.remaining() - ProtocolFrameCodec.HEADER_BYTES
        <= ProtocolFrameCodec.MAXIMUM_LOGICAL_RESPONSE_PAYLOAD_BYTES);

    ProtocolResponse response = new ProtocolResponse();
    assertEquals(StatusCode.OK,
        new ProtocolResponseDecoder().decodeAssembled(bytes, frame, response));
    assertEquals(utf8Bytes, response.textByteLengthAt(0));
    char[] decoded = new char[value.length()];
    assertEquals(value.length(), response.copyTextAt(0, decoded, 0));
    assertEquals('\u0800', decoded[0]);
    assertEquals('\u0800', decoded[decoded.length - 1]);
  }

  @Test
  void rejectsNegativeAndEnvelopeOverrunU32ResponseTextLengths() {
    // The decoder rejects both the signed-invalid u32 representation and a positive length that
    // crosses the enclosing logical result boundary before exposing any columns.
    String value = "\u0800".repeat(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS);
    int length = responseTextLengthOffset();
    ProtocolResponse response = new ProtocolResponse();
    ProtocolResponseDecoder decoder = new ProtocolResponseDecoder();

    ByteBuffer bytes = encodedTextResponse(value);
    int utf8Bytes = bytes.getInt(length);
    bytes.putInt(length, -1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        decoder.decodeAssembled(bytes, frame, response));
    assertEquals(0, response.columnCount());

    bytes = encodedTextResponse(value);
    bytes.putInt(length, utf8Bytes + 1);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        decoder.decodeAssembled(bytes, frame, response));
    assertEquals(0, response.columnCount());
  }

  @Test
  void roundTripsDecimal128ResponseWithoutIntermediateObjects() {
    int descriptor = SqlTypeDescriptor.decimal(38, 6);
    long high = 669_260_594_276_348_691L;
    long low = -4_302_749_291_975_740_594L;
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, command.complete(
        1, 1, false, true, 0,
        new long[] {high}, new long[] {low}, new long[1], 1,
        new int[] {descriptor}, 1));
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(StatusCode.OK, codec.encodeCommandResponse(
        bytes, ProtocolMessageType.EXECUTE, 92, StatusCode.OK, command, false));
    ProtocolResponse response = new ProtocolResponse();
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(high, response.decimalUnscaledHighAt(0));
    assertEquals(low, response.decimalUnscaledLowAt(0));
    assertEquals(0, response.decimalUnscaledAt(0));

    assertEquals(StatusCode.OK, codec.encodeCommandResponse(
        bytes, ProtocolMessageType.EXECUTE, 92, StatusCode.OK, command, false));
    int valueOffset = ProtocolFrameCodec.HEADER_BYTES + 64 + 1 + Integer.BYTES;
    bytes.putLong(valueOffset, Long.MAX_VALUE);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));
  }

  @Test
  void rejectsInvalidFixedWidthValuesAtTheRemoteBoundary() {
    assertInvalidFixedValue(SqlTypeDescriptor.SMALLINT, Short.MIN_VALUE,
        (long) Short.MIN_VALUE - 1);
    assertInvalidFixedValue(SqlTypeDescriptor.INTEGER, Integer.MAX_VALUE,
        (long) Integer.MAX_VALUE + 1);
    assertInvalidFixedValue(SqlTypeDescriptor.BOOLEAN, 1, 2);
    assertInvalidFixedValue(SqlTypeDescriptor.decimal(3, 1), 999, 1_000);
    assertInvalidFixedValue(SqlTypeDescriptor.REAL,
        SqlApproximateNumeric.realBits(1.25f),
        Integer.toUnsignedLong(Float.floatToRawIntBits(-0.0f)));
    assertInvalidFixedValue(SqlTypeDescriptor.DOUBLE,
        SqlApproximateNumeric.doubleBits(-2.5d),
        Double.doubleToRawLongBits(Double.POSITIVE_INFINITY));
    assertInvalidFixedValue(SqlTypeDescriptor.DATE, -1, 2_932_897L);
    assertInvalidFixedValue(SqlTypeDescriptor.time(3), 123_000, 123_001);
    assertInvalidFixedValue(
        SqlTypeDescriptor.timestamp(3), -1_000, -999);
    assertInvalidFixedValue(
        SqlTypeDescriptor.timestampWithTimeZone(3), 1_000, 1_001);
  }

  @Test
  void roundTripsQueryMetadataAndFirstRowWithIndependentNullability() {
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    MetadataQuery query = new MetadataQuery("id", "balance", "region", "amount");
    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, ProtocolMessageType.BEGIN_QUERY, 9, StatusCode.OK, metadata(query),
            row(9, 0b0101, 0, 0, 1, 0), 1, null, true));

    ProtocolResponse response = new ProtocolResponse();
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.OK, response.status());
    assertTrue(response.queryActive());
    assertFalse(response.endOfStream());
    assertTrue(response.rowAvailable());
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
    assertEquals(0b0101, response.nullMask());
    assertFalse(response.columnIsNullable(0));
    assertTrue(response.columnIsNullable(1));
    assertFalse(response.columnIsNullable(2));
    assertTrue(response.columnIsNullable(3));
    bytes.putInt(ProtocolFrameCodec.HEADER_BYTES + 4,
        bytes.getInt(ProtocolFrameCodec.HEADER_BYTES + 4)
            | ProtocolFrameCodec.FLAG_END_OF_STREAM);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, ProtocolMessageType.BEGIN_QUERY, 10, StatusCode.OK, metadata(query),
            new RowResult(), 0, completion(0, 22, true), false));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertTrue(response.endOfStream());
    assertFalse(response.queryActive());
    assertFalse(response.rowAvailable());
    assertEquals(0, response.rowsReturned());
    assertTrue(response.columnIsNullable(1));
    assertTrue(response.transactionActive());
    assertEquals(22, response.commitSequence());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, ProtocolMessageType.BEGIN_QUERY, 11,
            StatusCode.INVALID_EXTERNAL_INPUT, null, null, 0, null, false));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, response.status());
    assertFalse(response.queryActive());
    assertEquals(0, response.columnCount());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, ProtocolMessageType.BEGIN_QUERY, 12,
            StatusCode.QUERY_TOO_COMPLEX, null, null, 0, null, false));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.QUERY_TOO_COMPLEX, response.status());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, ProtocolMessageType.BEGIN_QUERY, 13,
            StatusCode.CARDINALITY_VIOLATION, null, null, 0, null, false));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.CARDINALITY_VIOLATION, response.status());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, ProtocolMessageType.BEGIN_QUERY, 14,
            StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, null, null, 0, null, false));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, response.status());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, ProtocolMessageType.BEGIN_QUERY, 15,
            StatusCode.DATATYPE_MISMATCH, null, null, 0, null, false));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(StatusCode.DATATYPE_MISMATCH, response.status());

    assertEquals(
        StatusCode.OK,
        codec.encodeQueryOpenResponse(
            bytes, ProtocolMessageType.BEGIN_QUERY, 16, StatusCode.OK, metadata(query),
            row(9, 0, 91, 0, 1, 2), 1, null, true));
    bytes.put(ProtocolFrameCodec.HEADER_BYTES + 81, (byte) 0);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));
  }

  @Test
  void finalFetchCarriesItsRowAndQueryCompletionTogether() {
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    RowResult finalRow = row(12, 0b010, 12, 0, 1);
    CommandResult completion = completion(0, 44, true);
    assertEquals(StatusCode.OK, codec.encodeRowResponse(
        bytes, ProtocolMessageType.FETCH, 17, StatusCode.OK,
        finalRow, 3, completion, false));
    ProtocolResponse response = new ProtocolResponse();
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertTrue(response.rowAvailable());
    assertTrue(response.endOfStream());
    assertFalse(response.queryActive());
    assertTrue(response.transactionActive());
    assertEquals(3, response.rowsReturned());
    assertEquals(44, response.commitSequence());
    assertEquals(12, response.valueAt(0));
  }

  @Test
  void roundTripsMultiwordNullsThroughMaximumResultShape() {
    int columns = 1_664;
    long[] values = new long[columns];
    int[] descriptors = new int[columns];
    long[] nulls = new long[(columns + 63) >>> 6];
    for (int index = 0; index < columns; index++) {
      values[index] = index;
      descriptors[index] = SqlTypeDescriptor.BIGINT;
    }
    char[] text = "é😀".toCharArray();
    descriptors[1_662] = SqlTypeDescriptor.varchar(8);
    nulls[1] = 1;
    nulls[nulls.length - 1] = 1L << 63;
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, command.reserve(columns, 6));
    assertEquals(StatusCode.OK, command.complete(
        1, 0, false, true, 0, values, nulls, nulls.length, descriptors, columns));
    assertEquals(StatusCode.OK, command.setTextAt(1_662, text, 0, text.length));
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(StatusCode.OK, codec.encodeCommandResponse(
        bytes, ProtocolMessageType.EXECUTE, 42, StatusCode.OK, command, false));
    ProtocolResponse response = new ProtocolResponse();
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertEquals(columns, response.columnCount());
    assertTrue(response.isNull(64));
    assertTrue(response.isNull(1_663));
    assertFalse(response.isNull(63));
    char[] decoded = new char[8];
    assertEquals(text.length, response.copyTextAt(1_662, decoded, 0));
    assertEquals(new String(text), new String(decoded, 0, text.length));

    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        new ProtocolQueryMetadata().capture(new WideMetadataQuery(1_665)));

    ProtocolQueryMetadata metadata = new ProtocolQueryMetadata();
    assertEquals(StatusCode.OK, metadata.capture(new WideMetadataQuery(columns)));
    RowResult row = new RowResult();
    int[] queryDescriptors = new int[columns];
    java.util.Arrays.fill(queryDescriptors, SqlTypeDescriptor.BIGINT);
    assertEquals(StatusCode.OK, row.complete(
        0, values, nulls, nulls.length, queryDescriptors, columns));
    assertEquals(StatusCode.OK, codec.encodeQueryOpenResponse(
        bytes, ProtocolMessageType.BEGIN_QUERY, 43, StatusCode.OK,
        metadata, row, 1, null, true));
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    assertTrue(response.queryActive());
    assertTrue(response.rowAvailable());
    assertEquals(columns, response.columnCount());
    assertEquals(columns - 1, response.valueAt(columns - 1));
  }

  @Test
  void rejectsGappedOverlappingAndPrematureContinuationFrames() {
    int columns = 1_664;
    long[] values = new long[columns];
    int[] descriptors = new int[columns];
    java.util.Arrays.fill(descriptors, SqlTypeDescriptor.BIGINT);
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, command.complete(
        1, 0, false, true, 0, values, new long[26], 26, descriptors, columns));
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    ProtocolResponse response = new ProtocolResponse();

    assertEquals(StatusCode.OK, codec.encodeCommandResponse(
        bytes, ProtocolMessageType.EXECUTE, 44, StatusCode.OK, command, false));
    int second = ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
    bytes.putInt(second + ProtocolFrameCodec.HEADER_BYTES + 4,
        ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES - 11);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));

    assertEquals(StatusCode.OK, codec.encodeCommandResponse(
        bytes, ProtocolMessageType.EXECUTE, 44, StatusCode.OK, command, false));
    bytes.putInt(second + ProtocolFrameCodec.HEADER_BYTES + 4,
        ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES - 13);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));

    assertEquals(StatusCode.OK, codec.encodeCommandResponse(
        bytes, ProtocolMessageType.EXECUTE, 44, StatusCode.OK, command, false));
    bytes.putInt(12, bytes.getInt(12) | 4);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));
    assertEquals(0, response.columnCount());
  }

  @Test
  void responseAssemblyFailsClosedOnDuplicateInvalidAndEmptySegments() {
    int columns = 1_664;
    int[] descriptors = new int[columns];
    java.util.Arrays.fill(descriptors, SqlTypeDescriptor.BIGINT);
    CommandResult command = new CommandResult();
    assertEquals(StatusCode.OK, command.complete(
        1, 0, false, true, 0, new long[columns], new long[26], 26,
        descriptors, columns));
    ByteBuffer encoded = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(StatusCode.OK, codec.encodeCommandResponse(
        encoded, ProtocolMessageType.EXECUTE, 45, StatusCode.OK, command, false));
    ByteBuffer first = encoded.duplicate();
    first.limit(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    first = first.slice();
    ProtocolFrameHeader firstHeader = new ProtocolFrameHeader();
    assertEquals(StatusCode.OK, codec.inspectResponseHeader(first, firstHeader));
    ProtocolResponseAssembly assembly = new ProtocolResponseAssembly();
    assertEquals(StatusCode.OK, assembly.accept(first, firstHeader));
    assertTrue(assembly.isActive());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assembly.accept(first, firstHeader));
    assertFalse(assembly.isActive());

    ByteBuffer second = encoded.duplicate();
    second.position(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    ProtocolFrameHeader secondHeader = new ProtocolFrameHeader();
    assertEquals(StatusCode.OK, codec.inspectResponseHeader(second, secondHeader));
    second.limit(second.position() + ProtocolFrameCodec.HEADER_BYTES
        + secondHeader.payloadBytes());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        assembly.accept(second.slice(), secondHeader));
    assertFalse(assembly.isActive());

    ProtocolFrameHeader unknown = new ProtocolFrameHeader();
    unknown.complete(Integer.MAX_VALUE, 45, firstHeader.payloadBytes(), true, true, false);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assembly.accept(first, unknown));

    encoded.putInt(24, ProtocolResponseSegmenter.SEGMENT_BYTES);
    encoded.putInt(ProtocolFrameCodec.HEADER_BYTES + 8, 0);
    ByteBuffer empty = encoded.duplicate();
    empty.limit(ProtocolFrameCodec.HEADER_BYTES + ProtocolResponseSegmenter.SEGMENT_BYTES);
    empty = empty.slice();
    ProtocolFrameHeader emptyHeader = new ProtocolFrameHeader();
    assertEquals(StatusCode.OK, codec.inspectResponseHeader(empty, emptyHeader));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assembly.accept(empty, emptyHeader));
    assertFalse(assembly.isActive());
  }

  @Test
  void roundTripsTemporalFailureCodes() {
    ByteBuffer bytes = ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    ProtocolResponse response = new ProtocolResponse();
    StatusCode[] statuses = {
        StatusCode.INVALID_DATETIME_FORMAT,
        StatusCode.DATETIME_FIELD_OVERFLOW,
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        StatusCode.STRING_DATA_RIGHT_TRUNCATION,
        StatusCode.FEATURE_NOT_SUPPORTED,
        StatusCode.PARAMETER_COUNT_MISMATCH,
        StatusCode.PROGRAM_STALE
    };
    for (StatusCode status : statuses) {
      assertEquals(
          StatusCode.OK,
          codec.encodeQueryOpenResponse(
              bytes, ProtocolMessageType.BEGIN_QUERY, 18,
              status, null, null, 0, null, false));
      assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
      assertEquals(status, response.status());
    }
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
        codec.encodeSqlRequest(
            tooSmall, ProtocolMessageType.EXECUTE, 1, "SELECT 1", null, 0, 0, 0));
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
        codec.encodeSqlRequest(
            bytes, ProtocolMessageType.EXECUTE, 41, "SELECT 1", null, 0, 0, 0));
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
        codec.encodeSqlRequest(
            encoded, ProtocolMessageType.EXECUTE, 17, "SELECT 1", null, 0, 0, 0));
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

    bytes.putInt(24, ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES);
    assertEquals(StatusCode.OK, codec.inspectResponseHeader(bytes, header));

    bytes.putInt(24, ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES + 1);
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
        codec.encodeSqlRequest(
            bytes, ProtocolMessageType.EXECUTE, 1, "A", null, 0, 0, 0));
    bytes.put(ProtocolFrameCodec.HEADER_BYTES + 32, (byte) 0xc0);
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    ProtocolSqlRequestDecoder request = new ProtocolSqlRequestDecoder();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, request.decode(frame));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.encodeSqlRequest(
            bytes,
            ProtocolMessageType.EXECUTE,
            2,
            String.valueOf((char) 0xd800),
            null,
            0, 0, 0));
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
    public boolean columnIsNullable(int index) {
      return index == 1 || index == 3;
    }

    @Override
    public long rowsReturned() {
      return 0;
    }
  }

  private static ProtocolQueryMetadata metadata(RiverQuery query) {
    ProtocolQueryMetadata metadata = new ProtocolQueryMetadata();
    assertEquals(StatusCode.OK, metadata.capture(query));
    return metadata;
  }

  private static RowResult row(long key, long nullMask, long... values) {
    int[] descriptors = new int[values.length];
    for (int index = 0; index < values.length; index++) {
      descriptors[index] = switch (index) {
        case 0 -> SqlTypeDescriptor.BIGINT;
        case 1 -> SqlTypeDescriptor.varchar(7);
        case 2 -> SqlTypeDescriptor.BOOLEAN;
        case 3 -> SqlTypeDescriptor.decimal(18, 6);
        default -> throw new AssertionError("unsupported test column " + index);
      };
    }
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, row.reserve(values.length, 1));
    assertEquals(StatusCode.OK, row.complete(key, values, nullMask, descriptors, values.length));
    if (values.length > 1 && (nullMask & 2) == 0) {
      assertEquals(StatusCode.OK, row.setTextAt(1, new char[] {'x'}, 0, 1));
    }
    return row;
  }

  private static ByteBuffer encodedTextResponse(String value) {
    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
    int nullBytes = ProtocolResponseNullBitmap.bytes(1);
    int payloadBytes = responseFixedBytes() + nullBytes
        + Integer.BYTES + Integer.BYTES + utf8.length;
    ByteBuffer response = ByteBuffer.allocate(ProtocolFrameCodec.HEADER_BYTES + payloadBytes);
    assertEquals(StatusCode.OK, ProtocolFrameWire.begin(
        response, ProtocolMessageType.EXECUTE, 81, payloadBytes,
        ProtocolFrameWire.FRAME_RESPONSE));
    int fixed = ProtocolFrameCodec.HEADER_BYTES;
    response.putInt(fixed, StatusCode.OK.stableCode());
    response.putInt(fixed + Integer.BYTES, ProtocolFrameCodec.FLAG_ROW_AVAILABLE);
    response.putInt(fixed + Integer.BYTES * 3, 1);
    response.putInt(fixed + Integer.BYTES * 4 + Long.BYTES * 5, nullBytes);
    int descriptor = fixed + responseFixedBytes() + nullBytes;
    response.putInt(descriptor,
        SqlTypeDescriptor.varchar(SqlTypeDescriptor.MAXIMUM_VARCHAR_SCALARS));
    int length = descriptor + Integer.BYTES;
    response.putInt(length, utf8.length);
    ByteBuffer text = response.duplicate();
    text.position(length + Integer.BYTES);
    text.put(utf8);
    response.position(0);
    return response;
  }

  private static int responseTextLengthOffset() {
    return ProtocolFrameCodec.HEADER_BYTES + responseFixedBytes()
        + ProtocolResponseNullBitmap.bytes(1) + Integer.BYTES;
  }

  private static int responseFixedBytes() {
    return Integer.BYTES * 6 + Long.BYTES * 5;
  }

  private static CommandResult completion() {
    return completion(0, 0, false);
  }

  private static CommandResult completion(int rows, long sequence, boolean transactionActive) {
    CommandResult result = new CommandResult();
    assertEquals(StatusCode.OK, result.complete(
        rows, sequence, transactionActive, false, 0, null, 0, null, 0));
    return result;
  }

  private static final class WideMetadataQuery implements RiverQuery {
    private final int columns;

    private WideMetadataQuery(int count) { columns = count; }
    @Override public StatusCode next(RowResult result) { return StatusCode.CONFLICT; }
    @Override public StatusCode close(CommandResult result) { return StatusCode.OK; }
    @Override public boolean isActive() { return true; }
    @Override public int columnCount() { return columns; }
    @Override public CharSequence columnName(int index) { return "c" + index; }
    @Override public int columnTypeDescriptor(int index) { return SqlTypeDescriptor.BIGINT; }
    @Override public boolean columnIsNullable(int index) { return false; }
    @Override public long rowsReturned() { return 0; }
  }

  private void assertInvalidFixedValue(
      int descriptor, long valid, long invalid) {
    CommandResult command = new CommandResult();
    assertEquals(
        StatusCode.OK,
        command.complete(
            0,
            0,
            false,
            true,
            0,
            new long[] {valid},
            0,
            new int[] {descriptor},
            1));
    ByteBuffer bytes = ByteBuffer.allocate(
        ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
    assertEquals(
        StatusCode.OK,
        codec.encodeCommandResponse(
            bytes,
            ProtocolMessageType.EXECUTE,
            91,
            StatusCode.OK,
            command,
            false));
    ProtocolResponse response = new ProtocolResponse();
    assertEquals(StatusCode.OK, codec.decodeResponse(bytes, frame, response));
    bytes.putLong(
        ProtocolFrameCodec.HEADER_BYTES + 64 + 1 + Integer.BYTES,
        invalid);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        codec.decodeResponse(bytes, frame, response));
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
    expected.putInt(4, ProtocolFrameCodec.VERSION);
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
      case PREPARE -> 9;
      case EXECUTE_PREPARED -> 10;
      case BEGIN_PREPARED_QUERY -> 11;
      case CLOSE_PREPARED -> 12;
      case PREPARE_PROGRAM -> 13;
      case EXECUTE_PROGRAM -> 14;
      case CLOSE_PROGRAM -> 15;
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
