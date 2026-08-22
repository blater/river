package io.riverdb.format.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class CatalogContinuationCodecTest {
  @Test
  void publishesCompositeMaximumColumnHeaderInReservedKeyRange() {
    long first = CatalogContinuationKey.first(17);
    ByteBuffer bytes = ByteBuffer.allocate(CatalogHeaderCodec.BYTES);
    assertEquals(
        StatusCode.OK,
        CatalogHeaderCodec.encode(
            bytes,
            CatalogHeaderCodec.KIND_TABLE,
            9,
            CatalogHeaderCodec.COMPOSITE_KEY,
            4,
            255,
            Long.MAX_VALUE,
            first,
            3,
            8_001,
            new CRC32C()));
    CatalogHeader decoded = new CatalogHeader();
    assertEquals(StatusCode.OK, CatalogHeaderCodec.decode(bytes, decoded, new CRC32C()));
    assertEquals(255, decoded.columnCount());
    assertEquals(Long.MAX_VALUE, decoded.generation());
    assertEquals(4, decoded.keyArity());
    assertTrue(CatalogContinuationKey.validRange(decoded.firstSegmentKey(), 3));
    assertEquals(first + 2, CatalogContinuationKey.at(first, 2, 3));
    assertEquals(0, CatalogContinuationKey.at(first, 3, 3));
  }

  @Test
  void headerBytesAreLittleEndianAndIndependentOfCarrierOrderAndPosition() {
    ByteBuffer big = ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN);
    ByteBuffer little = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
    big.position(8);
    little.position(8);
    long first = CatalogContinuationKey.first(0);
    assertEquals(
        StatusCode.OK,
        CatalogHeaderCodec.encode(
            big, CatalogHeaderCodec.KIND_TABLE, 1, CatalogHeaderCodec.SIMPLE_KEY,
            1, 1, 2, first, 1, 3, new CRC32C()));
    assertEquals(
        StatusCode.OK,
        CatalogHeaderCodec.encode(
            little, CatalogHeaderCodec.KIND_TABLE, 1, CatalogHeaderCodec.SIMPLE_KEY,
            1, 1, 2, first, 1, 3, new CRC32C()));
    assertEquals(0, Arrays.compare(big.array(), little.array()));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "4448544143564952010000000100000001000000010000000100000001000000"
                + "0200000000000000000000000000008001000000030000003727bce0c8d8431f"),
        Arrays.copyOfRange(big.array(), 8, 72));
    assertEquals(0x44, Byte.toUnsignedInt(big.get(8)));
    assertEquals(0x48, Byte.toUnsignedInt(big.get(9)));
    assertEquals(1, Byte.toUnsignedInt(big.get(16)));
    assertEquals(2, Byte.toUnsignedInt(big.get(40)));
    assertEquals(0x80, Byte.toUnsignedInt(big.get(55)));

    big.position(8);
    CatalogHeader decoded = new CatalogHeader();
    assertEquals(StatusCode.OK, CatalogHeaderCodec.decode(big, decoded, new CRC32C()));
    assertEquals(2, decoded.generation());
  }

  @Test
  void segmentChecksumCoversHeaderAndPayloadAndRejectsTruncation() {
    ByteBuffer payload = ByteBuffer.wrap(new byte[] {1, 3, 3, 7});
    ByteBuffer bytes = ByteBuffer.allocate(CatalogSegmentCodec.MAXIMUM_RECORD_BYTES);
    assertEquals(
        StatusCode.OK,
        CatalogSegmentCodec.encode(
            bytes,
            CatalogSegmentCodec.KIND_SCHEMA,
            11,
            1,
            3,
            19,
            payload,
            new CRC32C()));
    CatalogSegment decoded = new CatalogSegment();
    assertEquals(StatusCode.OK, CatalogSegmentCodec.decode(bytes, decoded, new CRC32C()));
    assertEquals(4, decoded.payloadBytes());
    assertEquals(1, decoded.ordinal());

    int payloadIndex = CatalogSegmentCodec.HEADER_BYTES + 2;
    bytes.put(payloadIndex, (byte) (bytes.get(payloadIndex) ^ 1));
    assertEquals(StatusCode.CORRUPTION, CatalogSegmentCodec.decode(bytes, decoded, new CRC32C()));
    bytes.put(payloadIndex, (byte) (bytes.get(payloadIndex) ^ 1));
    bytes.limit(bytes.limit() - 1);
    assertEquals(StatusCode.CORRUPTION, CatalogSegmentCodec.decode(bytes, decoded, new CRC32C()));
  }

  @Test
  void segmentBytesAreCanonicalLittleEndian() {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogSegmentCodec.MAXIMUM_RECORD_BYTES)
        .order(ByteOrder.BIG_ENDIAN);
    assertEquals(
        StatusCode.OK,
        CatalogSegmentCodec.encode(
            bytes,
            CatalogSegmentCodec.KIND_SCHEMA,
            1,
            0,
            1,
            2,
            ByteBuffer.wrap(new byte[] {1, 2, 3}),
            new CRC32C()));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "4753544143564952010000000100000001000000000000000100000003000000"
                + "0200000000000000bc92a8d4436d572b010203"),
        Arrays.copyOf(bytes.array(), bytes.limit()));
  }

  @Test
  void rejectsOldVersionsAndInvalidKeyDomains() {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogHeaderCodec.BYTES);
    assertEquals(
        StatusCode.OK,
        CatalogHeaderCodec.encode(
            bytes,
            CatalogHeaderCodec.KIND_TABLE,
            1,
            CatalogHeaderCodec.KEYLESS,
            0,
            21,
            1,
            CatalogContinuationKey.first(0),
            1,
            12,
            new CRC32C()));
    FormatBytes.putInt(bytes, 8, 0);
    int checksum = FormatBytes.checksum(bytes, 0, CatalogHeaderCodec.CHECKSUM_OFFSET, new CRC32C());
    FormatBytes.putInt(bytes, CatalogHeaderCodec.CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(bytes, CatalogHeaderCodec.COMPLEMENT_OFFSET, ~checksum);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogHeaderCodec.decode(bytes, new CatalogHeader(), new CRC32C()));
    assertEquals(0, CatalogContinuationKey.first(-1));
    assertEquals(0, CatalogContinuationKey.first(Long.MAX_VALUE));
  }

  @Test
  void rejectsUnknownKindsEmptyPayloadsAndInvalidBuffersWithoutThrowing() {
    ByteBuffer target = ByteBuffer.allocate(CatalogHeaderCodec.BYTES);
    target.limit(CatalogHeaderCodec.BYTES - 1);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        CatalogHeaderCodec.encode(
            target,
            CatalogHeaderCodec.KIND_TABLE,
            1,
            CatalogHeaderCodec.SIMPLE_KEY,
            1,
            1,
            1,
            CatalogContinuationKey.first(0),
            1,
            1,
            new CRC32C()));
    ByteBuffer readOnly = ByteBuffer.allocate(CatalogHeaderCodec.BYTES).asReadOnlyBuffer();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        CatalogHeaderCodec.encode(
            readOnly,
            CatalogHeaderCodec.KIND_TABLE,
            1,
            CatalogHeaderCodec.SIMPLE_KEY,
            1,
            1,
            1,
            CatalogContinuationKey.first(0),
            1,
            1,
            new CRC32C()));

    ByteBuffer segment = ByteBuffer.allocate(CatalogSegmentCodec.HEADER_BYTES + 1);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        CatalogSegmentCodec.encode(
            segment, 7, 1, 0, 1, 1, ByteBuffer.wrap(new byte[] {1}), new CRC32C()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        CatalogSegmentCodec.encode(
            segment,
            CatalogSegmentCodec.KIND_SCHEMA,
            1,
            0,
            1,
            1,
            ByteBuffer.allocate(0),
            new CRC32C()));

    ByteBuffer impossible = ByteBuffer.allocate(CatalogHeaderCodec.BYTES);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        CatalogHeaderCodec.encode(
            impossible,
            CatalogHeaderCodec.KIND_TABLE,
            1,
            CatalogHeaderCodec.SIMPLE_KEY,
            1,
            1,
            1,
            CatalogContinuationKey.first(0),
            2,
            1,
            new CRC32C()));

    assertEquals(
        StatusCode.OK,
        CatalogHeaderCodec.encode(
            impossible,
            CatalogHeaderCodec.KIND_TABLE,
            1,
            CatalogHeaderCodec.SIMPLE_KEY,
            1,
            1,
            1,
            CatalogContinuationKey.first(0),
            2,
            2,
            new CRC32C()));
    FormatBytes.putInt(impossible, 52, 1);
    int value = FormatBytes.checksum(
        impossible, 0, CatalogHeaderCodec.CHECKSUM_OFFSET, new CRC32C());
    FormatBytes.putInt(impossible, CatalogHeaderCodec.CHECKSUM_OFFSET, value);
    FormatBytes.putInt(impossible, CatalogHeaderCodec.COMPLEMENT_OFFSET, ~value);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogHeaderCodec.decode(impossible, new CatalogHeader(), new CRC32C()));
  }

  @Test
  void reservesMonotonicRangesAndRejectsExhaustion() {
    CatalogContinuationReservation first = new CatalogContinuationReservation();
    CatalogContinuationReservation second = new CatalogContinuationReservation();
    assertEquals(StatusCode.OK, CatalogContinuationKey.reserve(0, 3, first));
    assertEquals(
        StatusCode.OK, CatalogContinuationKey.reserve(first.nextAllocation(), 2, second));
    assertEquals(first.firstKey() + 3, second.firstKey());
    assertEquals(5, second.nextAllocation());
    assertEquals(1, CatalogContinuationKey.SPACE);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        CatalogContinuationKey.reserve(Long.MAX_VALUE, 1, second));
    assertEquals(0, second.firstKey());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        CatalogContinuationKey.reserve(0, CatalogHeaderCodec.MAXIMUM_SEGMENTS + 1, second));
    assertFalse(CatalogContinuationKey.validRange(
        CatalogContinuationKey.LAST, CatalogHeaderCodec.MAXIMUM_SEGMENTS));
  }

  @Test
  void persistsContinuationAllocationAuthorityThroughExhaustion() {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogAllocationWatermarkCodec.BYTES)
        .order(ByteOrder.BIG_ENDIAN);
    assertEquals(
        StatusCode.OK,
        CatalogAllocationWatermarkCodec.encode(bytes, 5, new CRC32C()));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "4d57544143564952010000002800000005000000000000000000000000000000"
                + "c4aea33a3b515cc5"),
        bytes.array());
    CatalogAllocationWatermark decoded = new CatalogAllocationWatermark();
    assertEquals(
        StatusCode.OK,
        CatalogAllocationWatermarkCodec.decode(bytes, decoded, new CRC32C()));
    assertTrue(decoded.isAvailable());
    assertEquals(5, decoded.nextAllocation());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        CatalogAllocationWatermarkCodec.decode(null, decoded, new CRC32C()));
    assertFalse(decoded.isAvailable());
    assertEquals(
        StatusCode.OK,
        CatalogAllocationWatermarkCodec.decode(bytes, decoded, new CRC32C()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        CatalogAllocationWatermarkCodec.decode(bytes, decoded, null));
    assertFalse(decoded.isAvailable());

    assertEquals(
        StatusCode.OK,
        CatalogAllocationWatermarkCodec.encode(
            bytes, CatalogContinuationKey.MAXIMUM_ALLOCATION, new CRC32C()));
    assertEquals(
        StatusCode.OK,
        CatalogAllocationWatermarkCodec.decode(bytes, decoded, new CRC32C()));
    assertEquals(CatalogContinuationKey.MAXIMUM_ALLOCATION, decoded.nextAllocation());
    CatalogContinuationReservation reservation = new CatalogContinuationReservation();
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        CatalogContinuationKey.reserve(decoded.nextAllocation(), 1, reservation));

    FormatBytes.putLong(bytes, 16, CatalogContinuationKey.MAXIMUM_ALLOCATION + 1);
    int checksum = FormatBytes.checksum(
        bytes, 0, CatalogAllocationWatermarkCodec.BYTES - 8, new CRC32C());
    FormatBytes.putInt(bytes, CatalogAllocationWatermarkCodec.BYTES - 8, checksum);
    FormatBytes.putInt(bytes, CatalogAllocationWatermarkCodec.BYTES - 4, ~checksum);
    assertEquals(
        StatusCode.CORRUPTION,
        CatalogAllocationWatermarkCodec.decode(bytes, decoded, new CRC32C()));
    assertFalse(decoded.isAvailable());
  }

  @Test
  void assemblyRejectsMissingDuplicateWrongGenerationAndWrongNamespace() {
    long first = CatalogContinuationKey.first(9);
    ByteBuffer headerBytes = ByteBuffer.allocate(CatalogHeaderCodec.BYTES);
    assertEquals(
        StatusCode.OK,
        CatalogHeaderCodec.encode(
            headerBytes,
            CatalogHeaderCodec.KIND_TABLE,
            7,
            CatalogHeaderCodec.COMPOSITE_KEY,
            2,
            21,
            4,
            first,
            2,
            4,
            new CRC32C()));
    CatalogHeader header = new CatalogHeader();
    assertEquals(StatusCode.OK, CatalogHeaderCodec.decode(headerBytes, header, new CRC32C()));

    CatalogSegment firstSegment = segment(7, 0, 2, 4, new byte[] {1, 2});
    CatalogSegment secondSegment = segment(7, 1, 2, 4, new byte[] {3, 4});
    CatalogAssemblyValidator assembly = new CatalogAssemblyValidator();
    assertEquals(StatusCode.OK, assembly.begin(header));
    assertEquals(
        StatusCode.OK,
        assembly.accept(CatalogContinuationKey.SPACE, first, firstSegment));
    assertFalse(assembly.complete());
    assertEquals(
        StatusCode.CORRUPTION,
        assembly.accept(CatalogContinuationKey.SPACE, first, firstSegment));
    assertFalse(assembly.complete());

    assertEquals(StatusCode.OK, assembly.begin(header));
    assertEquals(StatusCode.CORRUPTION, assembly.accept(0, first, firstSegment));
    assertFalse(assembly.complete());
    assertEquals(StatusCode.OK, assembly.begin(header));
    assertEquals(
        StatusCode.OK,
        assembly.accept(CatalogContinuationKey.SPACE, first, firstSegment));
    assertEquals(
        StatusCode.OK,
        assembly.accept(CatalogContinuationKey.SPACE, first + 1, secondSegment));
    assertTrue(assembly.complete());

    CatalogSegment wrongGeneration = segment(7, 1, 2, 5, new byte[] {3, 4});
    assertEquals(StatusCode.OK, assembly.begin(header));
    assertEquals(
        StatusCode.CORRUPTION,
        assembly.accept(CatalogContinuationKey.SPACE, first + 1, wrongGeneration));
    assertFalse(assembly.complete());

    assertEquals(StatusCode.OK, assembly.begin(header));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        assembly.accept(CatalogContinuationKey.SPACE, first, null));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        assembly.accept(CatalogContinuationKey.SPACE, first, firstSegment));
    assertFalse(assembly.complete());

    CatalogSegment firstConstraint = segmentOfKind(
        CatalogSegmentCodec.KIND_CONSTRAINT, 7, 0, 2, 4, new byte[] {1, 2});
    CatalogSegment secondConstraint = segmentOfKind(
        CatalogSegmentCodec.KIND_CONSTRAINT, 7, 1, 2, 4, new byte[] {3, 4});
    assertEquals(StatusCode.OK, assembly.begin(header));
    assertEquals(
        StatusCode.OK,
        assembly.accept(CatalogContinuationKey.SPACE, first, firstConstraint));
    assertEquals(
        StatusCode.OK,
        assembly.accept(CatalogContinuationKey.SPACE, first + 1, secondConstraint));
    assertFalse(assembly.complete());
  }

  @Test
  void assemblesMaximumThirtyTwoSegmentsIncludingOrdinal31() {
    int count = CatalogHeaderCodec.MAXIMUM_SEGMENTS;
    long first = CatalogContinuationKey.first(100);
    ByteBuffer headerBytes = ByteBuffer.allocate(CatalogHeaderCodec.BYTES);
    assertEquals(
        StatusCode.OK,
        CatalogHeaderCodec.encode(
            headerBytes,
            CatalogHeaderCodec.KIND_TABLE,
            3,
            CatalogHeaderCodec.SIMPLE_KEY,
            1,
            255,
            8,
            first,
            count,
            count,
            new CRC32C()));
    CatalogHeader header = new CatalogHeader();
    assertEquals(StatusCode.OK, CatalogHeaderCodec.decode(headerBytes, header, new CRC32C()));
    CatalogAssemblyValidator assembly = new CatalogAssemblyValidator();
    assertEquals(StatusCode.OK, assembly.begin(header));
    for (int ordinal = 0; ordinal < count; ordinal++) {
      CatalogSegment segment = segment(3, ordinal, count, 8, new byte[] {(byte) ordinal});
      assertEquals(
          StatusCode.OK,
          assembly.accept(CatalogContinuationKey.SPACE, first + ordinal, segment));
    }
    assertTrue(assembly.complete());
  }

  @Test
  void failedDecodeErasesPreviouslyPublishedMetadata() {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogHeaderCodec.BYTES);
    assertEquals(
        StatusCode.OK,
        CatalogHeaderCodec.encode(
            bytes,
            CatalogHeaderCodec.KIND_TABLE,
            1,
            CatalogHeaderCodec.SIMPLE_KEY,
            1,
            1,
            1,
            CatalogContinuationKey.first(0),
            1,
            1,
            new CRC32C()));
    CatalogHeader decoded = new CatalogHeader();
    assertEquals(StatusCode.OK, CatalogHeaderCodec.decode(bytes, decoded, new CRC32C()));
    assertEquals(1, decoded.tableId());
    bytes.put(0, (byte) (bytes.get(0) ^ 1));
    assertEquals(StatusCode.CORRUPTION, CatalogHeaderCodec.decode(bytes, decoded, new CRC32C()));
    assertEquals(0, decoded.tableId());
    assertEquals(0, decoded.generation());
  }

  private static CatalogSegment segment(
      int tableId, int ordinal, int count, long generation, byte[] payload) {
    return segmentOfKind(
        CatalogSegmentCodec.KIND_SCHEMA, tableId, ordinal, count, generation, payload);
  }

  private static CatalogSegment segmentOfKind(
      int kind, int tableId, int ordinal, int count, long generation, byte[] payload) {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogSegmentCodec.MAXIMUM_RECORD_BYTES);
    assertEquals(
        StatusCode.OK,
        CatalogSegmentCodec.encode(
            bytes,
            kind,
            tableId,
            ordinal,
            count,
            generation,
            ByteBuffer.wrap(payload),
            new CRC32C()));
    CatalogSegment result = new CatalogSegment();
    assertEquals(StatusCode.OK, CatalogSegmentCodec.decode(bytes, result, new CRC32C()));
    return result;
  }
}
