package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Validates and locates the fixed-width row representation of a table definition. */
final class TableDefinitionRowCodec {
  private TableDefinitionRowCodec() { }

  static boolean isNull(TableDefinition table, ByteBuffer row, int column) {
    return row != null && column > 0 && column < table.columnCount
        && row.remaining() >= table.fixedRowBytes() && row.remaining() <= table.maximumRowBytes()
        && (row.getLong(row.position() + table.nullMaskOffset()) & 1L << column) != 0;
  }

  static boolean isValidRow(TableDefinition table, ByteBuffer row) {
    if (row == null || row.remaining() < table.fixedRowBytes()
        || row.remaining() > table.maximumRowBytes()) return false;
    long nullMask = row.getLong(row.position() + table.nullMaskOffset());
    if (!table.isValidNullMask(nullMask)) return false;
    int base = row.position();
    int payloadOffset = table.fixedRowBytes();
    for (int column = 1; column < table.columnCount; column++) {
      long slot = row.getLong(base + (column - 1) * Long.BYTES);
      if (!table.isVarchar(column)) {
        if ((nullMask & 1L << column) != 0
            ? slot != 0 : !TableSchema.validFixedValue(table.typeDescriptors[column], slot)) return false;
        continue;
      }
      if ((nullMask & 1L << column) != 0) { if (slot != 0) return false; continue; }
      int offset = (int) (slot >>> 32), length = (int) slot;
      if (offset != payloadOffset || length < 0 || offset > row.remaining() - length
          || io.riverdb.base.text.Utf8Text.validate(row, base + offset, length,
              SqlTypeDescriptor.parameterOne(table.typeDescriptors[column])) < 0) return false;
      payloadOffset += length;
    }
    return payloadOffset == row.remaining();
  }

  static int textOffset(TableDefinition table, ByteBuffer row, int column) {
    if (!table.isVarchar(column) || isNull(table, row, column)) return -1;
    return (int) (row.getLong(row.position() + (column - 1) * Long.BYTES) >>> 32);
  }

  static int textLength(TableDefinition table, ByteBuffer row, int column) {
    if (!table.isVarchar(column) || isNull(table, row, column)) return -1;
    return (int) row.getLong(row.position() + (column - 1) * Long.BYTES);
  }
}
