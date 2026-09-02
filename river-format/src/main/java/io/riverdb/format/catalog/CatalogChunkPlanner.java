package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/**
 * Validates exact per-kind totals reported by the canonical catalog payload packer.
 * Individual record codecs independently enforce each emitted record's byte bound.
 */
public final class CatalogChunkPlanner {
  private CatalogChunkPlanner() {
  }

  public static StatusCode planTable(
      int columnCount,
      int columnChunks,
      int columnBytes,
      int keyPartCount,
      int keyChunks,
      int keyBytes,
      int constraintCount,
      int constraintChunks,
      int constraintBytes,
      int expressionCount,
      int expressionChunks,
      int expressionBytes,
      CatalogChunkPlan result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (columnCount <= 0 || keyPartCount < 0 || constraintCount < 0
        || expressionCount < 0
        || !validPackedKind(columnCount, columnChunks, columnBytes)
        || !validPackedKind(keyPartCount, keyChunks, keyBytes)
        || !validPackedKind(constraintCount, constraintChunks, constraintBytes)
        || !validPackedKind(expressionCount, expressionChunks, expressionBytes)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (columnCount > SqlShapeLimits.MAX_TABLE_COLUMNS
        || keyPartCount > SqlShapeLimits.MAX_TABLE_KEY_PARTS
        || constraintCount > SqlShapeLimits.MAX_CHECK_CONSTRAINTS
        || expressionCount > SqlShapeLimits.MAX_EXPRESSION_NODES
        || columnChunks < chunks(columnCount, CatalogDefinitionRecordCodec.MAX_COLUMN_RECORDS)
        || expressionChunks < chunks(
            expressionCount, CatalogDefinitionRecordCodec.MAX_EXPRESSION_NODES)
        || !withinPackedCapacity(columnChunks, columnBytes)
        || !withinPackedCapacity(keyChunks, keyBytes)
        || !withinPackedCapacity(constraintChunks, constraintBytes)
        || !withinPackedCapacity(expressionChunks, expressionBytes)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return finish(columnChunks, keyChunks, constraintChunks, expressionChunks,
        columnBytes, keyBytes, constraintBytes, expressionBytes,
        SqlShapeLimits.MAX_SCHEMA_CHUNKS, result);
  }

  public static StatusCode planStatistics(
      int columnCount, int packedChunks, int payloadBytes, CatalogChunkPlan result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (columnCount <= 0 || !validPackedKind(columnCount, packedChunks, payloadBytes)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int minimum = chunks(columnCount, CatalogDefinitionRecordCodec.MAX_STATISTICS_COLUMNS);
    if (columnCount > SqlShapeLimits.MAX_TABLE_COLUMNS || packedChunks != minimum
        || !withinPackedCapacity(packedChunks, payloadBytes)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return finish(packedChunks, 0, 0, 0, payloadBytes, 0, 0, 0,
        packedChunks, result);
  }

  private static StatusCode finish(
      int columnChunks,
      int keyChunks,
      int constraintChunks,
      int expressionChunks,
      int columnBytes,
      int keyBytes,
      int constraintBytes,
      int expressionBytes,
      int maximumChunks,
      CatalogChunkPlan result) {
    long total = (long) columnChunks + keyChunks + constraintChunks + expressionChunks;
    long payloadBytes = (long) columnBytes + keyBytes + constraintBytes + expressionBytes;
    if (payloadBytes > SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES
        || total > maximumChunks) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    result.set(columnChunks, keyChunks, constraintChunks, expressionChunks,
        columnBytes, keyBytes, constraintBytes, expressionBytes);
    return StatusCode.OK;
  }

  private static boolean validPackedKind(
      int logicalCount, int packedChunks, int payloadBytes) {
    if (logicalCount < 0 || packedChunks < 0 || payloadBytes < 0) return false;
    if (logicalCount == 0) return packedChunks == 0 && payloadBytes == 0;
    return packedChunks > 0 && payloadBytes >= packedChunks;
  }

  private static boolean withinPackedCapacity(int packedChunks, int payloadBytes) {
    return (long) payloadBytes
        <= (long) packedChunks * CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES;
  }

  private static int chunks(int count, int perChunk) {
    return count == 0 ? 0 : (count - 1) / perChunk + 1;
  }
}
