package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class ProtocolV4EnvelopeCodecTest {
  @Test
  void carriesFourMaskWordsThroughColumnTwoHundredFiftyFour() {
    int names = 255 * ProtocolFrameCodec.MAXIMUM_COLUMN_NAME_BYTES;
    int bytes = ProtocolV4EnvelopeCodec.queryMetadataBytes(255, names);
    assertTrue(bytes > 16 * 1024);
    assertTrue(bytes <= ProtocolV4EnvelopeCodec.MAXIMUM_QUERY_METADATA_BYTES);
    ByteBuffer target = ByteBuffer.allocate(bytes + 8);
    long first = 1L << 63;
    long second = 1L | 1L << 63;
    long third = 1L | 1L << 63;
    long fourth = 1L | 1L << 62;
    assertEquals(
        StatusCode.OK,
        ProtocolV4EnvelopeCodec.encode(
            target,
            8,
            ProtocolV4EnvelopeCodec.KIND_QUERY_METADATA,
            bytes,
            255,
            names,
            first,
            second,
            third,
            fourth));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "52495650525634000000000400000002000045fa000000ff00003fc000000000"
                + "8000000000000000800000000000000180000000000000014000000000000001"),
        Arrays.copyOfRange(target.array(), 8, 8 + ProtocolV4EnvelopeCodec.HEADER_BYTES));
    ByteBuffer encoded = bounded(target, 8, bytes);
    ProtocolV4Envelope result = new ProtocolV4Envelope();
    assertEquals(StatusCode.OK, ProtocolV4EnvelopeCodec.decode(encoded, 0, result));
    assertEquals(255, result.elementCount());
    assertEquals(fourth, result.maskWord(3));
  }

  @Test
  void separatesSqlMetadataAndPackedRowBounds() {
    int maximumSql = ProtocolV4EnvelopeCodec.MAXIMUM_SQL_REQUEST_BYTES
        - ProtocolV4EnvelopeCodec.HEADER_BYTES
        - 255 * 12
        - ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_VALUE_BYTES;
    assertEquals(
        ProtocolV4EnvelopeCodec.MAXIMUM_SQL_REQUEST_BYTES,
        ProtocolV4EnvelopeCodec.sqlRequestBytes(
            maximumSql, 255, ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_VALUE_BYTES));
    assertEquals(
        0,
        ProtocolV4EnvelopeCodec.sqlRequestBytes(
            maximumSql + 1, 255, ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_VALUE_BYTES));
    assertEquals(0, ProtocolV4EnvelopeCodec.queryMetadataBytes(256, 256));
    assertEquals(
        0,
        ProtocolV4EnvelopeCodec.packedRowBytes(
            255, ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_VALUE_BYTES + 1));
    assertTrue(
        ProtocolV4EnvelopeCodec.packedRowBytes(
            255, ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_VALUE_BYTES)
            <= ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_ROW_BYTES);

    int requestBytes = ProtocolV4EnvelopeCodec.sqlRequestBytes(3, 2, 16);
    ByteBuffer request = ByteBuffer.allocate(requestBytes);
    assertEquals(
        StatusCode.OK,
        ProtocolV4EnvelopeCodec.encode(
            request,
            0,
            ProtocolV4EnvelopeCodec.KIND_SQL_REQUEST,
            requestBytes,
            2,
            3,
            2,
            0,
            0,
            0));
    ProtocolV4Envelope result = new ProtocolV4Envelope();
    assertEquals(StatusCode.OK, ProtocolV4EnvelopeCodec.decode(request, 0, result));
    assertEquals(ProtocolV4EnvelopeCodec.KIND_SQL_REQUEST, result.kind());
    assertEquals(3, result.prefixBytes());

    int rowBytes = ProtocolV4EnvelopeCodec.packedRowBytes(
        255, ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_VALUE_BYTES);
    ByteBuffer row = ByteBuffer.allocate(rowBytes);
    assertEquals(
        StatusCode.OK,
        ProtocolV4EnvelopeCodec.encode(
            row,
            0,
            ProtocolV4EnvelopeCodec.KIND_PACKED_ROW,
            rowBytes,
            255,
            ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_VALUE_BYTES,
            0,
            0,
            0,
            1L << 62));
    assertEquals(StatusCode.OK, ProtocolV4EnvelopeCodec.decode(row, 0, result));
    assertEquals(255, result.elementCount());
    assertEquals(1L << 62, result.maskWord(3));
  }

  @Test
  void rejectsOldVersionsReservedBitAndMasksBeyondTheDeclaredShape() {
    int bytes = ProtocolV4EnvelopeCodec.packedRowBytes(255, 0);
    ByteBuffer target = ByteBuffer.allocate(bytes);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        ProtocolV4EnvelopeCodec.encode(
            target,
            0,
            ProtocolV4EnvelopeCodec.KIND_PACKED_ROW,
            bytes,
            255,
            0,
            0,
            0,
            0,
            Long.MIN_VALUE));
    bytes = ProtocolV4EnvelopeCodec.packedRowBytes(64, 0);
    target = ByteBuffer.allocate(bytes);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        ProtocolV4EnvelopeCodec.encode(
            target,
            0,
            ProtocolV4EnvelopeCodec.KIND_PACKED_ROW,
            bytes,
            64,
            0,
            0,
            1,
            0,
            0));
    assertEquals(
        StatusCode.OK,
        ProtocolV4EnvelopeCodec.encode(
            target,
            0,
            ProtocolV4EnvelopeCodec.KIND_PACKED_ROW,
            bytes,
            64,
            0,
            0,
            0,
            0,
            0));
    target.putInt(8, 3);
    ProtocolV4Envelope result = new ProtocolV4Envelope();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, ProtocolV4EnvelopeCodec.decode(target, 0, result));
    assertEquals(0, result.elementCount());
  }

  private static ByteBuffer bounded(ByteBuffer source, int offset, int bytes) {
    ByteBuffer result = source.duplicate();
    result.position(offset);
    result.limit(offset + bytes);
    return result.slice();
  }
}
