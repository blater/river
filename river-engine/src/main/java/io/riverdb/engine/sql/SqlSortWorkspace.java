package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/** Reusable bounded sort storage and spill lifecycle for one SQL session. */
final class SqlSortWorkspace {
  private static final int MAXIMUM_ROWS = 1_024;
  private static final int MAXIMUM_RUNS = 64;
  private static final int MAXIMUM_RECORD_BYTES =
      (TableSchema.MAXIMUM_COLUMNS + 3) * Long.BYTES + Integer.BYTES;

  private final long[] runOffsets = new long[MAXIMUM_RUNS];
  private final long[] runReadOffsets = new long[MAXIMUM_RUNS];
  private final int[] runRowCounts = new int[MAXIMUM_RUNS];
  private final int[] runRowsRemaining = new int[MAXIMUM_RUNS];
  private final long[] mergeKeys = new long[MAXIMUM_RUNS];
  private final long[] mergePrimaryKeys = new long[MAXIMUM_RUNS];
  private final long[] mergeNullMasks = new long[MAXIMUM_RUNS];
  private final boolean[] mergeKeyNulls = new boolean[MAXIMUM_RUNS];
  private final long[] mergeValues =
      new long[MAXIMUM_RUNS * TableSchema.MAXIMUM_COLUMNS];
  private final boolean[] runActive = new boolean[MAXIMUM_RUNS];
  private final ByteBuffer record = ByteBuffer.allocateDirect(MAXIMUM_RECORD_BYTES);
  private final CRC32C checksum = new CRC32C();
  private final HeapRowResult rowView = new HeapRowResult();
  private long[] keys;
  private long[] primaryKeys;
  private long[] values;
  private long[] nullMasks;
  private boolean[] keyNulls;
  private int[] rowSlots;
  private int[] rowLengths;
  private ByteBuffer rows;
  private FileChannel spillChannel;
  private Path spillPath;
  private TableDefinition table;
  private boolean spilled;
  private boolean containsText;
  private boolean descending;
  private int orderColumn;
  private int projectedColumnCount;
  private int runCount;
  private int rowCount;
  private int totalRows;
  private long spillWriteOffset;
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
    runCount = 0;
    spillWriteOffset = 0;
    spilled = false;
    return StatusCode.OK;
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
        : containsText ? StatusCode.RESOURCE_EXHAUSTED : spillRun();
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
    return status.isOk() ? initializeMerge() : status;
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
    return table != null || spillChannel != null || spillPath != null;
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

