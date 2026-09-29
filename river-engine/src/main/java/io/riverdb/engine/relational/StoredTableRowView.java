package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/**
 * Reusable read-only descriptor row. Its bytes belong to the retained heap result of this view
 * or its owning scan cursor and remain valid until the role advances, resets or closes.
 */
public final class StoredTableRowView
    implements SqlValueAccess, io.riverdb.base.text.BoundedByteSource {
  private final HeapRowResult fetched = new HeapRowResult();
  private TableDescriptor table;
  private ByteBuffer bytes;
  private StoredTableColumnSelection selection;
  private int start;
  private int length;

  HeapRowResult fetched() { return fetched; }

  void borrowFrom(StoredTableRowView owner) {
    reset();
    table = owner.table;
    bytes = owner.bytes;
    selection = owner.selection;
    start = owner.start;
    length = owner.length;
  }

  StatusCode bindFetched(
      TableDescriptor descriptor, HeapRowResult source,
      StoredTableRowIntegerFilter filter, StoredTableColumnSelection selected) {
    ByteBuffer retained = source.retainedReadOnlyBytes();
    if (retained == null) {
      if (source != fetched) fetched.copyFrom(source);
      fetched.retentionProjection(selected == null ? null : selected.projection());
      StatusCode status = fetched.retainBytes();
      if (!status.isOk()) return status;
      source = fetched;
      retained = source.retainedReadOnlyBytes();
    }
    return bind(descriptor, retained, 0, source.length(), filter, selected);
  }

  StatusCode bind(
      TableDescriptor descriptor, ByteBuffer source, int offset, int rowLength,
      StoredTableRowIntegerFilter filter, StoredTableColumnSelection selected) {
    if (descriptor == null || source == null || !source.isReadOnly()
        || offset < 0 || rowLength < 0
        || selected != null && !selected.matches(descriptor.columnCount())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (rowLength > HeapPage.MAXIMUM_ROW_BYTES
        || offset > source.limit() - rowLength
        || !StoredTableRowBounds.fixedPrefix(descriptor, rowLength)) {
      return StatusCode.CORRUPTION;
    }
    if (filter != null) {
      StatusCode status = filter.test(descriptor, source, offset);
      if (!status.isOk()) return status;
    }
    if (StoredTableRowBounds.publishedTextBytes(
        descriptor, source, offset, rowLength, selected) < 0) {
      return StatusCode.CORRUPTION;
    }
    table = descriptor;
    bytes = source;
    selection = selected;
    start = offset;
    length = rowLength;
    return StatusCode.OK;
  }

  public void reset() {
    table = null;
    bytes = null;
    selection = null;
    start = 0;
    length = 0;
    fetched.reset();
  }

  public int count() { return table == null ? 0 : table.columnCount(); }

  boolean boundTo(TableDescriptor descriptor) {
    return table == descriptor && selection == null;
  }

  public int descriptorAt(int column) {
    return available(column) ? table.typeDescriptorAt(column) : 0;
  }

  public boolean isNull(int column) {
    return available(column) && StoredTableRowAccess.nullAt(bytes, start, column);
  }

  public long valueAt(int column) {
    if (!available(column) || isNull(column)) return 0;
    if (textColumn(column)) return 0;
    int slot = start + table.fixedOffsetAt(column);
    return SqlTypeDescriptor.isWideDecimal(table.typeDescriptorAt(column))
        ? StoredTableRowAccess.wideLow(bytes, slot)
        : StoredTableRowAccess.fixedValue(table, column, bytes, slot);
  }

  public long highValueAt(int column) {
    if (!available(column) || isNull(column)) return 0;
    int slot = start + table.fixedOffsetAt(column);
    return SqlTypeDescriptor.isWideDecimal(table.typeDescriptorAt(column))
        ? StoredTableRowAccess.wideHigh(bytes, slot) : valueAt(column) >> 63;
  }

  public int textByteLengthAt(int column) {
    return textColumn(column) && !isNull(column)
        ? FormatBytes.getInt(bytes, start + table.fixedOffsetAt(column) + Integer.BYTES)
        : -1;
  }

  public int textByteOffsetAt(int column) {
    return textColumn(column) && !isNull(column)
        ? FormatBytes.getInt(bytes, start + table.fixedOffsetAt(column)) : -1;
  }

  @Override
  public io.riverdb.base.text.BoundedByteSource textSource(int column) { return this; }

  @Override
  public int copyTextChars(int column, char[] destination, int destinationOffset) {
    int offset = textByteOffsetAt(column);
    int bytes = textByteLengthAt(column);
    return offset < 0 || bytes < 0 ? -1
        : Utf8Text.decode(this.bytes, start + offset, bytes, destination, destinationOffset);
  }

  @Override
  public byte getByte(int offset) { return bytes.get(start + offset); }

  public int length() { return length; }

  private boolean textColumn(int column) {
    return available(column)
        && SqlTypeDescriptor.typeId(table.typeDescriptorAt(column))
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private boolean available(int column) {
    return table != null && column >= 0 && column < table.columnCount()
        && (selection == null || selection.includes(column));
  }
}
