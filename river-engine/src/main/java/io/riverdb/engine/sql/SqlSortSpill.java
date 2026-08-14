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

/** Bounded durable scratch format and merge cursor for one SQL sort. */
final class SqlSortSpill {
  private static final int MAXIMUM_RUNS = 64;
  private static final int FIXED_HEADER_LONGS = 3;
  private static final int MAXIMUM_DATA_BYTES =
      (TableSchema.MAXIMUM_COLUMNS + FIXED_HEADER_LONGS) * Long.BYTES
          + Integer.BYTES + TableSchema.MAXIMUM_ROW_BYTES;
  private static final int MAXIMUM_RECORD_BYTES =
      Integer.BYTES + MAXIMUM_DATA_BYTES + Integer.BYTES;

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
  private final int[] mergeRowLengths = new int[MAXIMUM_RUNS];
  private final boolean[] runActive = new boolean[MAXIMUM_RUNS];
  private final ByteBuffer mergeRows = ByteBuffer.allocateDirect(
      MAXIMUM_RUNS * TableSchema.MAXIMUM_ROW_BYTES);
  private final ByteBuffer outputRow = ByteBuffer.allocateDirect(
      TableSchema.MAXIMUM_ROW_BYTES);
  private final HeapRowResult outputRowView = new HeapRowResult();
  private final ByteBuffer record = ByteBuffer.allocateDirect(MAXIMUM_RECORD_BYTES);
  private final CRC32C checksum = new CRC32C();
  private FileChannel channel;
  private Path path;
  private TableDefinition table;
  private boolean descending;
  private boolean containsText;
  private int orderColumn;
  private int projectedColumnCount;
  private int runCount;
  private int outputRowLength;
  private long writeOffset;
  private long outputPrimaryKey;
  private long outputNullMask;

  StatusCode begin(
      TableDefinition definition,
      boolean descendingOrder,
      boolean textRows,
      int orderedColumn,
      int projectionCount) {
    StatusCode status = close();
    if (!status.isOk()) {
      return status;
    }
    table = definition;
    descending = descendingOrder;
    containsText = textRows;
    orderColumn = orderedColumn;
    projectedColumnCount = projectionCount;
    runCount = 0;
    writeOffset = 0;
    outputRowLength = 0;
    return StatusCode.OK;
  }

