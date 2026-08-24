package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Column metadata and default-value access for a table definition. */
final class TableDefinitionColumnView {
  private TableDefinitionColumnView() { }

  static CharSequence keyName(TableDefinition table) { return table.keyColumnName; }
  static CharSequence valueName(TableDefinition table) { return table.valueColumnName; }
  static boolean matchesKey(TableDefinition table, CharSequence name) {
    return table.keyColumnName.matches(name);
  }
  static boolean matchesValue(TableDefinition table, CharSequence name) {
    return table.valueColumnName.matches(name);
  }
  static int count(TableDefinition table) { return table.columnCount; }
  static CharSequence name(TableDefinition table, int index) {
    return index >= 0 && index < table.columnCount ? table.columnNameAt(index) : null;
  }
  static boolean nullable(TableDefinition table, int column) {
    return column >= 0
        && column < table.columnCount
        && (table.notNullMask & 1L << column) == 0;
  }
  static boolean hasDefault(TableDefinition table, int column) {
    return column > 0
        && column < table.columnCount
        && (table.defaultMask & 1L << column) != 0;
  }
  static boolean varchar(TableDefinition table, int column) {
    return column > 0
        && column < table.columnCount
        && SqlTypeDescriptor.typeId(table.typeDescriptors[column])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
  static int typeDescriptor(TableDefinition table, int column) {
    return column >= 0 && column < table.columnCount ? table.typeDescriptors[column] : 0;
  }
  static boolean supportsSecondaryIndex(TableDefinition table, int column) {
    return column > 0 && column < table.columnCount;
  }
  static long defaultValue(TableDefinition table, int column) {
    return hasDefault(table, column) ? table.defaultValues[column] : 0;
  }
  static int defaultKind(TableDefinition table, int column) {
    return hasDefault(table, column) ? Byte.toUnsignedInt(table.defaultKinds[column]) : 0;
  }
  static int defaultTextLength(TableDefinition table, int column) {
    if (!hasDefault(table, column) || !varchar(table, column)) {
      return -1;
    }
    long handle = table.defaultValues[column];
    int offset = (int) (handle >>> 32);
    int length = (int) handle;
    return offset >= 0 && length >= 0 && offset <= table.defaultTextBytesUsed - length
        ? length : -1;
  }
  static int copyDefaultText(TableDefinition table, int column, ByteBuffer target) {
    int length = defaultTextLength(table, column);
    if (length < 0 || target == null || target.remaining() < length) {
      return -1;
    }
    int offset = (int) (table.defaultValues[column] >>> 32);
    target.put(table.defaultTextBytes, offset, length);
    return length;
  }
  static int defaultTextBytes(TableDefinition table) { return table.defaultTextBytesUsed; }
  static byte defaultTextByte(TableDefinition table, int index) {
    return index >= 0 && index < table.defaultTextBytesUsed
        ? table.defaultTextBytes[index] : 0;
  }
  static int findColumn(TableDefinition table, CharSequence name) {
    for (int index = 0; index < table.columnCount; index++) {
      if (table.columnNameAt(index).matches(name)) {
        return index;
      }
    }
    return -1;
  }
  static int fixedRowBytes(TableDefinition table) { return table.columnCount * Long.BYTES; }
  static int maximumRowBytes(TableDefinition table) {
    int bytes = fixedRowBytes(table);
    for (int column = 1; column < table.columnCount; column++) {
      if (varchar(table, column)) {
        bytes += SqlTypeDescriptor.parameterOne(table.typeDescriptors[column]) * 4;
      }
    }
    return bytes;
  }
  static int nullMaskOffset(TableDefinition table) { return (table.columnCount - 1) * Long.BYTES; }
}