  StatusCode nextSpilled(int count, long[] target) {
    int selected = -1;
    for (int run = 0; run < runCount; run++) {
      if (runActive[run]
          && (selected < 0 || compareMergeRows(run, selected) < 0)) {
        selected = run;
      }
    }
    if (selected < 0) {
      return StatusCode.CORRUPTION;
    }
    outputPrimaryKey = mergePrimaryKeys[selected];
    outputNullMask = mergeNullMasks[selected];
    int valueStart = selected * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < count; index++) {
      target[index] = mergeValues[valueStart + index];
    }
    return readRunRow(selected);
  }

  long outputPrimaryKey() {
    return outputPrimaryKey;
  }

  long outputNullMask() {
    return outputNullMask;
  }

  StatusCode close() {
    StatusCode status = StatusCode.OK;
    if (spillChannel != null) {
      try {
        spillChannel.close();
      } catch (IOException failure) {
        status = StatusCode.IO_FAILURE;
      }
      if (!spillChannel.isOpen()) {
        spillChannel = null;
      }
    }
    if (status.isOk() && spillPath != null) {
      try {
        Files.deleteIfExists(spillPath);
        spillPath = null;
      } catch (IOException failure) {
        status = StatusCode.IO_FAILURE;
      }
    }
    if (status.isOk()) {
      table = null;
      spilled = false;
      runCount = 0;
      rowCount = 0;
      totalRows = 0;
    }
    return status;
  }

  private StatusCode spillRun() {
    if (rowCount <= 0) {
      return StatusCode.OK;
    }
    if (runCount >= MAXIMUM_RUNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = openSpill();
    if (!status.isOk()) {
      return status;
    }
    sortRows();
    int run = runCount;
    runOffsets[run] = spillWriteOffset;
    runRowCounts[run] = rowCount;
    int dataBytes = (projectedColumnCount + 3) * Long.BYTES;
    int recordBytes = dataBytes + Integer.BYTES;
    for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
      record.clear();
      record.limit(recordBytes);
      record.putLong(keys[rowIndex]);
      record.putLong(primaryKeys[rowIndex]);
      record.putLong(
          nullMasks[rowIndex]
              | (keyNulls[rowIndex] ? Long.MIN_VALUE : 0));
      int valueStart = rowIndex * TableSchema.MAXIMUM_COLUMNS;
      for (int index = 0; index < projectedColumnCount; index++) {
        record.putLong(values[valueStart + index]);
      }
      record.putInt(recordChecksum(dataBytes));
      record.flip();
      status = writeRecord();
      if (!status.isOk()) {
        return status;
      }
    }
    runCount++;
    spilled = true;
    rowCount = 0;
    return StatusCode.OK;
  }

  private StatusCode openSpill() {
    if (spillChannel != null) {
      return StatusCode.OK;
    }
    try {
      spillPath = Files.createTempFile("river-sort-", ".run");
      spillChannel = FileChannel.open(
          spillPath,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE);
      return StatusCode.OK;
    } catch (IOException failure) {
      spillChannel = null;
      return StatusCode.IO_FAILURE;
    }
  }

  private StatusCode writeRecord() {
    try {
      while (record.hasRemaining()) {
        int written = spillChannel.write(record, spillWriteOffset);
        if (written <= 0) {
          return StatusCode.IO_FAILURE;
        }
        spillWriteOffset += written;
      }
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  private StatusCode initializeMerge() {
    for (int run = 0; run < runCount; run++) {
      runReadOffsets[run] = runOffsets[run];
      runRowsRemaining[run] = runRowCounts[run];
      runActive[run] = false;
      StatusCode status = readRunRow(run);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private int compareMergeRows(int left, int right) {
    int comparison;
    if (mergeKeyNulls[left] != mergeKeyNulls[right]) {
      comparison = mergeKeyNulls[left] ? -1 : 1;
    } else {
      comparison = Long.compare(mergeKeys[left], mergeKeys[right]);
    }
    if (comparison == 0) {
      comparison = Long.compare(mergePrimaryKeys[left], mergePrimaryKeys[right]);
    }
    return descending ? -comparison : comparison;
  }

  private StatusCode readRunRow(int run) {
    if (runRowsRemaining[run] <= 0) {
      runActive[run] = false;
      return StatusCode.OK;
    }
    int dataBytes = (projectedColumnCount + 3) * Long.BYTES;
    int recordBytes = dataBytes + Integer.BYTES;
    record.clear();
    record.limit(recordBytes);
    long offset = runReadOffsets[run];
    try {
      while (record.hasRemaining()) {
        int read = spillChannel.read(record, offset);
        if (read <= 0) {
          return read < 0 ? StatusCode.CORRUPTION : StatusCode.IO_FAILURE;
        }
        offset += read;
      }
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
    record.flip();
    int storedChecksum = record.getInt(dataBytes);
    if (storedChecksum != recordChecksum(dataBytes)) {
      return StatusCode.CORRUPTION;
    }
    mergeKeys[run] = record.getLong();
    mergePrimaryKeys[run] = record.getLong();
    long nullInfo = record.getLong();
    mergeNullMasks[run] = nullInfo & ~Long.MIN_VALUE;
    mergeKeyNulls[run] = (nullInfo & Long.MIN_VALUE) != 0;
    int valueStart = run * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < projectedColumnCount; index++) {
      mergeValues[valueStart + index] = record.getLong();
    }
    runReadOffsets[run] = offset;
    runRowsRemaining[run]--;
    runActive[run] = true;
    return StatusCode.OK;
  }

  private int recordChecksum(int length) {
    checksum.reset();
    for (int index = 0; index < length; index++) {
      checksum.update(record.get(index));
    }
    return (int) checksum.getValue();
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
