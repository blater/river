package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.catalog.CatalogChunkPlan;
import io.riverdb.format.catalog.CatalogChunkPlanner;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import java.nio.ByteBuffer;

/** Canonically packs variable-size table descriptor items into bounded catalog child payloads. */
public final class CatalogTablePayloadPacker {
  private final CatalogPayloadSize size = new CatalogPayloadSize();
  private final CatalogChunkPlan admission = new CatalogChunkPlan();

  public StatusCode plan(TableDescriptor table, CatalogTablePayloadPlan result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (table == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = packColumns(table, result);
    if (status.isOk()) status = packKeys(table, result);
    if (status.isOk()) status = validate(table, result);
    if (!status.isOk()) result.reset();
    return status;
  }

  public StatusCode encodeChunk(
      TableDescriptor table, CatalogTablePayloadPlan plan,
      int chunk, ByteBuffer target, int start) {
    if (table == null || plan == null || chunk < 0 || chunk >= plan.chunkCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int kind = plan.kindAt(chunk);
    return kind == CatalogDefinitionRecordCodec.KIND_COLUMNS
        ? CatalogColumnPayloadCodec.encode(table.columns(), plan.itemStartAt(chunk),
            plan.itemCountAt(chunk), target, start)
        : kind == CatalogDefinitionRecordCodec.KIND_KEY
            ? CatalogKeyPayloadCodec.encode(table, plan.itemStartAt(chunk),
                plan.itemCountAt(chunk), target, start)
            : StatusCode.INVARIANT_BROKEN;
  }

  private StatusCode packColumns(TableDescriptor table, CatalogTablePayloadPlan result) {
    int first = 0;
    while (first < table.columnCount()) {
      int count = largestColumnRun(table, first);
      if (count <= 0) return StatusCode.RESOURCE_EXHAUSTED;
      StatusCode status = CatalogColumnPayloadCodec.payloadBytes(
          table.columns(), first, count, size);
      if (!status.isOk()) return status;
      status = result.add(CatalogDefinitionRecordCodec.KIND_COLUMNS,
          first, count, first, count, size.bytes());
      if (!status.isOk()) return status;
      first += count;
    }
    return StatusCode.OK;
  }

  private StatusCode packKeys(TableDescriptor table, CatalogTablePayloadPlan result) {
    int first = 0;
    int logicalStart = 0;
    int keys = CatalogTableKeys.count(table);
    while (first < keys) {
      int count = largestKeyRun(table, first, keys);
      if (count <= 0) return StatusCode.RESOURCE_EXHAUSTED;
      StatusCode status = CatalogKeyPayloadCodec.payloadBytes(table, first, count, size);
      if (!status.isOk()) return status;
      int parts = keyParts(table, first, count);
      status = result.add(CatalogDefinitionRecordCodec.KIND_KEY,
          first, count, logicalStart, parts, size.bytes());
      if (!status.isOk()) return status;
      first += count;
      logicalStart += parts;
    }
    return StatusCode.OK;
  }

  private int largestColumnRun(TableDescriptor table, int first) {
    int available = Math.min(
        CatalogDefinitionRecordCodec.MAX_COLUMN_RECORDS, table.columnCount() - first);
    int accepted = 0;
    for (int count = 1; count <= available; count++) {
      StatusCode status = CatalogColumnPayloadCodec.payloadBytes(
          table.columns(), first, count, size);
      if (!status.isOk()) break;
      accepted = count;
    }
    return accepted;
  }

  private int largestKeyRun(TableDescriptor table, int first, int keyCount) {
    int accepted = 0;
    for (int count = 1; count <= keyCount - first; count++) {
      StatusCode status = CatalogKeyPayloadCodec.payloadBytes(table, first, count, size);
      if (!status.isOk()) break;
      accepted = count;
    }
    return accepted;
  }

  private StatusCode validate(TableDescriptor table, CatalogTablePayloadPlan plan) {
    int columnChunks = 0;
    int columnBytes = 0;
    int keyChunks = 0;
    int keyBytes = 0;
    int keyParts = 0;
    for (int index = 0; index < plan.chunkCount(); index++) {
      if (plan.kindAt(index) == CatalogDefinitionRecordCodec.KIND_COLUMNS) {
        columnChunks++;
        columnBytes += plan.payloadBytesAt(index);
      } else {
        keyChunks++;
        keyBytes += plan.payloadBytesAt(index);
        keyParts += plan.logicalCountAt(index);
      }
    }
    return CatalogChunkPlanner.planTable(
        table.columnCount(), columnChunks, columnBytes,
        keyParts, keyChunks, keyBytes, 0, 0, 0, 0, 0, 0, admission);
  }

  private static int keyParts(TableDescriptor table, int first, int count) {
    int parts = 0;
    for (int index = 0; index < count; index++) {
      KeyDescriptor key = CatalogTableKeys.at(table, first + index);
      parts += key.partCount();
    }
    return parts;
  }
}
