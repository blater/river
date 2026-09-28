package io.riverdb.engine.row;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.row.StoredTableRowHeader;
import io.riverdb.format.row.StoredTableRowHeaderCodec;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Full admission for arbitrary caller-supplied encoded rows. */
final class StoredTableRowExternalAdmission {
  private final StoredTableRowHeader header = new StoredTableRowHeader();

  StatusCode validate(
      TableDescriptor table, long rowId, ByteBuffer source, int start, int length) {
    if (table == null || table.rowLayoutId() <= 0 || rowId <= 0 || source == null
        || start < 0 || length < 0 || start > source.limit()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (length < StoredTableRowHeaderCodec.HEADER_BYTES
        || length > HeapPage.MAXIMUM_ROW_BYTES || start > source.limit() - length
        || !StoredTableRowBounds.fixedPrefix(table, length)) return StatusCode.CORRUPTION;
    StatusCode status = StoredTableRowHeaderCodec.decode(source, start, rowId, header);
    if (!status.isOk() || header.rowLayoutId() != table.rowLayoutId()
        || !canonicalBitmap(table, source, start)) return StatusCode.CORRUPTION;
    int nextText = StoredTableRowEncoder.fixedEnd(table);
    for (int index = 0; index < table.columnCount(); index++) {
      int next = validateSlot(table, source, start, length, index, nextText);
      if (next < 0) return StatusCode.CORRUPTION;
      nextText = next;
    }
    return nextText == length ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static int validateSlot(
      TableDescriptor table, ByteBuffer source, int start, int length,
      int index, int textOffset) {
    boolean isNull = StoredTableRowAccess.nullAt(source, start, index);
    if (isNull && !table.isNullable(index)) return -1;
    int slot = start + table.fixedOffsetAt(index);
    if (isNull) {
      for (int offset = 0; offset < table.fixedWidthAt(index); offset++) {
        if (source.get(slot + offset) != 0) return -1;
      }
      return textOffset;
    }
    int descriptor = table.typeDescriptorAt(index);
    if (!StoredTableRowEncoder.isText(descriptor)) {
      if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
        return SqlValueDomain.fitsDecimal128(descriptor,
            StoredTableRowAccess.wideHigh(source, slot),
            StoredTableRowAccess.wideLow(source, slot)) ? textOffset : -1;
      }
      return SqlValueDomain.fitsFixed(
          descriptor, StoredTableRowAccess.fixedValue(table, index, source, slot))
          ? textOffset : -1;
    }
    int offset = FormatBytes.getInt(source, slot);
    int bytes = FormatBytes.getInt(source, slot + Integer.BYTES);
    if (offset != textOffset || bytes < 0 || textOffset > length - bytes) return -1;
    return Utf8Text.validate(source, start + textOffset, bytes,
        SqlTypeDescriptor.parameterOne(descriptor)) < 0 ? -1 : textOffset + bytes;
  }

  private static boolean canonicalBitmap(
      TableDescriptor table, ByteBuffer source, int start) {
    int remainder = table.columnCount() & 7;
    if (remainder == 0) return true;
    int last = start + StoredTableRowHeaderCodec.HEADER_BYTES + table.nullBitmapBytes() - 1;
    return (Byte.toUnsignedInt(source.get(last)) & (0xff << remainder)) == 0;
  }
}
