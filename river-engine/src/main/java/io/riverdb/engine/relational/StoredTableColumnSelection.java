package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.row.StoredTableRowHeaderCodec;
import io.riverdb.storage.heap.HeapRowProjection;
import java.util.Arrays;

/** Reusable statement-owned column demand, stable while a scan reads rows. */
public final class StoredTableColumnSelection {
  private boolean[] selected = new boolean[0];
  private int[] ordinals = new int[0];
  private int columns;
  private int count;
  private boolean all = true;
  private final HeapRowProjection projection = new HeapRowProjection();
  private long projectionLayoutId;
  private boolean projectionDirty = true;

  public StatusCode selectNone(int count) {
    if (count < 0 || count > SqlShapeLimits.MAX_TABLE_COLUMNS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (selected.length < count) {
      boolean[] grownSelected;
      int[] grownOrdinals;
      try {
        grownSelected = new boolean[count];
        grownOrdinals = new int[count];
      } catch (OutOfMemoryError exhausted) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      selected = grownSelected;
      ordinals = grownOrdinals;
    } else Arrays.fill(selected, 0, count, false);
    columns = count;
    this.count = 0;
    all = false;
    projectionDirty = true;
    return StatusCode.OK;
  }

  public void select(int column) {
    if (column >= 0 && column < columns && !selected[column]) {
      selected[column] = true;
      ordinals[count++] = column;
      projectionDirty = true;
    }
  }

  public void selectAll() {
    all = true;
    projectionDirty = true;
  }

  public boolean matches(int columnCount) { return columns == columnCount; }

  public boolean includes(int column) {
    return all || column >= 0 && column < columns && selected[column];
  }

  public int count() { return all ? columns : count; }
  public int columnAt(int index) { return all ? index : ordinals[index]; }

  StatusCode prepareProjection(
      TableDescriptor table, StoredTableRowIntegerFilter filter) {
    if (all) return StatusCode.OK;
    if (!matches(table.columnCount())) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (filter != null && filter.active()) select(filter.column());
    if (!projectionDirty && projectionLayoutId == table.rowLayoutId()) return StatusCode.OK;
    StatusCode status = projection.prepare(
        StoredTableRowHeaderCodec.HEADER_BYTES + table.nullBitmapBytes(),
        StoredTableRowEncoder.fixedEnd(table), count);
    for (int index = 0; status.isOk() && index < count; index++) {
      int column = ordinals[index];
      status = projection.add(
          column, table.fixedOffsetAt(column), table.fixedWidthAt(column),
          StoredTableRowEncoder.isText(table.typeDescriptorAt(column)));
    }
    if (status.isOk()) {
      projectionLayoutId = table.rowLayoutId();
      projectionDirty = false;
    }
    return status;
  }

  HeapRowProjection projection() { return all ? null : projection; }

  void selectKey(KeyDescriptor key) {
    for (int part = 0; part < key.partCount(); part++) {
      select(key.columnOrdinalAt(part));
    }
  }
}
