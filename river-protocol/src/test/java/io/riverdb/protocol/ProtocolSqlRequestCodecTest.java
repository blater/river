package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class ProtocolSqlRequestCodecTest {
  private final ProtocolFrameCodec codec = new ProtocolFrameCodec();
  private final ProtocolFrame frame = new ProtocolFrame();
  private final ProtocolSqlRequestDecoder decoder = new ProtocolSqlRequestDecoder();
  private final ByteBuffer bytes =
      ByteBuffer.allocate(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);

  @Test
  void roundTripsV3SqlAndEveryTypedParameterFamily() {
    ParameterSet source = new ParameterSet(10, 32);
    assertEquals(StatusCode.OK, source.appendFixed(SqlTypeDescriptor.BIGINT, Long.MIN_VALUE));
    assertEquals(StatusCode.OK, source.appendFixed(SqlTypeDescriptor.BOOLEAN, 1));
    assertEquals(
        StatusCode.OK,
        source.appendFixed(SqlTypeDescriptor.decimal(5, 2), -12_345));
    assertEquals(
        StatusCode.OK,
        source.appendText(SqlTypeDescriptor.varchar(8), "Aé😀"));
    assertEquals(
        StatusCode.OK,
        source.appendFixed(SqlTypeDescriptor.DATE, LocalTemporal.MINIMUM_EPOCH_DAY));
    assertEquals(StatusCode.OK, source.appendFixed(SqlTypeDescriptor.time(3), 1_000));
    assertEquals(StatusCode.OK, source.appendFixed(SqlTypeDescriptor.timestamp(6), -1));
    assertEquals(
        StatusCode.OK,
        source.appendFixed(SqlTypeDescriptor.timestampWithTimeZone(0), 0));
    assertEquals(StatusCode.OK, source.appendNull(SqlTypeDescriptor.DATE));
    assertEquals(StatusCode.OK, source.appendNull(0));

    String sql = "INSERT INTO values_8 VALUES (?,?,?,?,?,?,?,?,?,?)";
    assertEquals(
        StatusCode.OK,
        codec.encodeSqlRequest(bytes, ProtocolMessageType.EXECUTE, 71, sql, source));
    int payload = ProtocolFrameCodec.HEADER_BYTES;
    assertEquals(sql.length(), bytes.getInt(payload));
    assertEquals(10, Short.toUnsignedInt(bytes.getShort(payload + 4)));
    assertEquals(0, Short.toUnsignedInt(bytes.getShort(payload + 6)));
    int first = payload + 8 + sql.length();
    assertEquals(SqlTypeDescriptor.BIGINT, bytes.getInt(first));
    assertEquals(0, Byte.toUnsignedInt(bytes.get(first + 4)));
    assertEquals(8, Short.toUnsignedInt(bytes.getShort(first + 6)));
    assertEquals(Long.MIN_VALUE, bytes.getLong(first + 8));
    int text = first + 3 * 16;
    assertEquals(SqlTypeDescriptor.varchar(8), bytes.getInt(text));
    assertEquals(7, Short.toUnsignedInt(bytes.getShort(text + 6)));
    assertEquals('A', Byte.toUnsignedInt(bytes.get(text + 8)));
    int typedNull = text + 15 + 4 * 16;
    assertEquals(SqlTypeDescriptor.DATE, bytes.getInt(typedNull));
    assertEquals(1, Byte.toUnsignedInt(bytes.get(typedNull + 4)));
    assertEquals(0, Short.toUnsignedInt(bytes.getShort(typedNull + 6)));
    int untypedNull = typedNull + 8;
    assertEquals(0, bytes.getInt(untypedNull));
    assertEquals(1, Byte.toUnsignedInt(bytes.get(untypedNull + 4)));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(StatusCode.OK, decoder.decode(frame));
    assertPayloadErased();
    assertEquals(sql, decoder.sql());
    ParameterSet decoded = decoder.parameters();
    assertEquals(10, decoded.count());
    assertEquals(Long.MIN_VALUE, decoded.valueAt(0));
    assertEquals(SqlTypeDescriptor.BOOLEAN, decoded.typeDescriptorAt(1));
    assertEquals(-12_345, decoded.valueAt(2));
    assertEquals(7, decoded.textLengthAt(3));
    assertEquals(-1, decoded.valueAt(6));
    assertTrue(decoded.isNull(8));
    assertTrue(decoded.isNull(9));
    assertEquals(0, decoded.typeDescriptorAt(9));
    assertFalse(decoded.isNull(7));
    decoder.reset();
    assertEquals(null, decoder.sql());
    assertEquals(0, decoder.parameters().count());
  }

  @Test
  void roundTripsDecimal128AsOneCanonicalSixteenByteParameter() {
    ParameterSet source = new ParameterSet(1, 0);
    long high = -669_260_594_276_348_692L;
    long low = 4_302_749_291_975_740_594L;
    assertEquals(StatusCode.OK, source.appendDecimal128(38, 6, high, low));
    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        bytes, ProtocolMessageType.EXECUTE, 91, "SELECT ?", source));
    int entry = ProtocolFrameCodec.HEADER_BYTES + 8 + "SELECT ?".length();
    assertEquals(16, Short.toUnsignedInt(bytes.getShort(entry + 6)));
    assertEquals(high, bytes.getLong(entry + 8));
    assertEquals(low, bytes.getLong(entry + 16));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(StatusCode.OK, decoder.decode(frame));
    assertEquals(high, decoder.parameters().decimalUnscaledHighAt(0));
    assertEquals(low, decoder.parameters().decimalUnscaledLowAt(0));
  }

  @Test
  void roundTripsPlainSqlThroughTheSameEnvelope() {
    assertEquals(
        StatusCode.OK,
        codec.encodeSqlRequest(
            bytes, ProtocolMessageType.BEGIN_QUERY, 8, "SELECT 1", null));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(StatusCode.OK, decoder.decode(frame));
    assertEquals("SELECT 1", decoder.sql());
    assertEquals(0, decoder.parameters().count());
  }

  @Test
  void roundTripsEveryNumericParameterDescriptorAndCanonicalBits() {
    ParameterSet source = new ParameterSet(6, 0);
    assertEquals(StatusCode.OK, source.appendSmallint((short) -7));
    assertEquals(StatusCode.OK, source.appendInteger(80_000));
    assertEquals(StatusCode.OK, source.appendBigint(Long.MAX_VALUE));
    assertEquals(StatusCode.OK, source.appendDecimal(6, 2, 12_345));
    assertEquals(StatusCode.OK, source.appendReal(1.5f));
    assertEquals(StatusCode.OK, source.appendDouble(-2.25d));

    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        bytes, ProtocolMessageType.EXECUTE, 72, "SELECT ?,?,?,?,?,?", source));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(StatusCode.OK, decoder.decode(frame));
    ParameterSet decoded = decoder.parameters();
    assertEquals((short) -7, decoded.smallintAt(0));
    assertEquals(80_000, decoded.integerAt(1));
    assertEquals(Long.MAX_VALUE, decoded.bigintAt(2));
    assertEquals(12_345, decoded.decimalUnscaledAt(3));
    assertEquals(1.5f, decoded.realAt(4));
    assertEquals(-2.25d, decoded.doubleAt(5));

    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        bytes, ProtocolMessageType.EXECUTE, 73, "SELECT ?", singleReal(1.0f)));
    int entry = ProtocolFrameCodec.HEADER_BYTES + 8 + "SELECT ?".length();
    bytes.putLong(entry + 8,
        Integer.toUnsignedLong(Float.floatToRawIntBits(Float.NEGATIVE_INFINITY)));
    assertPayloadInvalid();
  }

  @Test
  void rejectsARequestWhoseSensitivePayloadCannotBeErased() {
    assertEquals(
        StatusCode.OK,
        codec.encodeSqlRequest(
            bytes, ProtocolMessageType.EXECUTE, 9, "SELECT 1", null));
    ByteBuffer readOnly = bytes.asReadOnlyBuffer();
    assertEquals(StatusCode.OK, codec.decode(readOnly, frame));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decoder.decode(frame));
    assertEquals(null, decoder.sql());
    assertEquals(0, decoder.parameters().count());
  }

  @Test
  void erasesRequestWhenSqlTextMaterializationExhaustsMemory() {
    ProtocolUtf8Decoder exhaustedText = new ProtocolUtf8Decoder(
        io.riverdb.base.sql.SqlShapeLimits.MAX_SQL_TEXT_BYTES,
        (source, offset, length) -> {
          throw new OutOfMemoryError("injected SQL text materialization failure");
        });
    ProtocolSqlRequestDecoder exhausted = new ProtocolSqlRequestDecoder(exhaustedText);
    assertEquals(
        StatusCode.OK,
        codec.encodeSqlRequest(
            bytes, ProtocolMessageType.EXECUTE, 10, "SELECT sensitive_value", null));
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, exhausted.decode(frame));
    assertPayloadErased();
    assertEquals(null, exhausted.sql());
    assertEquals(0, exhausted.parameters().count());
  }

  @Test
  void rejectsNonCanonicalEntryHeadersLengthsAndDomains() {
    ParameterSet source = new ParameterSet(1, 0);
    assertEquals(StatusCode.OK, source.appendFixed(SqlTypeDescriptor.BOOLEAN, 1));
    int entry = ProtocolFrameCodec.HEADER_BYTES + 8 + "SELECT ?".length();

    encode(source);
    bytes.putShort(ProtocolFrameCodec.HEADER_BYTES + 6, (short) 1);
    assertPayloadInvalid();

    encode(source);
    bytes.put(entry + 4, (byte) 2);
    assertPayloadInvalid();

    encode(source);
    bytes.put(entry + 5, (byte) 1);
    assertPayloadInvalid();

    encode(source);
    bytes.putShort(entry + 6, (short) 7);
    assertPayloadInvalid();

    encode(source);
    bytes.putLong(entry + 8, 2);
    assertPayloadInvalid();
  }

  @Test
  void rejectsMalformedTextTrailingBytesAndImpossibleCounts() {
    ParameterSet source = new ParameterSet(1, 4);
    assertEquals(
        StatusCode.OK,
        source.appendText(SqlTypeDescriptor.varchar(2), "ok"));
    int entry = ProtocolFrameCodec.HEADER_BYTES + 8 + "SELECT ?".length();

    encode(source);
    bytes.put(entry + 8, (byte) 0xc0);
    assertPayloadInvalid();

    encode(source);
    bytes.put(ProtocolFrameCodec.HEADER_BYTES + 8, (byte) 0xc0);
    assertPayloadInvalid();

    encode(source);
    bytes.putShort(ProtocolFrameCodec.HEADER_BYTES + 4, (short) 513);
    assertPayloadInvalid();

    encode(source);
    bytes.putShort(ProtocolFrameCodec.HEADER_BYTES + 4, (short) 0);
    assertPayloadInvalid();

    encode(source);
    bytes.putShort(ProtocolFrameCodec.HEADER_BYTES + 4, (short) 2);
    assertPayloadInvalid();
  }

  @Test
  void rejectsInvalidNullDescriptorAndEnforcesExactPayloadBound() {
    ParameterSet source = new ParameterSet(1, 0);
    assertEquals(StatusCode.OK, source.appendNull(0));
    int entry = ProtocolFrameCodec.HEADER_BYTES + 8 + "SELECT ?".length();
    encode(source);
    bytes.putInt(entry, Integer.MAX_VALUE);
    assertPayloadInvalid();

    String maximum = "x".repeat(ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES - 8);
    assertEquals(
        StatusCode.OK,
        codec.encodeSqlRequest(
            bytes, ProtocolMessageType.EXECUTE, 1, maximum, null));
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        codec.encodeSqlRequest(
            bytes, ProtocolMessageType.EXECUTE, 1, maximum + "x", null));
  }

  @Test
  void assemblesWideSqlAndRejectsMalformedContinuationBeforeDecode() {
    String sql = "x".repeat(20_000);
    ByteBuffer encoded = ByteBuffer.allocate(64 * 1024);
    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        encoded, ProtocolMessageType.EXECUTE, 81, sql, null));
    assertTrue(encoded.remaining() > ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    ProtocolRequestAssembly assembly = requestAssembly();
    assertEquals(StatusCode.OK, assemble(encoded, assembly));
    assertTrue(assembly.isComplete());
    assertEquals(StatusCode.OK, codec.decodeAssembledRequest(assembly.source(), frame));
    assertEquals(StatusCode.OK, decoder.decode(frame));
    assertEquals(sql, decoder.sql());

    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        encoded, ProtocolMessageType.EXECUTE, 81, sql, null));
    int second = ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
    encoded.putInt(second + ProtocolFrameCodec.HEADER_BYTES + 4,
        ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES - 11);
    assembly.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assemble(encoded, assembly));

    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        encoded, ProtocolMessageType.EXECUTE, 81, sql, null));
    encoded.putInt(second + ProtocolFrameCodec.HEADER_BYTES + 4,
        ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES - 13);
    assembly.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assemble(encoded, assembly));

    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        encoded, ProtocolMessageType.EXECUTE, 81, sql, null));
    encoded.putLong(second + 16, 82);
    assembly.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assemble(encoded, assembly));

    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        encoded, ProtocolMessageType.EXECUTE, 81, sql, null));
    encoded.putInt(second + 8, ProtocolMessageType.BEGIN_QUERY.wireCode());
    assembly.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assemble(encoded, assembly));

    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        encoded, ProtocolMessageType.EXECUTE, 81, sql, null));
    encoded.putInt(second + ProtocolFrameCodec.HEADER_BYTES,
        encoded.getInt(second + ProtocolFrameCodec.HEADER_BYTES) + 1);
    assembly.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assemble(encoded, assembly));

    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        encoded, ProtocolMessageType.EXECUTE, 81, sql, null));
    encoded.putInt(12, encoded.getInt(12) | 4);
    assembly.reset();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assemble(encoded, assembly));

    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        encoded, ProtocolMessageType.EXECUTE, 81, sql, null));
    int fullLimit = encoded.limit();
    encoded.limit(second);
    assembly.reset();
    assertEquals(StatusCode.OK, assemble(encoded, assembly));
    assertTrue(assembly.isActive());
    assertFalse(assembly.isComplete());
    encoded.limit(fullLimit);
    assembly.reset();
    assertEquals(StatusCode.OK, assemble(encoded, assembly));
    ByteBuffer duplicate = encoded.duplicate();
    duplicate.position(second);
    ProtocolFrameHeader duplicateHeader = new ProtocolFrameHeader();
    assertEquals(StatusCode.OK, codec.inspectRequestHeader(duplicate, duplicateHeader));
    duplicate.limit(fullLimit);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        assembly.accept(duplicate.slice(), duplicateHeader));
  }

  @Test
  void requestAssemblyFailsClosedOnDuplicateInvalidAndEmptySegments() {
    String sql = "x".repeat(20_000);
    ByteBuffer encoded = ByteBuffer.allocate(64 * 1024);
    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        encoded, ProtocolMessageType.EXECUTE, 83, sql, null));
    ByteBuffer first = encoded.duplicate();
    first.limit(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    first = first.slice();
    ProtocolFrameHeader firstHeader = new ProtocolFrameHeader();
    assertEquals(StatusCode.OK, codec.inspectRequestHeader(first, firstHeader));
    ProtocolRequestAssembly assembly = requestAssembly();
    assertEquals(StatusCode.OK, assembly.accept(first, firstHeader));
    assertTrue(assembly.isActive());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assembly.accept(first, firstHeader));
    assertFalse(assembly.isActive());

    ByteBuffer second = encoded.duplicate();
    second.position(ProtocolFrameCodec.MAXIMUM_FRAME_BYTES);
    second = second.slice();
    ProtocolFrameHeader secondHeader = new ProtocolFrameHeader();
    assertEquals(StatusCode.OK, codec.inspectRequestHeader(second, secondHeader));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assembly.accept(second, secondHeader));
    assertFalse(assembly.isActive());

    ProtocolFrameHeader unknown = new ProtocolFrameHeader();
    unknown.complete(Integer.MAX_VALUE, 83, firstHeader.payloadBytes(), false, true, false);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assembly.accept(first, unknown));

    encoded.putInt(24, ProtocolResponseSegmenter.SEGMENT_BYTES);
    encoded.putInt(ProtocolFrameCodec.HEADER_BYTES + 8, 0);
    ByteBuffer empty = encoded.duplicate();
    empty.limit(ProtocolFrameCodec.HEADER_BYTES + ProtocolResponseSegmenter.SEGMENT_BYTES);
    empty = empty.slice();
    ProtocolFrameHeader emptyHeader = new ProtocolFrameHeader();
    assertEquals(StatusCode.OK, codec.inspectRequestHeader(empty, emptyHeader));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, assembly.accept(empty, emptyHeader));
    assertFalse(assembly.isActive());
  }

  @Test
  void roundTripsUnsignedMaximumFixedParameterCountAcrossFrames() {
    ParameterSet source = new ParameterSet(ParameterSet.MAXIMUM_PARAMETERS, 0);
    assertEquals(StatusCode.OK, source.reserve(ParameterSet.MAXIMUM_PARAMETERS, 0));
    for (int index = 0; index < ParameterSet.MAXIMUM_PARAMETERS; index++) {
      assertEquals(StatusCode.OK,
          source.appendFixed(SqlTypeDescriptor.BIGINT, index));
    }
    ByteBuffer encoded = ByteBuffer.allocate(2 * 1024 * 1024);
    assertEquals(StatusCode.OK, codec.encodeSqlRequest(
        encoded, ProtocolMessageType.EXECUTE, 82, "SELECT ?", source));
    ProtocolRequestAssembly assembly = requestAssembly();
    assertEquals(StatusCode.OK, assemble(encoded, assembly));
    assertEquals(StatusCode.OK, codec.decodeAssembledRequest(assembly.source(), frame));
    assertEquals(StatusCode.OK, decoder.decode(frame));
    assertEquals(ParameterSet.MAXIMUM_PARAMETERS, decoder.parameters().count());
    assertEquals(ParameterSet.MAXIMUM_PARAMETERS - 1,
        decoder.parameters().valueAt(ParameterSet.MAXIMUM_PARAMETERS - 1));
  }

  private void encode(ParameterSet parameters) {
    assertEquals(
        StatusCode.OK,
        codec.encodeSqlRequest(
            bytes, ProtocolMessageType.EXECUTE, 1, "SELECT ?", parameters));
  }

  private static ParameterSet singleReal(float value) {
    ParameterSet parameters = new ParameterSet(1, 0);
    if (!parameters.appendFixed(
        SqlTypeDescriptor.REAL, SqlApproximateNumeric.realBits(value)).isOk()) {
      throw new AssertionError("finite REAL test parameter rejected");
    }
    return parameters;
  }

  private void assertPayloadInvalid() {
    assertEquals(StatusCode.OK, codec.decode(bytes, frame));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, decoder.decode(frame));
    assertPayloadErased();
  }

  private void assertPayloadErased() {
    for (int index = ProtocolFrameCodec.HEADER_BYTES; index < bytes.limit(); index++) {
      assertEquals(0, bytes.get(index));
    }
  }

  private StatusCode assemble(ByteBuffer encoded, ProtocolRequestAssembly assembly) {
    ProtocolFrameHeader header = new ProtocolFrameHeader();
    int offset = 0;
    while (offset < encoded.limit()) {
      ByteBuffer segment = encoded.duplicate();
      segment.position(offset);
      StatusCode status = codec.inspectRequestHeader(segment, header);
      if (!status.isOk()) return status;
      int frameBytes = ProtocolFrameCodec.HEADER_BYTES + header.payloadBytes();
      segment.limit(offset + frameBytes);
      status = assembly.accept(segment.slice(), header);
      if (!status.isOk()) return status;
      offset += frameBytes;
    }
    return StatusCode.OK;
  }

  private static ProtocolRequestAssembly requestAssembly() {
    long maximum = ProtocolFrameCodec.HEADER_BYTES
        + ProtocolFrameCodec.MAXIMUM_LOGICAL_REQUEST_PAYLOAD_BYTES;
    return new ProtocolRequestAssembly(new ProtocolMemoryBudget(maximum).lease());
  }
}
