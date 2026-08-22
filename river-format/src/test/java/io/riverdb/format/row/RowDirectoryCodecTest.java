package io.riverdb.format.row;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class RowDirectoryCodecTest {
  @Test
  void directoryBytesAreCanonicalLittleEndian() {
    ByteBuffer logical = ByteBuffer.allocate(RowDirectoryCodec.LOGICAL_RECORD_BYTES)
        .order(ByteOrder.BIG_ENDIAN);
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.encodeLogical(logical, 1, 2, 3, true, new CRC32C()));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "4f52474f4c564952010000000100000002000000000000000300000000000000"
                + "0100000000000000f7600f38089ff0c7"),
        logical.array());

    ByteBuffer version = ByteBuffer.allocate(RowDirectoryCodec.VERSION_RECORD_BYTES)
        .order(ByteOrder.BIG_ENDIAN);
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.encodeVersion(
            version, 1, 3, 2, 0, 4, 5, 6, 7, 8, false, new CRC32C()));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "4e53524556564952010000000100000003000000000000000200000000000000"
                + "0000000000000000040000000000000005000000000000000600000000000000"
                + "07000000080000000000000000000000d18ac08c2e753f73"),
        version.array());
  }

  @Test
  void keepsLogicalAndVersionIdentityDistinctAtLongBoundary() {
    long logicalId = Long.MAX_VALUE - 1;
    long versionId = Long.MAX_VALUE;
    ByteBuffer logical = ByteBuffer.allocate(RowDirectoryCodec.LOGICAL_RECORD_BYTES);
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.encodeLogical(logical, 17, logicalId, versionId, true, new CRC32C()));
    LogicalRowRecord logicalResult = new LogicalRowRecord();
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.decodeLogical(logical, logicalId, logicalResult, new CRC32C()));
    assertEquals(logicalId, logicalResult.logicalRowId());
    assertEquals(versionId, logicalResult.headVersionId());
    assertTrue(logicalResult.keyless());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RowDirectoryCodec.decodeLogical(logical, 0, logicalResult, new CRC32C()));
    assertEquals(0, logicalResult.logicalRowId());
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.decodeLogical(logical, logicalId, logicalResult, new CRC32C()));

    ByteBuffer version = ByteBuffer.allocate(RowDirectoryCodec.VERSION_RECORD_BYTES);
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.encodeVersion(
            version,
            17,
            versionId,
            logicalId,
            Integer.MAX_VALUE + 9L,
            Long.MAX_VALUE,
            Long.MAX_VALUE - 2,
            Long.MAX_VALUE - 3,
            RowDirectoryCodec.MAXIMUM_HEAP_SLOT_ID,
            Integer.MAX_VALUE,
            false,
            new CRC32C()));
    VersionRecord versionResult = new VersionRecord();
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.decodeVersion(version, versionId, versionResult, new CRC32C()));
    assertEquals(versionId, versionResult.versionId());
    assertEquals(logicalId, versionResult.logicalRowId());
    assertEquals(Integer.MAX_VALUE + 9L, versionResult.previousVersionId());
    assertEquals(Long.MAX_VALUE - 2, versionResult.pageNumber());
    assertEquals(Long.MAX_VALUE - 3, versionResult.pageGeneration());
    assertEquals(RowDirectoryCodec.MAXIMUM_HEAP_SLOT_ID, versionResult.heapSlotId());
    assertEquals(Integer.MAX_VALUE, versionResult.slotGeneration());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RowDirectoryCodec.decodeVersion(version, 0, versionResult, new CRC32C()));
    assertEquals(0, versionResult.versionId());
  }

  @Test
  void rejectsInvalidDomainsCorruptionAndTruncation() {
    LogicalRowRecord logicalResult = new LogicalRowRecord();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RowDirectoryCodec.decodeLogical(
            ByteBuffer.allocate(RowDirectoryCodec.LOGICAL_RECORD_BYTES),
            0,
            logicalResult,
            new CRC32C()));
    VersionRecord versionResult = new VersionRecord();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RowDirectoryCodec.decodeVersion(
            ByteBuffer.allocate(RowDirectoryCodec.VERSION_RECORD_BYTES),
            0,
            versionResult,
            new CRC32C()));

    ByteBuffer version = ByteBuffer.allocate(RowDirectoryCodec.VERSION_RECORD_BYTES);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RowDirectoryCodec.encodeVersion(
            version, 1, 9, 3, 9, 1, 1, 1, 1, 1, false, new CRC32C()));
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.encodeVersion(
            version, 1, 9, 3, 0, 1, 1, 1, 1, 1, true, new CRC32C()));
    io.riverdb.format.FormatBytes.putLong(version, 48, 0);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeVersion(version, 9, new VersionRecord(), new CRC32C()));
    version.limit(version.limit() - 1);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeVersion(version, 9, new VersionRecord(), new CRC32C()));
  }
}
