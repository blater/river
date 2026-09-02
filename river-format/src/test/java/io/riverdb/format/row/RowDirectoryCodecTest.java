package io.riverdb.format.row;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.catalog.CatalogKeyspace;
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
            "4f52474f4c564952020000000100000001000000000000000200000000000000"
                + "030000000000000079d7acd58628532a"),
        logical.array());

    ByteBuffer version = ByteBuffer.allocate(RowDirectoryCodec.VERSION_RECORD_BYTES)
        .order(ByteOrder.BIG_ENDIAN);
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.encodeVersion(
            version, 1, 3, 2, 0, 4, 5, 6, 7, 8, false, new CRC32C()));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "4e53524556564952020000000000000001000000000000000300000000000000"
                + "0200000000000000000000000000000004000000000000000500000000000000"
                + "06000000000000000700000008000000ded2756b212d8a94"),
        version.array());
  }

  @Test
  void keepsLogicalAndHeapVersionIdentityDistinctAtLongBoundary() {
    long objectId = CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID;
    long logicalRowId = Long.MAX_VALUE - 1;
    long heapVersionId = Long.MAX_VALUE;
    ByteBuffer logical = ByteBuffer.allocate(RowDirectoryCodec.LOGICAL_RECORD_BYTES);
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.encodeLogical(
            logical, objectId, logicalRowId, heapVersionId, true, new CRC32C()));
    LogicalRowRecord logicalResult = new LogicalRowRecord();
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.decodeLogical(
            logical, logicalRowId, logicalResult, new CRC32C()));
    assertEquals(objectId, logicalResult.objectId());
    assertEquals(logicalRowId, logicalResult.logicalRowId());
    assertEquals(heapVersionId, logicalResult.headHeapVersionId());
    assertTrue(logicalResult.keyless());

    ByteBuffer version = ByteBuffer.allocate(RowDirectoryCodec.VERSION_RECORD_BYTES);
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.encodeVersion(
            version,
            objectId,
            heapVersionId,
            logicalRowId,
            heapVersionId - 1,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            RowDirectoryCodec.MAXIMUM_HEAP_SLOT_ID,
            Integer.MAX_VALUE,
            false,
            new CRC32C()));
    VersionRecord versionResult = new VersionRecord();
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.decodeVersion(
            version, heapVersionId, versionResult, new CRC32C()));
    assertEquals(objectId, versionResult.objectId());
    assertEquals(heapVersionId, versionResult.heapVersionId());
    assertEquals(logicalRowId, versionResult.logicalRowId());
    assertEquals(heapVersionId - 1, versionResult.previousHeapVersionId());
    assertEquals(Long.MAX_VALUE, versionResult.commitSequence());
    assertEquals(Long.MAX_VALUE, versionResult.pageNumber());
    assertEquals(Long.MAX_VALUE, versionResult.pageGeneration());
    assertEquals(RowDirectoryCodec.MAXIMUM_HEAP_SLOT_ID, versionResult.heapSlotId());
    assertEquals(Integer.MAX_VALUE, versionResult.slotGeneration());
    assertFalse(versionResult.deleted());
  }

  @Test
  void rejectsNonpositiveLongIdentitiesBeforeEncoding() {
    assertLogicalEncodeRejected(0, 2, 3);
    assertLogicalEncodeRejected(-1, 2, 3);
    assertLogicalEncodeRejected(CatalogKeyspace.OBJECT_ID_EXHAUSTED, 2, 3);
    assertLogicalEncodeRejected(1, 0, 3);
    assertLogicalEncodeRejected(1, -1, 3);
    assertLogicalEncodeRejected(1, 2, 0);
    assertLogicalEncodeRejected(1, 2, -1);

    assertVersionEncodeRejected(0, 9, 3, 0, 1, 1, 1);
    assertVersionEncodeRejected(-1, 9, 3, 0, 1, 1, 1);
    assertVersionEncodeRejected(
        CatalogKeyspace.OBJECT_ID_EXHAUSTED, 9, 3, 0, 1, 1, 1);
    assertVersionEncodeRejected(1, 0, 3, 0, 1, 1, 1);
    assertVersionEncodeRejected(1, -1, 3, 0, 1, 1, 1);
    assertVersionEncodeRejected(1, 9, 0, 0, 1, 1, 1);
    assertVersionEncodeRejected(1, 9, -1, 0, 1, 1, 1);
    assertVersionEncodeRejected(1, 9, 3, -1, 1, 1, 1);
    assertVersionEncodeRejected(1, 9, 3, 0, 0, 1, 1);
    assertVersionEncodeRejected(1, 9, 3, 0, -1, 1, 1);
    assertVersionEncodeRejected(1, 9, 3, 0, 1, 0, 1);
    assertVersionEncodeRejected(1, 9, 3, 0, 1, -1, 1);
    assertVersionEncodeRejected(1, 9, 3, 0, 1, 1, 0);
    assertVersionEncodeRejected(1, 9, 3, 0, 1, 1, -1);
    assertVersionEncodeRejected(1, 9, 3, 9, 1, 1, 1);
  }

  @Test
  void rejectsLogicalVersionFlagsIdentityAndChecksumCorruption() {
    ByteBuffer logical = encodedLogical();
    LogicalRowRecord result = new LogicalRowRecord();

    FormatBytes.putInt(logical, 8, 1);
    reseal(logical, 40, 44);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeLogical(logical, 2, result, new CRC32C()));

    logical = encodedLogical();
    FormatBytes.putInt(logical, 12, 2);
    reseal(logical, 40, 44);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeLogical(logical, 2, result, new CRC32C()));

    logical = encodedLogical();
    FormatBytes.putLong(logical, 16, 0);
    reseal(logical, 40, 44);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeLogical(logical, 2, result, new CRC32C()));

    logical = encodedLogical();
    FormatBytes.putLong(logical, 16, CatalogKeyspace.OBJECT_ID_EXHAUSTED);
    reseal(logical, 40, 44);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeLogical(logical, 2, result, new CRC32C()));

    logical = encodedLogical();
    logical.put(24, (byte) (logical.get(24) ^ 1));
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeLogical(logical, 2, result, new CRC32C()));
    assertEquals(0, result.objectId());
    assertEquals(0, result.logicalRowId());
    assertEquals(0, result.headHeapVersionId());
  }

  @Test
  void rejectsHeapVersionVersionFlagsIdentityAndChecksumCorruption() {
    ByteBuffer version = encodedVersion();
    VersionRecord result = new VersionRecord();

    FormatBytes.putInt(version, 8, 1);
    reseal(version, 80, 84);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeVersion(version, 9, result, new CRC32C()));

    version = encodedVersion();
    FormatBytes.putInt(version, 12, 2);
    reseal(version, 80, 84);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeVersion(version, 9, result, new CRC32C()));

    version = encodedVersion();
    FormatBytes.putLong(version, 16, 0);
    reseal(version, 80, 84);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeVersion(version, 9, result, new CRC32C()));

    version = encodedVersion();
    FormatBytes.putLong(version, 16, CatalogKeyspace.OBJECT_ID_EXHAUSTED);
    reseal(version, 80, 84);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeVersion(version, 9, result, new CRC32C()));

    version = encodedVersion();
    FormatBytes.putLong(version, 40, 9);
    reseal(version, 80, 84);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeVersion(version, 9, result, new CRC32C()));

    version = encodedVersion();
    version.put(56, (byte) (version.get(56) ^ 1));
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeVersion(version, 9, result, new CRC32C()));
    assertEquals(0, result.objectId());
    assertEquals(0, result.heapVersionId());

    version = encodedVersion();
    version.limit(version.limit() - 1);
    assertEquals(
        StatusCode.CORRUPTION,
        RowDirectoryCodec.decodeVersion(version, 9, result, new CRC32C()));
  }

  @Test
  void invalidExpectedIdentityResetsCallerOwnedResults() {
    LogicalRowRecord logical = new LogicalRowRecord();
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.decodeLogical(encodedLogical(), 2, logical, new CRC32C()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RowDirectoryCodec.decodeLogical(encodedLogical(), 0, logical, new CRC32C()));
    assertEquals(0, logical.objectId());

    VersionRecord version = new VersionRecord();
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.decodeVersion(encodedVersion(), 9, version, new CRC32C()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RowDirectoryCodec.decodeVersion(encodedVersion(), -1, version, new CRC32C()));
    assertEquals(0, version.objectId());
  }

  private static ByteBuffer encodedLogical() {
    ByteBuffer bytes = ByteBuffer.allocate(RowDirectoryCodec.LOGICAL_RECORD_BYTES);
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.encodeLogical(bytes, 1, 2, 9, true, new CRC32C()));
    return bytes;
  }

  private static ByteBuffer encodedVersion() {
    ByteBuffer bytes = ByteBuffer.allocate(RowDirectoryCodec.VERSION_RECORD_BYTES);
    assertEquals(
        StatusCode.OK,
        RowDirectoryCodec.encodeVersion(
            bytes, 1, 9, 3, 0, 1, 1, 1, 1, 1, true, new CRC32C()));
    return bytes;
  }

  private static void assertLogicalEncodeRejected(
      long objectId, long logicalRowId, long headHeapVersionId) {
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RowDirectoryCodec.encodeLogical(
            ByteBuffer.allocate(RowDirectoryCodec.LOGICAL_RECORD_BYTES),
            objectId,
            logicalRowId,
            headHeapVersionId,
            false,
            new CRC32C()));
  }

  private static void assertVersionEncodeRejected(
      long objectId,
      long heapVersionId,
      long logicalRowId,
      long previousHeapVersionId,
      long commitSequence,
      long pageNumber,
      long pageGeneration) {
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        RowDirectoryCodec.encodeVersion(
            ByteBuffer.allocate(RowDirectoryCodec.VERSION_RECORD_BYTES),
            objectId,
            heapVersionId,
            logicalRowId,
            previousHeapVersionId,
            commitSequence,
            pageNumber,
            pageGeneration,
            1,
            1,
            false,
            new CRC32C()));
  }

  private static void reseal(ByteBuffer bytes, int checksumOffset, int complementOffset) {
    int checksum = FormatBytes.checksum(bytes, 0, checksumOffset, new CRC32C());
    FormatBytes.putInt(bytes, checksumOffset, checksum);
    FormatBytes.putInt(bytes, complementOffset, ~checksum);
  }
}
