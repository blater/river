package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable bounded sort storage and spill lifecycle for one SQL session. */
final class SqlSortWorkspace {
  private static final int MAXIMUM_ROWS = 1_024;
  private final SqlSortSpill spill = new SqlSortSpill();
  private final HeapRowResult rowView = new HeapRowResult();
  private long[] keys;
  private long[] primaryKeys;
  private long[] values;
  private long[] nullMasks;
  private boolean[] keyNulls;
  private int[] rowSlots;
  private int[] rowLengths;
  private ByteBuffer rows;
  private TableDefinition table;
  private boolean spilled;
  private boolean containsText;
  private boolean descending;
  private int orderColumn;
  private int projectedColumnCount;
  private int rowCount;
  private int totalRows;
  private long outputPrimaryKey;
  private long outputNullMask;

  StatusCode begin(
      TableDefinition definition,
      boolean descendingOrder,
      int orderedColumn,
      int projectionCount,
      boolean textRows) {
    if (definition == null
        || orderedColumn < 0
        || projectionCount <= 0
        || projectionCount > TableSchema.MAXIMUM_COLUMNS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = close();
    if (!status.isOk()) {
      return status;
    }
    ensureStorage();
    table = definition;
    descending = descendingOrder;
    orderColumn = orderedColumn;
    projectedColumnCount = projectionCount;
    containsText = textRows;
    rowCount = 0;
    totalRows = 0;
    spilled = false;
    return spill.begin(
        definition, descendingOrder, textRows, orderedColumn, projectionCount);
  }

  StatusCode append(
      long key,
      boolean keyNull,
      long primaryKey,
      long[] projectedValues,
      long nullMask,
      HeapRowResult source) {
    if (table == null || projectedValues == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = rowCount < MAXIMUM_ROWS
        ? StatusCode.OK
        : spillRun();
    if (!status.isOk()) {
      return status;
    }
    int rowIndex = rowCount++;
    totalRows++;
    keys[rowIndex] = key;
    keyNulls[rowIndex] = keyNull;
    primaryKeys[rowIndex] = primaryKey;
    if (containsText) {
      if (source == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int rowOffset = rowIndex * TableSchema.MAXIMUM_ROW_BYTES;
      rows.position(rowOffset);
      rows.limit(rowOffset + source.length());
      status = source.copyTo(rows);
      rows.clear();
      if (!status.isOk()) {
        rowCount--;
        totalRows--;
        return status;
      }
      rowSlots[rowIndex] = rowIndex;
      rowLengths[rowIndex] = source.length();
    }
    int valueStart = rowIndex * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < projectedColumnCount; index++) {
      values[valueStart + index] = projectedValues[index];
    }
    nullMasks[rowIndex] = nullMask;
    return StatusCode.OK;
  }

  StatusCode finish() {
    if (table == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!spilled) {
      sortRows();
      return StatusCode.OK;
    }
    StatusCode status = spillRun();
    return status.isOk() ? spill.initializeMerge() : status;
  }

  boolean isSpilled() {
    return spilled;
  }

  boolean containsText() {
    return containsText;
  }

  int totalRows() {
    return totalRows;
  }

  boolean hasResources() {
    return table != null || spill.hasResources();
  }

  long primaryKeyAt(int index) {
    return primaryKeys[index];
  }

  long nullMaskAt(int index) {
    return nullMasks[index];
  }

  void copyValuesAt(int row, int count, long[] target) {
    int valueStart = row * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < count; index++) {
      target[index] = values[valueStart + index];
    }
  }

  HeapRowResult rowAt(int index) {
    int slot = rowSlots[index];
    rowView.set(
        rows,
        0,
        slot * TableSchema.MAXIMUM_ROW_BYTES,
        rowLengths[slot]);
    return rowView;
  }

  HeapRowResult spilledRow() {
    return spill.outputRow();
  }

  StatusCode nextSpilled(int count, long[] target) {
    StatusCode status = spill.next(count, target);
    outputPrimaryKey = spill.outputPrimaryKey();
    outputNullMask = spill.outputNullMask();
    return status;
  }

  long outputPrimaryKey() {
    return outputPrimaryKey;
  }

  long outputNullMask() {
    return outputNullMask;
  }

  StatusCode close() {
    StatusCode status = StatusCode.OK;
    status = spill.close();
    if (status.isOk()) {
      table = null;
      spilled = false;
      rowCount = 0;
      totalRows = 0;
    }
    return status;
  }

  private StatusCode spillRun() {
    if (rowCount <= 0) {
      return StatusCode.OK;
    }
    sortRows();
    StatusCode status = spill.writeRun(
        keys, keyNulls, primaryKeys, nullMasks, values,
        rowSlots, rowLengths, rows, rowCount);
    if (status.isOk()) {
      spilled = true;
      rowCount = 0;
    }
    return status;
  }

  private void ensureStorage() {
    if (keys != null) {
      return;
    }
    keys = new long[MAXIMUM_ROWS];
    primaryKeys = new long[MAXIMUM_ROWS];
    values = new long[MAXIMUM_ROWS * TableSchema.MAXIMUM_COLUMNS];
    nullMasks = new long[MAXIMUM_ROWS];
    keyNulls = new boolean[MAXIMUM_ROWS];
    rowSlots = new int[MAXIMUM_ROWS];
    rowLengths = new int[MAXIMUM_ROWS];
    rows = ByteBuffer.allocateDirect(
        MAXIMUM_ROWS * TableSchema.MAXIMUM_ROW_BYTES);
  }

  private void sortRows() {
    for (int root = rowCount / 2 - 1; root >= 0; root--) {
      siftRow(root, rowCount);
    }
    for (int end = rowCount - 1; end > 0; end--) {
      swapRows(0, end);
      siftRow(0, end);
    }
  }

  private void siftRow(int root, int length) {
    int current = root;
    while (current * 2 + 1 < length) {
      int child = current * 2 + 1;
      if (child + 1 < length && compareRows(child, child + 1) < 0) {
        child++;
      }
      if (compareRows(current, child) >= 0) {
        return;
      }
      swapRows(current, child);
      current = child;
    }
  }

  private int compareRows(int left, int right) {
    int comparison;
    if (keyNulls[left] != keyNulls[right]) {
      comparison = keyNulls[left] ? -1 : 1;
    } else {
      comparison = containsText && table.isVarchar(orderColumn)
          ? compareText(left, right)
          : Long.compare(keys[left], keys[right]);
    }
    if (comparison == 0) {
      comparison = Long.compare(primaryKeys[left], primaryKeys[right]);
    }
    return descending ? -comparison : comparison;
  }

  private int compareText(int left, int right) {
    long leftHandle = keys[left];
    long rightHandle = keys[right];
    int leftOffset = rowSlots[left] * TableSchema.MAXIMUM_ROW_BYTES
        + (int) (leftHandle >>> 32);
    int rightOffset = rowSlots[right] * TableSchema.MAXIMUM_ROW_BYTES
        + (int) (rightHandle >>> 32);
    int leftLength = (int) leftHandle;
    int rightLength = (int) rightHandle;
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(rows.get(leftOffset + index)),
          Byte.toUnsignedInt(rows.get(rightOffset + index)));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftLength, rightLength);
  }

  private void swapRows(int left, int right) {
    long key = keys[left];
    keys[left] = keys[right];
    keys[right] = key;
    long primaryKey = primaryKeys[left];
    primaryKeys[left] = primaryKeys[right];
    primaryKeys[right] = primaryKey;
    long nullMask = nullMasks[left];
    nullMasks[left] = nullMasks[right];
    nullMasks[right] = nullMask;
    boolean keyNull = keyNulls[left];
    keyNulls[left] = keyNulls[right];
    keyNulls[right] = keyNull;
    int rowSlot = rowSlots[left];
    rowSlots[left] = rowSlots[right];
    rowSlots[right] = rowSlot;
    int leftStart = left * TableSchema.MAXIMUM_COLUMNS;
    int rightStart = right * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < projectedColumnCount; index++) {
      long value = values[leftStart + index];
      values[leftStart + index] = values[rightStart + index];
      values[rightStart + index] = value;
    }
  }
}
