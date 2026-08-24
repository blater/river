package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Writes the fixed-width metadata sections of a catalog table record. */
final class CatalogTableFieldEncoder {
  private CatalogTableFieldEncoder() { }

  static void clear(ByteBuffer target) {
    target.clear();
    for (int index = 0; index < target.capacity(); index++) {
      target.put(index, (byte) 0);
    }
  }

  static void writeHeader(
      ByteBuffer target,
      int tableId,
      int nameBytes,
      int columnCount,
      int indexCount,
      long notNullMask,
      long defaultMask,
      TableSchema definition,
      TableDefinition existing) {
    target.putLong(0, CatalogRecord.TABLE_MAGIC);
    target.putInt(8, CatalogRecord.TABLE_VERSION);
    target.putInt(12, tableId);
    target.putInt(16, nameBytes);
    target.putInt(20, columnCount);
    target.putInt(24, indexCount);
    target.putLong(28, notNullMask);
    target.putLong(36, defaultMask);
    int defaultTextBytes = definition != null
        ? definition.defaultTextBytes()
        : existing != null ? existing.defaultTextBytes() : 0;
    target.putLong(44, defaultTextBytes);
    boolean identity = definition != null
        ? definition.hasIdentity() : existing != null && existing.hasIdentity();
    target.putLong(52, identity ? 1 : 0);
  }

  static void writeChecks(ByteBuffer target, TableSchema definition, TableDefinition existing) {
    CatalogTableCheckEncoder.write(target, definition, existing);
  }

  static void writeDefaults(ByteBuffer target, TableSchema definition, TableDefinition existing) {
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      long value = definition != null
          ? definition.defaultValue(index)
          : existing != null ? existing.defaultValue(index) : 0;
      target.putLong(CatalogRecord.TABLE_DEFAULTS_OFFSET + index * Long.BYTES, value);
    }
  }

  static void writeReferences(
      ByteBuffer target,
      TableSchema definition,
      TableDefinition existing) {
    long referenceMask = definition != null
        ? definition.referenceMask()
        : existing != null ? existing.referenceMask() : 0;
    target.putLong(CatalogRecord.TABLE_REFERENCE_MASK_OFFSET, referenceMask);
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      boolean referenced = (referenceMask & 1L << index) != 0;
      int referencedTableId = definition != null
          ? definition.referenceTableId(index)
          : existing != null ? existing.referenceTableId(index) : 0;
      target.putInt(
          CatalogRecord.TABLE_REFERENCE_IDS_OFFSET + index * Integer.BYTES,
          referenced ? referencedTableId : 0);
    }
  }

  static void writeTypes(
      ByteBuffer target,
      int columnCount,
      TableSchema definition,
      TableDefinition existing) {
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      int descriptor = index >= columnCount
          ? 0
          : definition != null
              ? definition.typeDescriptor(index)
              : existing != null ? existing.typeDescriptor(index) : SqlTypeDescriptor.BIGINT;
      target.putInt(
          CatalogRecord.TABLE_TYPE_DESCRIPTORS_OFFSET + index * Integer.BYTES,
          descriptor);
    }
  }

  static void writeDefaultKinds(
      ByteBuffer target,
      TableSchema definition,
      TableDefinition existing) {
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      int kind = definition != null
          ? definition.defaultKind(index)
          : existing != null ? existing.defaultKind(index) : 0;
      target.put(CatalogRecord.TABLE_DEFAULT_KINDS_OFFSET + index, (byte) kind);
    }
  }

  static void writeIndexes(
      ByteBuffer target,
      int indexCount,
      int existingCount,
      int overrideSlot,
      int tableId,
      int state,
      int column,
      TableDefinition existing,
      boolean unique,
      boolean constraint) {
    for (int index = 0; index < indexCount; index++) {
      writeIndex(
          target,
          index,
          existingCount,
          overrideSlot,
          tableId,
          state,
          column,
          existing,
          unique,
          constraint);
    }
  }

  static void writeName(ByteBuffer target, int offset, CharSequence name) {
    for (int index = 0; index < name.length(); index++) {
      target.put(offset + index, (byte) name.charAt(index));
    }
  }

  private static void writeIndex(
      ByteBuffer target,
      int index,
      int existingCount,
      int overrideSlot,
      int tableId,
      int state,
      int column,
      TableDefinition existing,
      boolean unique,
      boolean constraint) {
    int output = CatalogRecord.TABLE_INDEXES_OFFSET + index * 16;
    boolean override = index == overrideSlot
        || existing == null && index == 0
        || existing != null && index == existingCount;
    if (override) {
      writeIndexValues(target, output, tableId, state, column, unique, constraint);
    } else {
      writeIndexValues(
          target,
          output,
          existing.uniqueIndexTableId(index),
          existing.uniqueIndexState(index),
          existing.uniqueIndexColumn(index),
          existing.indexIsUnique(index),
          existing.indexIsConstraint(index));
    }
  }

  private static void writeIndexValues(
      ByteBuffer target,
      int output,
      int tableId,
      int state,
      int column,
      boolean unique,
      boolean constraint) {
    target.putInt(output, tableId);
    target.putInt(output + 4, state);
    target.putInt(output + 8, column);
    int flags = unique ? 1 : 0;
    if (constraint) {
      flags |= 2;
    }
    target.putInt(output + 12, flags);
  }
}
