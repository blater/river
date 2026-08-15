package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
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
        source.appendText(SqlTypeDescriptor.varchar(8), "A\u00e9\ud83d\ude00"));
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

  private void encode(ParameterSet parameters) {
    assertEquals(
        StatusCode.OK,
        codec.encodeSqlRequest(
            bytes, ProtocolMessageType.EXECUTE, 1, "SELECT ?", parameters));
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
}
