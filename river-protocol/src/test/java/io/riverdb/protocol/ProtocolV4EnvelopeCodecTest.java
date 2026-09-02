package io.riverdb.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class ProtocolV4EnvelopeCodecTest {
  @Test
  void roundTripsLengthDelimitedMasksAcrossColumnBoundaries() {
    int[] counts = {8, 9, 63, 64, 65, 255, 256, 1_024, 1_664};
    ProtocolV4Envelope result = new ProtocolV4Envelope();
    for (int columns : counts) {
      int bytes = ProtocolV4EnvelopeCodec.queryMetadataBytes(columns, columns);
      ByteBuffer target = ByteBuffer.allocate(bytes);
      long[] masks = maskWithLastBit(columns);
      assertEquals(StatusCode.OK, ProtocolV4EnvelopeCodec.encode(
          target, 0, ProtocolV4EnvelopeCodec.KIND_QUERY_METADATA,
          bytes, columns, columns, masks, masks.length));
      assertEquals(StatusCode.OK, ProtocolV4EnvelopeCodec.decode(target, 0, result));
      assertEquals(columns, result.elementCount());
      assertEquals(1L << ((columns - 1) & 63),
          result.maskWord((columns - 1) >>> 6));
    }
    assertEquals(0, ProtocolV4EnvelopeCodec.queryMetadataBytes(1_665, 1_665));
  }

  @Test
  void separatesSqlMetadataAndPackedRowBounds() {
    int parameters = 255;
    int maximumSql = ProtocolV4EnvelopeCodec.MAXIMUM_SQL_REQUEST_BYTES
        - ProtocolV4EnvelopeCodec.HEADER_BYTES - bitmapBytes(parameters)
        - parameters * 12 - ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_VALUE_BYTES;
    assertEquals(ProtocolV4EnvelopeCodec.MAXIMUM_SQL_REQUEST_BYTES,
        ProtocolV4EnvelopeCodec.sqlRequestBytes(
            maximumSql, parameters, ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_VALUE_BYTES));
    assertEquals(0, ProtocolV4EnvelopeCodec.sqlRequestBytes(
        maximumSql + 1, parameters, ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_VALUE_BYTES));

    int columns = 1_664;
    int rowOverhead = ProtocolV4EnvelopeCodec.HEADER_BYTES + bitmapBytes(columns)
        + columns * 12;
    int maximumValues = ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_ROW_BYTES - rowOverhead;
    assertEquals(ProtocolV4EnvelopeCodec.MAXIMUM_PACKED_ROW_BYTES,
        ProtocolV4EnvelopeCodec.packedRowBytes(columns, maximumValues));
    assertEquals(0, ProtocolV4EnvelopeCodec.packedRowBytes(columns, maximumValues + 1));
  }

  @Test
  void rejectsNoncanonicalBitmapAndLeavesResultUnpublished() {
    int columns = 65;
    int bytes = ProtocolV4EnvelopeCodec.packedRowBytes(columns, 0);
    ByteBuffer target = ByteBuffer.allocate(bytes);
    long[] masks = new long[2];
    assertEquals(StatusCode.OK, ProtocolV4EnvelopeCodec.encode(
        target, 0, ProtocolV4EnvelopeCodec.KIND_PACKED_ROW,
        bytes, columns, 0, masks, masks.length));
    target.put(ProtocolV4EnvelopeCodec.HEADER_BYTES + bitmapBytes(columns) - 1, (byte) 2);
    ProtocolV4Envelope result = new ProtocolV4Envelope();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        ProtocolV4EnvelopeCodec.decode(target, 0, result));
    assertEquals(0, result.elementCount());
  }

  @Test
  void rejectsMaskShapeBeforeWriting() {
    int bytes = ProtocolV4EnvelopeCodec.packedRowBytes(64, 0);
    ByteBuffer target = ByteBuffer.allocate(bytes);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, ProtocolV4EnvelopeCodec.encode(
        target, 0, ProtocolV4EnvelopeCodec.KIND_PACKED_ROW,
        bytes, 64, 0, new long[] {0, 1}, 2));
    assertEquals(0, target.getLong(0));
  }

  private static long[] maskWithLastBit(int columns) {
    long[] result = new long[(columns + 63) >>> 6];
    result[(columns - 1) >>> 6] = 1L << ((columns - 1) & 63);
    return result;
  }

  private static int bitmapBytes(int elements) {
    return (elements + 7) >>> 3;
  }
}
