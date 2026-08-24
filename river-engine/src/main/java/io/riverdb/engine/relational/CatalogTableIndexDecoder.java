package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Applies and validates index descriptors embedded in a table record. */
final class CatalogTableIndexDecoder {
  private CatalogTableIndexDecoder() {
  }

  static StatusCode decode(
      ByteBuffer source,
      int indexCount,
      int columnCount,
      long referenceMask,
      TableDefinition result) {
    int buildingIndexes = 0;
    for (int index = 0; index < indexCount; index++) {
      int offset = CatalogRecord.TABLE_INDEXES_OFFSET + index * 16;
      int tableId = source.getInt(offset);
      int state = source.getInt(offset + 4);
      int column = source.getInt(offset + 8);
      int flags = source.getInt(offset + 12);
      if (!validIndex(
          source, index, tableId, state, column, flags, columnCount, referenceMask)) {
        return StatusCode.CORRUPTION;
      }
      if (state == TableDefinition.INDEX_BUILDING && ++buildingIndexes > 1) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = result.upsertIndex(
          tableId, state, column, (flags & 1) != 0, (flags & 2) != 0);
      if (status == StatusCode.CONFLICT
          || status == StatusCode.RESOURCE_EXHAUSTED
          || status == StatusCode.INVALID_EXTERNAL_INPUT) {
        return StatusCode.CORRUPTION;
      }
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private static boolean validIndex(
      ByteBuffer source,
      int slot,
      int tableId,
      int state,
      int column,
      int flags,
      int columnCount,
      long referenceMask) {
    return tableId > 0
        && tableId <= RelationalKey.MAXIMUM_TABLE_ID
        && tableId != source.getInt(12)
        && (state == TableDefinition.INDEX_BUILDING
            || state == TableDefinition.INDEX_READY
            || state == TableDefinition.INDEX_DROPPING)
        && column > 0
        && column < columnCount
        && (flags & ~3) == 0
        && ((flags & 3) != 2 || (referenceMask & 1L << column) != 0)
        && !duplicateIndex(source, slot, tableId, column);
  }

  private static boolean duplicateIndex(
      ByteBuffer source,
      int slot,
      int tableId,
      int column) {
    for (int prior = 0; prior < slot; prior++) {
      int offset = CatalogRecord.TABLE_INDEXES_OFFSET + prior * 16;
      if (source.getInt(offset) == tableId || source.getInt(offset + 8) == column) {
        return true;
      }
    }
    return false;
  }
}
