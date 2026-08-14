package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Encodes the current bounded catalog table record. */
final class CatalogTableEncoder {
  private CatalogTableEncoder() {
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      CharSequence name) {
    encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexTableId == 0
            ? TableDefinition.INDEX_NONE : TableDefinition.INDEX_READY,
        name,
        "key",
        "value");
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name) {
    encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        name,
        "key",
        "value");
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName) {
    int indexColumn = uniqueValueIndexTableId == 0 ? -1 : 1;
    int offset = encodeTableHeader(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        2,
        1L,
        0,
        null,
        null,
        true,
        false);
    offset = encodeColumn(target, offset, keyColumnName);
    offset = encodeColumn(target, offset, valueColumnName);
    finish(target, offset);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableSchema schema) {
    int offset = encodeTableHeader(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema.columnCount(),
        schema.notNullMask(),
        schema.defaultMask(),
        schema,
        null,
        true,
        false);
    offset = encodeColumnsAndDefaults(target, offset, schema);
    finish(target, offset);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema) {
    encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema,
        true,
        false);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema,
      boolean unique) {
    encode(
        target,
        tableId,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        name,
        schema,
        unique,
        false);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema,
      boolean unique,
      boolean constraint) {
    int offset = encodeTableHeader(
        target,
        tableId,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        name,
        schema.columnCount(),
        schema.notNullMask(),
        schema.defaultMask(),
        null,
        schema,
        unique,
        constraint);
    for (int index = 0; index < schema.columnCount(); index++) {
      offset = encodeColumn(target, offset, schema.columnName(index));
    }
    for (int index = 0; index < schema.defaultTextBytes(); index++) {
      target.put(offset++, schema.defaultTextByte(index));
    }
    finish(target, offset);
  }

  static void encodeDropping(
      ByteBuffer target,
      int tableId,
      CharSequence name,
      TableDefinition schema) {
    encode(
        target,
        tableId,
        0,
        TableDefinition.INDEX_NONE,
        -1,
        name,
        schema);
    target.putLong(0, CatalogRecord.DROPPING_TABLE_MAGIC);
  }

  private static int encodeTableHeader(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      int indexColumn,
      CharSequence name,
      int columnCount,
      long notNullMask,
      long defaultMask,
      TableSchema definition,
      TableDefinition existing,
      boolean unique,
      boolean constraint) {
    clear(target);
    int existingCount = existing == null ? 0 : existing.uniqueIndexCount();
    int overrideSlot = indexOverrideSlot(
        existing, existingCount, indexTableId, indexColumn);
    int indexCount = existingCount
        + (indexTableId > 0 && overrideSlot < 0 ? 1 : 0);
    if (existing == null && indexTableId > 0) {
      indexCount = 1;
    }
    writeTableHeader(
        target,
        tableId,
        name.length(),
        columnCount,
        indexCount,
        notNullMask,
        defaultMask,
        definition,
        existing);
    writeTableChecks(target, definition, existing);
    writeTableDefaults(target, definition, existing);
    writeTableReferences(target, definition, existing);
    writeTableTypes(target, columnCount, definition, existing);
    writeTableIndexes(
        target,
        indexCount,
        existingCount,
        overrideSlot,
        indexTableId,
        indexState,
        indexColumn,
        existing,
        unique,
        constraint);
    int nameOffset = CatalogRecord.TABLE_INDEXES_OFFSET + indexCount * 16;
    writeName(target, nameOffset, name);
    return nameOffset + name.length();
  }

  private static int indexOverrideSlot(
      TableDefinition existing,
      int existingCount,
      int tableId,
      int column) {
    if (tableId <= 0 || existing == null) {
      return -1;
    }
    for (int index = 0; index < existingCount; index++) {
      if (existing.uniqueIndexTableId(index) == tableId
          || existing.uniqueIndexColumn(index) == column) {
        return index;
      }
    }
    return -1;
  }

  private static void writeTableHeader(
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

  private static void writeTableChecks(
      ByteBuffer target,
      TableSchema definition,
      TableDefinition existing) {
    long checkMask = definition != null
        ? definition.checkMask() : existing != null ? existing.checkMask() : 0;
    target.putLong(CatalogRecord.TABLE_CHECK_MASK_OFFSET, checkMask);
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      writeTableCheck(target, definition, existing, checkMask, index);
    }
  }

  private static void writeTableCheck(
      ByteBuffer target,
      TableSchema definition,
      TableDefinition existing,
      long checkMask,
      int index) {
    boolean checked = (checkMask & 1L << index) != 0;
    int comparison = definition != null
        ? definition.checkComparison(index)
        : existing != null ? existing.checkComparison(index) : 0;
    long value = definition != null
        ? definition.checkValue(index)
        : existing != null ? existing.checkValue(index) : 0;
    target.putInt(
        CatalogRecord.TABLE_CHECKS_OFFSET + index * Integer.BYTES,
        checked ? comparison : 0);
    target.putLong(
        CatalogRecord.TABLE_CHECK_VALUES_OFFSET + index * Long.BYTES,
        checked ? value : 0);
  }

  private static void writeTableDefaults(
      ByteBuffer target,
      TableSchema definition,
      TableDefinition existing) {
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      long value = definition != null
          ? definition.defaultValue(index)
          : existing != null ? existing.defaultValue(index) : 0;
      target.putLong(CatalogRecord.TABLE_DEFAULTS_OFFSET + index * Long.BYTES, value);
    }
  }

  private static void writeTableReferences(
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

  private static void writeTableTypes(
      ByteBuffer target,
      int columnCount,
      TableSchema definition,
      TableDefinition existing) {
    for (int index = 0; index < TableSchema.MAXIMUM_COLUMNS; index++) {
      int descriptor = index >= columnCount
          ? 0
          : definition != null
              ? definition.typeDescriptor(index)
              : existing != null
                  ? existing.typeDescriptor(index)
                  : SqlTypeDescriptor.BIGINT;
      target.putInt(
          CatalogRecord.TABLE_TYPE_DESCRIPTORS_OFFSET + index * Integer.BYTES,
          descriptor);
    }
  }

  private static void writeTableIndexes(
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
      writeTableIndex(
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

  private static void writeTableIndex(
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

  private static int encodeColumnsAndDefaults(
      ByteBuffer target,
      int offset,
      TableSchema schema) {
    for (int index = 0; index < schema.columnCount(); index++) {
      offset = encodeColumn(target, offset, schema.columnName(index));
    }
    for (int index = 0; index < schema.defaultTextBytes(); index++) {
      target.put(offset++, schema.defaultTextByte(index));
    }
    return offset;
  }

  private static void writeName(ByteBuffer target, int offset, CharSequence name) {
    for (int index = 0; index < name.length(); index++) {
      target.put(offset + index, (byte) name.charAt(index));
    }
  }

  private static int encodeColumn(ByteBuffer target, int offset, CharSequence name) {
    target.putInt(offset, name.length());
    offset += Integer.BYTES;
    for (int index = 0; index < name.length(); index++) {
      target.put(offset + index, (byte) name.charAt(index));
    }
    return offset + name.length();
  }

  private static void clear(ByteBuffer target) {
    target.clear();
    for (int index = 0; index < target.capacity(); index++) {
      target.put(index, (byte) 0);
    }
  }

  private static void finish(ByteBuffer target, int offset) {
    target.position(0);
    target.limit(offset);
  }
}