  StatusCode writeRun(
      long[] keys,
      boolean[] keyNulls,
      long[] primaryKeys,
      long[] nullMasks,
      long[] values,
      int[] rowSlots,
      int[] rowLengths,
      ByteBuffer rows,
      int rowCount) {
    if (runCount >= MAXIMUM_RUNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = open();
    if (!status.isOk()) {
      return status;
    }
    int run = runCount;
    runOffsets[run] = writeOffset;
    runRowCounts[run] = rowCount;
    for (int row = 0; row < rowCount; row++) {
      status = writeRow(
          keys, keyNulls, primaryKeys, nullMasks, values,
          rowSlots, rowLengths, rows, row);
      if (!status.isOk()) {
        return status;
      }
    }
    runCount++;
    return StatusCode.OK;
  }

  StatusCode initializeMerge() {
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

  StatusCode next(int count, long[] target) {
    int selected = selectRun();
    if (selected < 0) {
      return StatusCode.CORRUPTION;
    }
    outputPrimaryKey = mergePrimaryKeys[selected];
    outputNullMask = mergeNullMasks[selected];
    int valueStart = selected * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < count; index++) {
      target[index] = mergeValues[valueStart + index];
    }
    if (containsText) {
      captureOutputRow(selected);
    }
    return readRunRow(selected);
  }

  long outputPrimaryKey() {
    return outputPrimaryKey;
  }

  long outputNullMask() {
    return outputNullMask;
  }

  HeapRowResult outputRow() {
    outputRowView.set(outputRow, 0, 0, outputRowLength);
    return outputRowView;
  }

  boolean hasResources() {
    return table != null || channel != null || path != null;
  }

  StatusCode close() {
    StatusCode status = StatusCode.OK;
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException failure) {
        status = StatusCode.IO_FAILURE;
      }
      if (!channel.isOpen()) {
        channel = null;
      }
    }
    if (status.isOk() && path != null) {
      try {
        Files.deleteIfExists(path);
        path = null;
      } catch (IOException failure) {
        status = StatusCode.IO_FAILURE;
      }
    }
    if (status.isOk()) {
      table = null;
      runCount = 0;
      outputRowLength = 0;
    }
    return status;
  }

  private StatusCode writeRow(
      long[] keys,
      boolean[] keyNulls,
      long[] primaryKeys,
      long[] nullMasks,
      long[] values,
      int[] rowSlots,
      int[] rowLengths,
      ByteBuffer rows,
      int row) {
    int rowLength = containsText ? rowLengths[row] : 0;
    int fixedBytes = (projectedColumnCount + FIXED_HEADER_LONGS) * Long.BYTES;
    int dataBytes = fixedBytes + (containsText ? Integer.BYTES + rowLength : 0);
    record.clear();
    record.limit(Integer.BYTES + dataBytes + Integer.BYTES);
    record.putInt(dataBytes);
    record.putLong(keys[row]);
    record.putLong(primaryKeys[row]);
    record.putLong(nullMasks[row] | (keyNulls[row] ? Long.MIN_VALUE : 0));
    int valueStart = row * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < projectedColumnCount; index++) {
      record.putLong(values[valueStart + index]);
    }
    if (containsText) {
      record.putInt(rowLength);
      int source = rowSlots[row] * TableSchema.MAXIMUM_ROW_BYTES;
      for (int index = 0; index < rowLength; index++) {
        record.put(rows.get(source + index));
      }
    }
    record.putInt(checksum(Integer.BYTES, dataBytes));
    record.flip();
    return writeRecord();
  }

  private StatusCode open() {
    if (channel != null) {
      return StatusCode.OK;
    }
    try {
      path = Files.createTempFile("river-sort-", ".run");
      channel = FileChannel.open(
          path, StandardOpenOption.READ, StandardOpenOption.WRITE);
      return StatusCode.OK;
    } catch (IOException failure) {
      channel = null;
      return StatusCode.IO_FAILURE;
    }
  }

  private StatusCode writeRecord() {
    try {
      while (record.hasRemaining()) {
        int written = channel.write(record, writeOffset);
        if (written <= 0) {
          return StatusCode.IO_FAILURE;
        }
        writeOffset += written;
      }
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  private int selectRun() {
    int selected = -1;
    for (int run = 0; run < runCount; run++) {
      if (runActive[run]
          && (selected < 0 || compareRows(run, selected) < 0)) {
        selected = run;
      }
    }
    return selected;
  }

  private int compareRows(int left, int right) {
    int comparison;
    if (mergeKeyNulls[left] != mergeKeyNulls[right]) {
      comparison = mergeKeyNulls[left] ? -1 : 1;
    } else if (containsText && table.isVarchar(orderColumn)) {
      comparison = compareText(left, right);
    } else {
      comparison = Long.compare(mergeKeys[left], mergeKeys[right]);
    }
    if (comparison == 0) {
      comparison = Long.compare(mergePrimaryKeys[left], mergePrimaryKeys[right]);
    }
    return descending ? -comparison : comparison;
  }

  private int compareText(int left, int right) {
    long leftHandle = mergeKeys[left];
    long rightHandle = mergeKeys[right];
    int leftOffset = left * TableSchema.MAXIMUM_ROW_BYTES + (int) (leftHandle >>> 32);
    int rightOffset = right * TableSchema.MAXIMUM_ROW_BYTES + (int) (rightHandle >>> 32);
    int leftLength = (int) leftHandle;
    int rightLength = (int) rightHandle;
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(mergeRows.get(leftOffset + index)),
          Byte.toUnsignedInt(mergeRows.get(rightOffset + index)));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftLength, rightLength);
  }

  private StatusCode readRunRow(int run) {
    if (runRowsRemaining[run] <= 0) {
      runActive[run] = false;
      return StatusCode.OK;
    }
    long offset = runReadOffsets[run];
    StatusCode status = readRecordHeader(offset);
    if (!status.isOk()) {
      return status;
    }
    int dataBytes = record.getInt(0);
    int fixedBytes = (projectedColumnCount + FIXED_HEADER_LONGS) * Long.BYTES;
    int minimum = fixedBytes + (containsText ? Integer.BYTES : 0);
    int maximum = minimum + (containsText ? TableSchema.MAXIMUM_ROW_BYTES : 0);
    if (dataBytes < minimum || dataBytes > maximum || (!containsText && dataBytes != fixedBytes)) {
      return StatusCode.CORRUPTION;
    }
    int recordBytes = Integer.BYTES + dataBytes + Integer.BYTES;
    status = readRecord(offset, recordBytes);
    if (!status.isOk()) {
      return status;
    }
    int storedChecksum = record.getInt(Integer.BYTES + dataBytes);
    if (storedChecksum != checksum(Integer.BYTES, dataBytes)) {
      return StatusCode.CORRUPTION;
    }
    record.position(Integer.BYTES);
    mergeKeys[run] = record.getLong();
    mergePrimaryKeys[run] = record.getLong();
    long nullInfo = record.getLong();
    mergeNullMasks[run] = nullInfo & ~Long.MIN_VALUE;
    mergeKeyNulls[run] = (nullInfo & Long.MIN_VALUE) != 0;
    int valueStart = run * TableSchema.MAXIMUM_COLUMNS;
    for (int index = 0; index < projectedColumnCount; index++) {
      mergeValues[valueStart + index] = record.getLong();
    }
    if (containsText) {
      status = readRowBytes(run, dataBytes, fixedBytes);
      if (!status.isOk()) {
        return status;
      }
    }
    runReadOffsets[run] = offset + recordBytes;
    runRowsRemaining[run]--;
    runActive[run] = true;
    return StatusCode.OK;
  }

  private StatusCode readRecordHeader(long offset) {
    record.clear();
    record.limit(Integer.BYTES);
    return readAt(offset);
  }

  private StatusCode readRecord(long offset, int length) {
    record.clear();
    record.limit(length);
    StatusCode status = readAt(offset);
    if (status.isOk()) {
      record.flip();
    }
    return status;
  }

  private StatusCode readAt(long offset) {
    try {
      long current = offset;
      while (record.hasRemaining()) {
        int read = channel.read(record, current);
        if (read <= 0) {
          return read < 0 ? StatusCode.CORRUPTION : StatusCode.IO_FAILURE;
        }
        current += read;
      }
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  private StatusCode readRowBytes(int run, int dataBytes, int fixedBytes) {
    int rowLength = record.getInt();
    if (rowLength < 0
        || rowLength > TableSchema.MAXIMUM_ROW_BYTES
        || dataBytes != fixedBytes + Integer.BYTES + rowLength) {
      return StatusCode.CORRUPTION;
    }
    int target = run * TableSchema.MAXIMUM_ROW_BYTES;
    for (int index = 0; index < rowLength; index++) {
      mergeRows.put(target + index, record.get());
    }
    mergeRowLengths[run] = rowLength;
    return StatusCode.OK;
  }

  private void captureOutputRow(int run) {
    outputRowLength = mergeRowLengths[run];
    int source = run * TableSchema.MAXIMUM_ROW_BYTES;
    for (int index = 0; index < outputRowLength; index++) {
      outputRow.put(index, mergeRows.get(source + index));
    }
  }

  private int checksum(int offset, int length) {
    checksum.reset();
    for (int index = 0; index < length; index++) {
      checksum.update(record.get(offset + index));
    }
    return (int) checksum.getValue();
  }
}
