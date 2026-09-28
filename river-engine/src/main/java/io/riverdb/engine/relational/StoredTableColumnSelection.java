package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.schema.KeyDescriptor;
import java.util.Arrays;

/** Reusable statement-owned column demand, stable while a scan reads rows. */
public final class StoredTableColumnSelection {
  private boolean[] selected = new boolean[0];
  private int[] ordinals = new int[0];
  private int columns;
  private int count;
  private boolean all = true;

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
    return StatusCode.OK;
  }

  public void select(int column) {
    if (column >= 0 && column < columns && !selected[column]) {
      selected[column] = true;
      ordinals[count++] = column;
    }
  }

  public void selectAll() { all = true; }

  public boolean matches(int columnCount) { return columns == columnCount; }

  public boolean includes(int column) {
    return all || column >= 0 && column < columns && selected[column];
  }

  public int count() { return all ? columns : count; }
  public int columnAt(int index) { return all ? index : ordinals[index]; }

  void selectKey(KeyDescriptor key) {
    for (int part = 0; part < key.partCount(); part++) {
      select(key.columnOrdinalAt(part));
    }
  }
}
