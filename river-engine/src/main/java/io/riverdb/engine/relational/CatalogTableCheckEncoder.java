package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Writes check metadata for a catalog table record. */
final class CatalogTableCheckEncoder {
  private CatalogTableCheckEncoder() { }

  static void write(
      ByteBuffer target,
      TableSchema definition,
      TableDefinition existing) {
    long checkMask = definition != null
        ? definition.checkMask() : existing != null ? existing.checkMask() : 0;
    target.putLong(CatalogRecord.TABLE_CHECK_MASK_OFFSET, checkMask);
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      writeColumn(target, definition, existing, checkMask, index);
    }
    int total = definition != null
        ? definition.checkNodeCount()
        : existing != null ? existing.checkNodeCount() : 0;
    target.putInt(CatalogRecord.TABLE_CHECK_NODE_TOTAL_OFFSET, total);
  }

  private static void writeColumn(
      ByteBuffer target,
      TableSchema definition,
      TableDefinition existing,
      long checkMask,
      int index) {
    boolean checked = (checkMask & 1L << index) != 0;
    int comparison = definition != null
        ? definition.checkComparison(index)
        : existing == null ? 0 : existing.checkComparison(index);
    long value = definition != null
        ? definition.checkValue(index)
        : existing == null ? 0 : existing.checkValue(index);
    int descriptor = definition != null
        ? definition.checkTypeDescriptor(index)
        : existing == null ? 0 : existing.checkTypeDescriptor(index);
    int nodes = definition != null
        ? definition.checkNodeCount(index)
        : existing == null ? 0 : existing.checkNodeCount(index);
    target.putInt(
        CatalogRecord.TABLE_CHECKS_OFFSET + index * Integer.BYTES,
        checked ? comparison : 0);
    target.putLong(
        CatalogRecord.TABLE_CHECK_VALUES_OFFSET + index * Long.BYTES,
        checked ? value : 0);
    target.putInt(
        CatalogRecord.TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET + index * Integer.BYTES,
        checked ? descriptor : 0);
    target.put(
        CatalogRecord.TABLE_CHECK_NODE_COUNTS_OFFSET + index,
        checked ? (byte) nodes : 0);
  }
}
