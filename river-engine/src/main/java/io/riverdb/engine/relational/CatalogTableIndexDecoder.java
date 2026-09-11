package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Validates and publishes the embedded index tail of one catalog table record. */
final class CatalogTableIndexDecoder {
  static final int INDEX_BYTES = 16;

  private CatalogTableIndexDecoder() { }

  static StatusCode decode(
      ByteBuffer source,
      int bytes,
      int offset,
      int indexes,
      int tableId,
      TableDefinition result) {
    if (offset < 0 || offset > bytes || indexes < 0
        || indexes > (bytes - offset) / INDEX_BYTES) {
      return StatusCode.CORRUPTION;
    }
    for (int slot = 0; slot < indexes; slot++) {
      int indexTableId = source.getInt(offset);
      int state = source.getInt(offset + 4);
      int column = source.getInt(offset + 8);
      int indexFlags = source.getInt(offset + 12);
      offset += INDEX_BYTES;
      if (!validIndex(result, tableId, indexTableId, state, column, indexFlags)
          || duplicateIndex(result, indexTableId, column)) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = result.upsertIndex(
          indexTableId, state, column, (indexFlags & 1) != 0, (indexFlags & 2) != 0);
      if (!status.isOk()) return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private static boolean validIndex(
      TableDefinition table,
      int tableId,
      int indexTableId,
      int state,
      int column,
      int flags) {
    return indexTableId > 0 && indexTableId <= RelationalKey.MAXIMUM_TABLE_ID
        && indexTableId != tableId && column > 0 && column < table.columnCount()
        && (state == TableDefinition.INDEX_BUILDING || state == TableDefinition.INDEX_READY
            || state == TableDefinition.INDEX_DROPPING)
        && (flags & ~3) == 0
        && ((flags & 3) != 2 || table.hasReference(column));
  }

  private static boolean duplicateIndex(TableDefinition table, int tableId, int column) {
    for (int slot = 0; slot < table.uniqueIndexCount(); slot++) {
      if (table.uniqueIndexTableId(slot) == tableId || table.uniqueIndexColumn(slot) == column) {
        return true;
      }
    }
    return false;
  }
}
