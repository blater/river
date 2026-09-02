package io.riverdb.format.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class CatalogRecordCodecTest {
  @Test
  void relationalObjectsOwnOneExactBaseRowSpace() {
    assertEquals(
        CatalogKeyspace.FIRST_RELATIONAL_SPACE + 1,
        CatalogKeyspace.relationalBaseRowSpace(1));
    assertEquals(
        CatalogKeyspace.FIRST_RELATIONAL_SPACE
            + CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID,
        CatalogKeyspace.relationalBaseRowSpace(
            CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID));
    assertTrue(CatalogKeyspace.relationalBaseRowSpace(
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID)
        < CatalogKeyspace.FIRST_INDEX_SPACE);
  }

  @Test
  void objectHeadIsThePositiveLongVisibilityPointer() {
    ByteBuffer bytes = ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN);
    bytes.position(3);
    bytes.limit(76);
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.encode(
        bytes, 8, CatalogObjectHeadCodec.STATE_READY,
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID,
        Long.MAX_VALUE - 1, Long.MAX_VALUE - 2, Long.MAX_VALUE - 3, new CRC32C()));
    assertEquals(3, bytes.position());
    assertEquals(76, bytes.limit());
    assertEquals(ByteOrder.BIG_ENDIAN, bytes.order());

    CatalogObjectHead head = new CatalogObjectHead();
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.decode(bytes, 8, head, new CRC32C()));
    assertEquals(CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID, head.objectId());
    assertEquals(Long.MAX_VALUE - 3, head.manifestRecordId());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, CatalogObjectHeadCodec.encode(
        bytes, 8, CatalogObjectHeadCodec.STATE_READY, 0, 1, 1, 1, new CRC32C()));
    assertEquals(StatusCode.OK, CatalogObjectHeadCodec.encode(
        bytes, 8, CatalogObjectHeadCodec.STATE_TOMBSTONE, 9, 0, 4, 0, new CRC32C()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, CatalogObjectHeadCodec.encode(
        bytes, 8, CatalogObjectHeadCodec.STATE_READY,
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID + 1, 1, 1, 1, new CRC32C()));
  }

  @Test
  void codecsAreCanonicalLittleEndianAndAbsolute() {
    ByteBuffer big = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
    ByteBuffer little = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
    big.position(5);
    little.position(5);
    ByteBuffer payload = ByteBuffer.wrap(new byte[] {1, 3, 3, 7});
    assertEquals(StatusCode.OK, encodeChild(big, 16, 91, 12, 13, 14,
        CatalogDefinitionRecordCodec.KIND_COLUMNS, 0, 0, 4, payload));
    assertEquals(StatusCode.OK, encodeChild(little, 16, 91, 12, 13, 14,
        CatalogDefinitionRecordCodec.KIND_COLUMNS, 0, 0, 4, payload));
    assertArrayEquals(big.array(), little.array());
    assertEquals(5, big.position());
    assertEquals(0, payload.position());
    assertEquals(0x48, Byte.toUnsignedInt(big.get(16)));
    assertEquals(0x43, Byte.toUnsignedInt(big.get(17)));
  }

  @Test
  void childDecodeRejectsIdentityCountChecksumAndTruncationWithoutStaleResult() {
    ByteBuffer bytes = childBytes(7, 9, 11, 13,
        CatalogDefinitionRecordCodec.KIND_COLUMNS, 0, 0, 2, new byte[] {2, 4});
    CatalogDefinitionRecord child = new CatalogDefinitionRecord();
    assertEquals(StatusCode.OK, CatalogDefinitionRecordCodec.decode(
        bytes, 0, bytes.limit(), child, new CRC32C()));
    assertEquals(7, child.catalogRecordId());
    bytes.put(CatalogDefinitionRecordCodec.HEADER_BYTES, (byte) 3);
    assertEquals(StatusCode.CORRUPTION, CatalogDefinitionRecordCodec.decode(
        bytes, 0, bytes.limit(), child, new CRC32C()));
    assertEquals(0, child.catalogRecordId());
    bytes.put(CatalogDefinitionRecordCodec.HEADER_BYTES, (byte) 2);
    assertEquals(StatusCode.CORRUPTION, CatalogDefinitionRecordCodec.decode(
        bytes, 0, bytes.limit() - 1, child, new CRC32C()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, encodeChild(bytes, 0, 0, 1, 1, 1,
        CatalogDefinitionRecordCodec.KIND_COLUMNS, 0, 0, 1,
        ByteBuffer.wrap(new byte[] {1})));
  }

  @Test
  void keyChildrenAndManifestsUseTheAggregateTablePartLimit() {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogDefinitionRecordCodec.HEADER_BYTES + 1);
    assertEquals(StatusCode.OK, encodeChild(bytes, 0, 1, 2, 3, 4,
        CatalogDefinitionRecordCodec.KIND_KEY, 0, 0,
        SqlShapeLimits.MAX_TABLE_KEY_PARTS, ByteBuffer.wrap(new byte[] {1})));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, encodeChild(bytes, 0, 1, 2, 3, 4,
        CatalogDefinitionRecordCodec.KIND_KEY, 0, 0,
        SqlShapeLimits.MAX_TABLE_KEY_PARTS + 1, ByteBuffer.wrap(new byte[] {1})));

    CatalogDefinitionManifest manifest = manifest(1, 2, 3, 4, 5, 6, 1,
        1, SqlShapeLimits.MAX_TABLE_KEY_PARTS,
        1 + SqlShapeLimits.MAX_TABLE_KEY_PARTS, 1, 7);
    assertEquals(SqlShapeLimits.MAX_TABLE_KEY_PARTS, manifest.keyPartCount());
    ByteBuffer manifestBytes = ByteBuffer.allocate(CatalogDefinitionManifestCodec.BYTES);
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, CatalogDefinitionManifestCodec.encode(
        manifestBytes, 0, CatalogDefinitionManifestCodec.KIND_TABLE,
        1, 2, 3, 4, 5, 6, 1, 1,
        SqlShapeLimits.MAX_TABLE_KEY_PARTS + 1,
        2 + SqlShapeLimits.MAX_TABLE_KEY_PARTS, 1, 7, new CRC32C()));
  }

  @Test
  void plannerCoversColumnAndSchemaBoundaries() {
    CatalogChunkPlan plan = new CatalogChunkPlan();
    assertPlan(plan, 31, 1);
    assertPlan(plan, 32, 1);
    assertPlan(plan, 33, 2);
    assertPlan(plan, 1_023, 32);
    assertPlan(plan, 1_024, 32);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        CatalogChunkPlanner.planTable(
            1_025, 33, 33, 0, 0, 0, 0, 0, 0, 0, 0, 0, plan));

    assertEquals(StatusCode.OK,
        CatalogChunkPlanner.planTable(
            32, 1, 1, 32, 1, 1, 30, 30, 30, 0, 0, 0, plan));
    assertEquals(32, plan.totalChunks());
    assertEquals(StatusCode.OK,
        CatalogChunkPlanner.planTable(
            32, 1, 1, 32, 1, 1, 31, 31, 31, 0, 0, 0, plan));
    assertEquals(33, plan.totalChunks());
    assertEquals(StatusCode.OK, CatalogChunkPlanner.planTable(
        32, 1, 1, 32, 1, 1,
        SqlShapeLimits.MAX_SCHEMA_CHUNKS - 2,
        SqlShapeLimits.MAX_SCHEMA_CHUNKS - 2,
        SqlShapeLimits.MAX_SCHEMA_CHUNKS - 2, 0, 0, 0, plan));
    assertEquals(160, plan.totalChunks());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, CatalogChunkPlanner.planTable(
        32, 1, 1, 32, 1, 1,
        SqlShapeLimits.MAX_SCHEMA_CHUNKS - 1,
        SqlShapeLimits.MAX_SCHEMA_CHUNKS - 1,
        SqlShapeLimits.MAX_SCHEMA_CHUNKS - 1, 0, 0, 0, plan));
    int columnBytes = 32 * CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES;
    int constraintBytes = SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES - columnBytes;
    assertEquals(StatusCode.OK, CatalogChunkPlanner.planTable(
        1_024, 32, columnBytes, 0, 0, 0,
        128, 128, constraintBytes, 0, 0, 0, plan));
    assertEquals(SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES, plan.payloadBytes());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, CatalogChunkPlanner.planTable(
        1_024, 32, columnBytes, 0, 0, 0,
        128, 128, constraintBytes + 1, 0, 0, 0, plan));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, CatalogChunkPlanner.planTable(
        32, 1, SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES + 1,
        32, 1, 1, 0, 0, 0, 0, 0, 0, plan));
    assertEquals(StatusCode.OK, CatalogChunkPlanner.planTable(
        32, 1, 1, SqlShapeLimits.MAX_TABLE_KEY_PARTS, 65, 65,
        0, 0, 0, 0, 0, 0, plan));
    assertEquals(65, plan.keyChunks());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, CatalogChunkPlanner.planTable(
        32, 1, 1, SqlShapeLimits.MAX_TABLE_KEY_PARTS + 1, 65, 65,
        0, 0, 0, 0, 0, 0, plan));
  }

  @Test
  void plannerSumsPackedKindsInsteadOfSharingAggregatePayloadCapacity() {
    CatalogChunkPlan plan = new CatalogChunkPlan();
    assertEquals(StatusCode.OK,
        CatalogChunkPlanner.planTable(
            1, 1, CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES,
            1, 1, CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES - 2,
            1, 1, 1, 1, 1, 1, plan));
    assertEquals(4, plan.totalChunks());
    assertEquals(1, plan.columnChunks());
    assertEquals(1, plan.keyChunks());
    assertEquals(1, plan.constraintChunks());
    assertEquals(1, plan.expressionChunks());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        CatalogChunkPlanner.planTable(1, 1,
            CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES + 1,
            1, 1, 1, 0, 0, 0, 0, 0, 0, plan));
    assertEquals(StatusCode.OK,
        CatalogChunkPlanner.planTable(1, 2,
            CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES + 1,
            1, 1, 1, 0, 0, 0, 0, 0, 0, plan));
  }

  @Test
  void plannerBoundsStatisticsAtOneHundredTwentyEightColumns() {
    CatalogChunkPlan plan = new CatalogChunkPlan();
    assertEquals(StatusCode.OK, CatalogChunkPlanner.planStatistics(1_023, 8, 8, plan));
    assertEquals(8, plan.columnChunks());
    assertEquals(StatusCode.OK, CatalogChunkPlanner.planStatistics(1_024, 8, 8, plan));
    assertEquals(8, plan.totalChunks());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        CatalogChunkPlanner.planStatistics(
            1, 1, CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES + 1, plan));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        CatalogChunkPlanner.planStatistics(
            1, 2, CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES + 1, plan));
  }

  @Test
  void plannerUsesTheExpressionRecordLogicalLimit() {
    CatalogChunkPlan plan = new CatalogChunkPlan();
    int expressions = SqlShapeLimits.MAX_EXPRESSION_NODES;
    int chunks = expressions / CatalogDefinitionRecordCodec.MAX_EXPRESSION_NODES;
    assertEquals(StatusCode.OK, CatalogChunkPlanner.planTable(
        1, 1, 1, 0, 0, 0, 0, 0, 0,
        expressions, chunks, chunks, plan));
    assertEquals(chunks, plan.expressionChunks());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, CatalogChunkPlanner.planTable(
        1, 1, 1, 0, 0, 0, 0, 0, 0,
        expressions, chunks - 1, chunks - 1, plan));
    assertEquals(StatusCode.OK, CatalogChunkPlanner.planTable(
        1, 1, 1, 0, 0, 0, 0, 0, 0,
        expressions - 1, chunks, chunks, plan));
  }

  @Test
  void assemblesOneHundredSixtyChildrenWithoutScalarMask() {
    int count = SqlShapeLimits.MAX_SCHEMA_CHUNKS;
    long first = Long.MAX_VALUE - count + 1;
    CatalogDefinitionRecord[] children = new CatalogDefinitionRecord[count];
    CRC32C setChecksum = new CRC32C();
    int logical = 0;
    for (int ordinal = 0; ordinal < count; ordinal++) {
      int kind = ordinal < 32 ? CatalogDefinitionRecordCodec.KIND_COLUMNS
          : CatalogDefinitionRecordCodec.KIND_CONSTRAINT;
      int start = ordinal < 32 ? ordinal * 32 : ordinal - 32;
      int width = ordinal < 32 ? 32 : 1;
      children[ordinal] = child(first + ordinal, 17, 19, 23, kind,
          ordinal, start, width, new byte[] {(byte) ordinal});
      logical += width;
      CatalogDefinitionRecordCodec.updateChildSetChecksum(
          setChecksum, children[ordinal].recordChecksum());
    }
    CatalogDefinitionManifest manifest = manifest(31, 17, 19, 29, 23,
        first, count, 1_024, 0, logical, count, (int) setChecksum.getValue());
    CatalogAssemblyValidator validator = new CatalogAssemblyValidator();
    assertEquals(StatusCode.OK, validator.begin(manifest, new CRC32C()));
    for (CatalogDefinitionRecord child : children) {
      assertEquals(StatusCode.OK, validator.accept(child));
    }
    assertTrue(validator.complete());
  }

  @Test
  void assemblyRejectsMissingDuplicateReorderedOverlapAndCorruptIdentity() {
    CatalogDefinitionRecord first = child(101, 7, 8, 9,
        CatalogDefinitionRecordCodec.KIND_COLUMNS, 0, 0, 2, new byte[] {1});
    CatalogDefinitionRecord second = child(102, 7, 8, 9,
        CatalogDefinitionRecordCodec.KIND_COLUMNS, 1, 2, 1, new byte[] {2});
    CRC32C sum = new CRC32C();
    CatalogDefinitionRecordCodec.updateChildSetChecksum(sum, first.recordChecksum());
    CatalogDefinitionRecordCodec.updateChildSetChecksum(sum, second.recordChecksum());
    CatalogDefinitionManifest manifest = manifest(
        90, 7, 8, 10, 9, 101, 2, 3, 0, 3, 2, (int) sum.getValue());
    CatalogAssemblyValidator validator = new CatalogAssemblyValidator();
    assertEquals(StatusCode.OK, validator.begin(manifest, new CRC32C()));
    assertEquals(StatusCode.OK, validator.accept(first));
    assertFalse(validator.complete());
    assertEquals(StatusCode.CORRUPTION, validator.accept(first));

    assertEquals(StatusCode.OK, validator.begin(manifest, new CRC32C()));
    assertEquals(StatusCode.CORRUPTION, validator.accept(second));

    CatalogDefinitionRecord overlap = child(102, 7, 8, 9,
        CatalogDefinitionRecordCodec.KIND_COLUMNS, 1, 1, 1, new byte[] {2});
    assertEquals(StatusCode.OK, validator.begin(manifest, new CRC32C()));
    assertEquals(StatusCode.OK, validator.accept(first));
    assertEquals(StatusCode.CORRUPTION, validator.accept(overlap));

    CatalogDefinitionRecord wrongObject = child(101, 6, 8, 9,
        CatalogDefinitionRecordCodec.KIND_COLUMNS, 0, 0, 2, new byte[] {1});
    assertEquals(StatusCode.OK, validator.begin(manifest, new CRC32C()));
    assertEquals(StatusCode.CORRUPTION, validator.accept(wrongObject));
  }

  @Test
  void manifestAndAllocationWatermarkPreserveLongIdsAndResetOnCorruption() {
    CatalogDefinitionManifest manifest = manifest(Long.MAX_VALUE,
        CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID,
        Long.MAX_VALUE - 2, Long.MAX_VALUE - 3, Long.MAX_VALUE - 4,
        Long.MAX_VALUE - 1, 1, 1, 1, 2, 1, 7);
    assertEquals(Long.MAX_VALUE, manifest.catalogRecordId());
    ByteBuffer bytes = ByteBuffer.allocate(CatalogAllocationWatermarkCodec.BYTES + 4);
    assertEquals(StatusCode.OK, CatalogAllocationWatermarkCodec.encode(
        bytes, 4, CatalogKeyspace.OBJECT_ID_EXHAUSTED, Long.MAX_VALUE - 1,
        Long.MAX_VALUE - 2, Long.MAX_VALUE - 3,
        CatalogKeyspace.MAXIMUM_KEY_ID - 3, new CRC32C()));
    CatalogAllocationWatermark watermark = new CatalogAllocationWatermark();
    assertEquals(StatusCode.OK,
        CatalogAllocationWatermarkCodec.decode(bytes, 4, watermark, new CRC32C()));
    assertEquals(CatalogKeyspace.OBJECT_ID_EXHAUSTED, watermark.nextObjectId());
    assertFalse(watermark.canAllocateObjectId());
    assertTrue(watermark.canAllocateSchemaId());
    assertTrue(watermark.canAllocateRowLayoutId());
    assertTrue(watermark.canAllocateCatalogRecordId());
    assertTrue(watermark.canAllocateKeyIds(4));
    assertFalse(watermark.canAllocateKeyIds(5));
    assertEquals(
        CatalogKeyspace.FIRST_INDEX_SPACE + CatalogKeyspace.MAXIMUM_KEY_ID - 1,
        CatalogKeyspace.relationalIndexSpace(CatalogKeyspace.MAXIMUM_KEY_ID));
    bytes.put(4, (byte) 0);
    assertEquals(StatusCode.CORRUPTION,
        CatalogAllocationWatermarkCodec.decode(bytes, 4, watermark, new CRC32C()));
    assertFalse(watermark.isAvailable());
    assertFalse(watermark.canAllocateKeyIds(1));
  }

  @Test
  void buildIntentRoundTripsProgressAndRejectsCorruption() {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogBuildIntentCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogBuildIntentCodec.encodeWithKeys(bytes, 0,
        CatalogBuildIntentCodec.STATE_BUILDING, 7, 8, 9, 10,
        100, 101, 32, 16, 0, 4_096, 8_000,
        200, 4, 3, 2, 0, new CRC32C()));
    CatalogBuildIntent intent = new CatalogBuildIntent();
    assertEquals(StatusCode.OK,
        CatalogBuildIntentCodec.decode(bytes, 0, intent, new CRC32C()));
    assertEquals(7, intent.objectId());
    assertEquals(9, intent.rowLayoutId());
    assertEquals(16, intent.nextChild());
    assertEquals(200, intent.firstKeyId());
    assertEquals(4, intent.keyCount());
    assertEquals(3, intent.physicalIndexCount());
    assertEquals(2, intent.nextPhysicalIndex());
    bytes.clear();
    assertEquals(StatusCode.OK, CatalogBuildIntentCodec.encodeWithCleanupHorizon(
        bytes, 0, CatalogBuildIntentCodec.STATE_CLEANUP,
        CatalogBuildIntentCodec.KIND_INITIAL, 7, 8, 9, 10,
        100, 101, 32, 32, 0, 4_096, 8_000,
        0, 0, 0, 200, 4, 3, 3, 1, 77, new CRC32C()));
    assertEquals(StatusCode.OK,
        CatalogBuildIntentCodec.decode(bytes, 0, intent, new CRC32C()));
    assertEquals(1, intent.indexCleanupCursor());
    assertEquals(77, intent.indexCleanupHorizon());
    bytes.put(88, (byte) (bytes.get(88) ^ 1));
    assertEquals(StatusCode.CORRUPTION,
        CatalogBuildIntentCodec.decode(bytes, 0, intent, new CRC32C()));
    assertFalse(intent.objectId() > 0);
  }

  @Test
  void manifestRecordCannotOverlapItsDurableChildRange() {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogDefinitionManifestCodec.BYTES);
    for (long manifestId : new long[] {100, 101, 102}) {
      assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
          encodeManifest(bytes, manifestId, 100, 3));
    }
    assertEquals(StatusCode.OK, encodeManifest(bytes, 99, 100, 3));
    assertEquals(StatusCode.OK, encodeManifest(bytes, 103, 100, 3));
  }

  private static void assertPlan(CatalogChunkPlan plan, int columns, int chunks) {
    assertEquals(StatusCode.OK,
        CatalogChunkPlanner.planTable(
            columns, chunks, columns, 0, 0, 0, 0, 0, 0, 0, 0, 0, plan));
    assertEquals(chunks, plan.columnChunks());
  }

  private static StatusCode encodeChild(
      ByteBuffer target, int start, long record, long object, long schema, long generation,
      int kind, int ordinal, int logicalStart, int logicalCount, ByteBuffer payload) {
    return CatalogDefinitionRecordCodec.encode(target, start, record, object, schema,
        generation, kind, ordinal, logicalStart, logicalCount, payload, new CRC32C());
  }

  private static CatalogDefinitionRecord child(
      long record, long object, long schema, long generation, int kind,
      int ordinal, int logicalStart, int logicalCount, byte[] payload) {
    ByteBuffer bytes = childBytes(record, object, schema, generation, kind,
        ordinal, logicalStart, logicalCount, payload);
    CatalogDefinitionRecord result = new CatalogDefinitionRecord();
    assertEquals(StatusCode.OK, CatalogDefinitionRecordCodec.decode(
        bytes, 0, bytes.limit(), result, new CRC32C()));
    return result;
  }

  private static ByteBuffer childBytes(
      long record, long object, long schema, long generation, int kind,
      int ordinal, int logicalStart, int logicalCount, byte[] payload) {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogDefinitionRecordCodec.HEADER_BYTES
        + payload.length);
    assertEquals(StatusCode.OK, encodeChild(bytes, 0, record, object, schema, generation,
        kind, ordinal, logicalStart, logicalCount, ByteBuffer.wrap(payload)));
    return bytes;
  }

  private static CatalogDefinitionManifest manifest(
      long record, long object, long schema, long layout, long generation,
      long firstChild, int children, int columns, int keys, int logical,
      int payloadBytes, int childChecksum) {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogDefinitionManifestCodec.BYTES);
    assertEquals(StatusCode.OK, CatalogDefinitionManifestCodec.encode(bytes, 0,
        CatalogDefinitionManifestCodec.KIND_TABLE, record, object, schema, layout,
        generation, firstChild, children, columns, keys, logical, payloadBytes,
        childChecksum, new CRC32C()));
    CatalogDefinitionManifest result = new CatalogDefinitionManifest();
    assertEquals(StatusCode.OK,
        CatalogDefinitionManifestCodec.decode(bytes, 0, result, new CRC32C()));
    return result;
  }

  private static StatusCode encodeManifest(
      ByteBuffer bytes, long manifestId, long firstChild, int children) {
    return CatalogDefinitionManifestCodec.encode(bytes, 0,
        CatalogDefinitionManifestCodec.KIND_TABLE, manifestId, 2, 3, 4, 5,
        firstChild, children, 1, 0, 1, children, 7, new CRC32C());
  }
}
