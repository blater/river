package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Encodes the catalog table header and its fixed metadata sections. */
final class CatalogTableHeaderEncoder {
  private CatalogTableHeaderEncoder() { }

  static int encode(ByteBuffer target, int tableId, int indexTableId, int indexState, int indexColumn,
      CharSequence name, int columnCount, long notNullMask, long defaultMask,
      TableSchema definition, TableDefinition existing, boolean unique, boolean constraint) {
    CatalogTableFieldEncoder.clear(target);
    int existingCount = existing == null ? 0 : existing.uniqueIndexCount();
    int overrideSlot = indexOverrideSlot(existing, existingCount, indexTableId, indexColumn);
    int indexCount = existingCount + (indexTableId > 0 && overrideSlot < 0 ? 1 : 0);
    if (existing == null && indexTableId > 0) indexCount = 1;
    CatalogTableFieldEncoder.writeHeader(target, tableId, name.length(), columnCount, indexCount,
        notNullMask, defaultMask, definition, existing);
    CatalogTableFieldEncoder.writeChecks(target, definition, existing);
    CatalogTableFieldEncoder.writeDefaults(target, definition, existing);
    CatalogTableFieldEncoder.writeReferences(target, definition, existing);
    CatalogTableFieldEncoder.writeTypes(target, columnCount, definition, existing);
    CatalogTableFieldEncoder.writeDefaultKinds(target, definition, existing);
    CatalogTableFieldEncoder.writeIndexes(target, indexCount, existingCount, overrideSlot, indexTableId,
        indexState, indexColumn, existing, unique, constraint);
    int nameOffset = CatalogRecord.TABLE_INDEXES_OFFSET + indexCount * 16;
    CatalogTableFieldEncoder.writeName(target, nameOffset, name);
    return nameOffset + name.length();
  }

  private static int indexOverrideSlot(TableDefinition existing, int count, int tableId, int column) {
    if (tableId <= 0 || existing == null) return -1;
    for (int index = 0; index < count; index++) {
      if (existing.uniqueIndexTableId(index) == tableId || existing.uniqueIndexColumn(index) == column) return index;
    }
    return -1;
  }
}
