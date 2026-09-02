package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Transitional row layout used only by the legacy table-definition path. */
final class TableDefinitionRowLayout {
  private TableDefinitionRowLayout() { }

  static int nullBitmapOffset(TableDefinition table) {
    if (table.columnCount <= 1) return 0;
    ensureOffsets(table);
    int last = table.columnCount - 1;
    return table.valueOffsets[last] + valueBytes(table.typeDescriptors[last]);
  }

  static int nullBitmapBytes(TableDefinition table) {
    return (table.columnCount + Byte.SIZE - 1) / Byte.SIZE;
  }

  static int fixedBytes(TableDefinition table) {
    return nullBitmapOffset(table) + nullBitmapBytes(table);
  }

  static int maximumBytes(TableDefinition table) {
    int bytes = fixedBytes(table);
    for (int column = 1; column < table.columnCount; column++) {
      if (table.isVarchar(column)) {
        bytes += SqlTypeDescriptor.parameterOne(table.typeDescriptors[column]) * 4;
      }
    }
    return bytes;
  }

  static int valueOffset(TableDefinition table, int column) {
    if (column <= 0 || column >= table.columnCount) return -1;
    ensureOffsets(table);
    int base = table.valueOffsets[column];
    return SqlTypeDescriptor.isWideDecimal(table.typeDescriptors[column])
        ? base + Long.BYTES : base;
  }

  static int highValueOffset(TableDefinition table, int column) {
    if (column <= 0 || column >= table.columnCount) return -1;
    ensureOffsets(table);
    return table.valueOffsets[column];
  }

  static void deriveOffsets(TableDefinition table) {
    int offset = 0;
    for (int column = 1; column < table.columnCount; column++) {
      table.valueOffsets[column] = offset;
      offset += valueBytes(table.typeDescriptors[column]);
    }
    table.layoutColumns = table.columnCount;
  }

  private static void ensureOffsets(TableDefinition table) {
    if (table.layoutColumns != table.columnCount) deriveOffsets(table);
  }

  private static int valueBytes(int descriptor) {
    return SqlTypeDescriptor.isWideDecimal(descriptor) ? Long.BYTES * 2 : Long.BYTES;
  }
}
