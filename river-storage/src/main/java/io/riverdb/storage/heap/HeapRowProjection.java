package io.riverdb.storage.heap;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.row.StoredTableRowHeaderCodec;

/** Reusable byte positions needed before a heap page pin can be released. */
public final class HeapRowProjection {
  private int[] columns = new int[0];
  private int[] offsets = new int[0];
  private int[] widths = new int[0];
  private boolean[] variable = new boolean[0];
  private int metadataBytes;
  private int fixedBytes;
  private int expectedCount;
  private int count;

  public StatusCode prepare(int metadataEnd, int fixedEnd, int selectedCount) {
    if (metadataEnd < StoredTableRowHeaderCodec.HEADER_BYTES || fixedEnd < metadataEnd
        || fixedEnd > HeapPage.MAXIMUM_ROW_BYTES || selectedCount < 0
        || selectedCount > HeapPage.MAXIMUM_ROW_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (columns.length < selectedCount) {
      int[] grownColumns;
      int[] grownOffsets;
      int[] grownWidths;
      boolean[] grownVariable;
      try {
        grownColumns = new int[selectedCount];
        grownOffsets = new int[selectedCount];
        grownWidths = new int[selectedCount];
        grownVariable = new boolean[selectedCount];
      } catch (OutOfMemoryError exhausted) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      columns = grownColumns;
      offsets = grownOffsets;
      widths = grownWidths;
      variable = grownVariable;
    }
    metadataBytes = metadataEnd;
    fixedBytes = fixedEnd;
    expectedCount = selectedCount;
    count = 0;
    return StatusCode.OK;
  }

  public StatusCode add(int column, int offset, int width, boolean text) {
    if (count >= expectedCount || column < 0
        || column >= (metadataBytes - StoredTableRowHeaderCodec.HEADER_BYTES) * 8
        || offset < metadataBytes || width <= 0 || width > fixedBytes - offset
        || text && width != 8) return StatusCode.INVALID_EXTERNAL_INPUT;
    columns[count] = column;
    offsets[count] = offset;
    widths[count] = width;
    variable[count] = text;
    count++;
    return StatusCode.OK;
  }

  public int metadataBytes() { return metadataBytes; }
  public int fixedBytes() { return fixedBytes; }
  public int count() { return count; }
  public int column(int index) { return columns[index]; }
  public int offset(int index) { return offsets[index]; }
  public int width(int index) { return widths[index]; }
  public boolean variable(int index) { return variable[index]; }
}
