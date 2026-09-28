package io.riverdb.engine.relational;

import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;

/** Checks only row bytes that a decoder will access. */
final class StoredTableRowBounds {
  private StoredTableRowBounds() {
  }

  static boolean fixedPrefix(TableDescriptor table, int length) {
    int fixedEnd = StoredTableRowEncoder.fixedEnd(table);
    return length >= fixedEnd && length <= table.encodedMaximumRowBytes();
  }

  static int publishedTextBytes(
      TableDescriptor table, ByteBuffer source, int start, int length,
      StoredTableColumnSelection selection) {
    int fixedEnd = StoredTableRowEncoder.fixedEnd(table);
    int total = 0;
    int selected = selection == null ? table.columnCount() : selection.count();
    for (int position = 0; position < selected; position++) {
      int index = selection == null ? position : selection.columnAt(position);
      if (!StoredTableRowEncoder.isText(table.typeDescriptorAt(index))
          || StoredTableRowAccess.nullAt(source, start, index)) continue;
      int slot = start + table.fixedOffsetAt(index);
      int offset = FormatBytes.getInt(source, slot);
      int bytes = FormatBytes.getInt(source, slot + Integer.BYTES);
      if (offset < fixedEnd || bytes < 0 || offset > length - bytes
          || total > Integer.MAX_VALUE - bytes) {
        return -1;
      }
      total += bytes;
    }
    return total;
  }
}
