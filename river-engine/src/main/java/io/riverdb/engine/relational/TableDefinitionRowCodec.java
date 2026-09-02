package io.riverdb.engine.relational;

import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.nio.ByteBuffer;

/** Validates and locates the transitional table-definition row representation. */
final class TableDefinitionRowCodec {
  private TableDefinitionRowCodec() { }

  static boolean isNull(TableDefinition table, ByteBuffer row, int column) {
    if (!hasFixedBytes(table, row) || column <= 0 || column >= table.columnCount) return false;
    int byteIndex = column >>> 3;
    int bit = 1 << (column & 7);
    return (row.get(row.position() + table.nullBitmapOffset() + byteIndex) & bit) != 0;
  }

  static boolean isValidRow(TableDefinition table, ByteBuffer row) {
    if (!hasFixedBytes(table, row) || row.remaining() > table.maximumRowBytes()
        || !validNulls(table, row)) return false;
    int base = row.position();
    int payloadOffset = table.fixedRowBytes();
    for (int column = 1; column < table.columnCount; column++) {
      int valueOffset = table.valueOffset(column);
      long slot = row.getLong(base + valueOffset);
      if (!table.isVarchar(column)) {
        boolean valid = SqlTypeDescriptor.isWideDecimal(table.typeDescriptors[column])
            ? ExactDecimal128.fits(
                row.getLong(base + table.highValueOffset(column)), slot,
                SqlTypeDescriptor.parameterOne(table.typeDescriptors[column]))
            : SqlValueDomain.validFixed(table.typeDescriptors[column], slot);
        boolean clear = slot == 0 && (!SqlTypeDescriptor.isWideDecimal(
            table.typeDescriptors[column])
            || row.getLong(base + table.highValueOffset(column)) == 0);
        if (isNull(table, row, column) ? !clear : !valid) {
          return false;
        }
      } else if (isNull(table, row, column)) {
        if (slot != 0) return false;
      } else {
        int offset = (int) (slot >>> 32);
        int length = (int) slot;
        if (offset != payloadOffset || length < 0 || offset > row.remaining() - length
            || Utf8Text.validate(row, base + offset, length,
                SqlTypeDescriptor.parameterOne(table.typeDescriptors[column])) < 0) return false;
        payloadOffset += length;
      }
    }
    return payloadOffset == row.remaining();
  }

  static int textOffset(TableDefinition table, ByteBuffer row, int column) {
    if (!table.isVarchar(column) || isNull(table, row, column)) return -1;
    return (int) (row.getLong(row.position() + table.valueOffset(column)) >>> 32);
  }

  static int textLength(TableDefinition table, ByteBuffer row, int column) {
    if (!table.isVarchar(column) || isNull(table, row, column)) return -1;
    return (int) row.getLong(row.position() + table.valueOffset(column));
  }

  private static boolean hasFixedBytes(TableDefinition table, ByteBuffer row) {
    return table.available
        && row != null
        && row.remaining() >= table.fixedRowBytes()
        && row.remaining() <= table.maximumRowBytes();
  }

  private static boolean validNulls(TableDefinition table, ByteBuffer row) {
    int offset = row.position() + table.nullBitmapOffset();
    for (int column = 0; column < table.columnCount; column++) {
      int bit = row.get(offset + (column >>> 3)) & 1 << (column & 7);
      if (bit != 0 && (column == 0 || table.notNullColumns.get(column))) return false;
    }
    int used = table.columnCount & 7;
    return used == 0 || (row.get(offset + table.nullBitmapBytes() - 1)
        & ~((1 << used) - 1)) == 0;
  }
}
