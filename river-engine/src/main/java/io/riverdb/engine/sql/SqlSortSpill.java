package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Statement-owned paged records for the shared external-order engine. */
final class SqlSortSpill {
  private static final int MERGE_SLOTS = 2;
  private static final int FIXED_HEADER_LONGS = 4;
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  private final SqlSortSpillDecodedRows decoded;
  private final SqlSortGeneratedTextSpill generatedText;
  private final SqlSortSpillStreams streams;
  private final SqlSortSpillRecordIO records = new SqlSortSpillRecordIO();
  private final SqlSortSpillResidentEncoder resident;
  private final OffsetResult leftNext = new OffsetResult();
  private final SqlSortSpillMerge merger;
  private final SqlSortSpillHeadComparator comparator = new SqlSortSpillHeadComparator();
  private final SqlSortSpillHeadBudget headBudget;
  private TableDefinition table;
  private boolean containsText;
  private int projectedColumnCount;
  private long readOffset;
  private long rowsRemaining;
  private long initialRunRows;

  SqlSortSpill() {
    this(SqlRetainedArrayAllocator.STANDARD, new SqlSessionShapeBudget(null));
  }

  SqlSortSpill(SqlRetainedArrayAllocator retainedAllocator) {
    this(retainedAllocator, new SqlSessionShapeBudget(null));
  }

  SqlSortSpill(
      SqlRetainedArrayAllocator retainedAllocator, SqlSessionShapeBudget budget) {
    allocator = retainedAllocator;
    this.budget = budget;
    streams = new SqlSortSpillStreams(budget.materialized());
    decoded = new SqlSortSpillDecodedRows(allocator);
    generatedText = new SqlSortGeneratedTextSpill(allocator);
    resident = new SqlSortSpillResidentEncoder(records, generatedText);
    merger = new SqlSortSpillMerge(this, budget);
    headBudget = new SqlSortSpillHeadBudget(budget);
  }

  StatusCode begin(
      TableDefinition definition,
      boolean descendingOrder,
      boolean textRows,
      boolean generatedTextRows,
      boolean textualKey,
      int projectionCount,
      int sortKeyDescriptor,
      SqlLegacyGroupTupleComparator tupleComparator,
      int tupleKeys,
      int runRows) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    containsText = textRows;
    projectedColumnCount = projectionCount;
    status = headBudget.reserve(MERGE_SLOTS, projectionCount, textRows);
    if (status.isOk()) status = ensureStorage(projectionCount);
    if (status.isOk()) status = ensureTextStorage(textRows);
    if (status.isOk()) status = generatedText.begin(generatedTextRows, projectionCount);
    if (!status.isOk()) return status;
    table = definition;
    comparator.configure(
        tupleComparator, tupleKeys, textualKey, sortKeyDescriptor, descendingOrder);
    initialRunRows = runRows;
    readOffset = 0;
    rowsRemaining = 0;
    resident.begin(projectionCount, textRows);
    return StatusCode.OK;
  }

  StatusCode writeRun(
      long[] keyHighs,
      long[] keys,
      boolean[] keyNulls,
      long[] primaryKeys,
      SqlSortNullWords nulls,
      long[] highs,
      long[] values,
      int[] rowSlots,
      int[] rowLengths,
      ByteBuffer rows,
      byte[] textLengths,
      char[] text,
      int rowCount) {
    StatusCode available = streams.ensureSource();
    if (!available.isOk()) return available;
    for (int row = 0; row < rowCount; row++) {
      resident.encode(
          keyHighs, keys, keyNulls, primaryKeys, nulls, highs, values,
          rowSlots, rowLengths, rows, textLengths, text, row, fixedBytes());
      StatusCode status = StatusCode.OK;
      if (status.isOk()) status = append(streams.source());
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode initializeMerge(long totalRows) {
    StatusCode status = streams.merge(totalRows, initialRunRows, merger);
    if (!status.isOk()) return status;
    readOffset = 0;
    rowsRemaining = totalRows;
    return StatusCode.OK;
  }

  StatusCode next(int count, long[] targetHighs, long[] targetValues) {
    if (rowsRemaining <= 0) return StatusCode.CONFLICT;
    StatusCode status = decode(streams.source(), readOffset, 0, leftNext);
    if (!status.isOk()) return status;
    decoded.outputPrimaryKey = decoded.primaryKeys[0];
    decoded.nulls.select(0);
    for (int index = 0; index < count; index++) {
      targetHighs[index] = decoded.highs[index];
      targetValues[index] = decoded.values[index];
    }
    if (containsText) captureOutputRow(0);
    generatedText.capture(0);
    readOffset = leftNext.value;
    rowsRemaining--;
    return StatusCode.OK;
  }

  long outputPrimaryKey() { return decoded.outputPrimaryKey; }
  long outputNullWord(int word) { return decoded.nulls.nullWord(word); }
  HeapRowResult outputRow() { return decoded.outputRow(); }
  int outputTextLength(int projection) { return generatedText.outputLength(projection); }
  char[] outputText() { return generatedText.output(); }
  int outputTextOffset(int projection) { return generatedText.outputOffset(projection); }
  boolean hasResources() { return table != null || streams.active(); }

  long sortRunPayloadBytes() { return streams.runPayloadBytes(); }

  StatusCode close() {
    StatusCode status = streams.close();
    if (status.isOk()) {
      table = null;
      decoded.outputRowLength = 0;
      readOffset = 0;
      rowsRemaining = 0;
    }
    return status;
  }

  private StatusCode decode(
      SqlMaterializedPagedByteStream stream, long offset, int slot, OffsetResult next) {
    return decode(stream, offset, slot, next, true);
  }

  private StatusCode decode(
      SqlMaterializedPagedByteStream stream, long offset, int slot,
      OffsetResult next, boolean outputRecord) {
    ByteBuffer record = records.buffer();
    StatusCode status = readRecord(stream, offset, Integer.BYTES);
    if (!status.isOk()) return status;
    int dataBytes = record.getInt(0);
    int minimum = fixedBytes() + (containsText ? Integer.BYTES : 0);
    int maximum = minimum + (containsText ? TableSchema.MAXIMUM_ROW_BYTES : 0);
    if (dataBytes < minimum || dataBytes > maximum
        || (!containsText && dataBytes != fixedBytes())) return StatusCode.CORRUPTION;
    int recordBytes = Integer.BYTES + dataBytes + Integer.BYTES;
    status = readRecord(stream, offset, recordBytes);
    if (!status.isOk()) return status;
    if (record.getInt(Integer.BYTES + dataBytes)
        != records.checksum(Integer.BYTES, dataBytes)) {
      return StatusCode.CORRUPTION;
    }
    record.position(Integer.BYTES);
    decoded.keyHighs[slot] = record.getLong();
    decoded.keys[slot] = record.getLong();
    decoded.primaryKeys[slot] = record.getLong();
    decoded.ordinals[slot] = record.getLong();
    long keyNull = record.getLong();
    if (keyNull < 0 || keyNull > 1) return StatusCode.CORRUPTION;
    decoded.keyNulls[slot] = keyNull != 0;
    status = decoded.nulls.read(record, slot, projectedColumnCount);
    int valueStart = slot * projectedColumnCount;
    for (int index = 0; status.isOk() && index < projectedColumnCount; index++) {
      decoded.highs[valueStart + index] = record.getLong();
      decoded.values[valueStart + index] = record.getLong();
    }
    if (status.isOk() && outputRecord) status = generatedText.read(record, slot);
    else if (status.isOk()) generatedText.skip(record);
    if (status.isOk() && containsText) status = readRowBytes(slot, dataBytes);
    if (status.isOk()) next.value = offset + recordBytes;
    return status;
  }

  StatusCode skipRecords(
      SqlMaterializedPagedByteStream stream, long offset, long count, OffsetResult result) {
    return records.skip(stream, offset, count, fixedBytes(), result);
  }

  StatusCode prepareMergeHeads(int slots) {
    StatusCode status = headBudget.reserve(slots, projectedColumnCount, containsText);
    if (status.isOk()) {
      status = decoded.reserve(projectedColumnCount, containsText, slots, allocator);
    }
    return status;
  }

  StatusCode loadMergeHead(
      SqlMaterializedPagedByteStream stream, long offset, int slot, OffsetResult next) {
    return decode(stream, offset, slot, next, false);
  }

  int compareMergeHeads(int left, int right) { return compareRows(left, right); }

  StatusCode copyRecord(
      SqlMaterializedPagedByteStream input,
      long inputOffset,
      SqlMaterializedPagedByteStream output,
      long outputOffset,
      OffsetResult result) {
    int minimum = fixedBytes() + (containsText ? Integer.BYTES : 0);
    int maximum = minimum + (containsText ? TableSchema.MAXIMUM_ROW_BYTES : 0);
    return records.copy(input, inputOffset, output, outputOffset, minimum, maximum, result);
  }

  private int compareRows(int left, int right) {
    return comparator.compare(
        left, right, decoded.keyHighs, decoded.keys, decoded.keyNulls, decoded.ordinals,
        decoded.highs, decoded.values, decoded.nulls, decoded.rows);
  }

  private StatusCode readRowBytes(int slot, int dataBytes) {
    ByteBuffer record = records.buffer();
    int rowLength = record.getInt();
    if (rowLength < 0 || rowLength > TableSchema.MAXIMUM_ROW_BYTES
        || dataBytes != fixedBytes() + Integer.BYTES + rowLength) {
      return StatusCode.CORRUPTION;
    }
    int targetOffset = slot * TableSchema.MAXIMUM_ROW_BYTES;
    for (int index = 0; index < rowLength; index++) {
      decoded.rows.put(targetOffset + index, record.get());
    }
    decoded.rowLengths[slot] = rowLength;
    return StatusCode.OK;
  }

  private void captureOutputRow(int slot) {
    decoded.captureRow(slot);
  }

  private StatusCode append(SqlMaterializedPagedByteStream stream) {
    return records.append(stream);
  }

  private StatusCode readRecord(
      SqlMaterializedPagedByteStream stream, long offset, int length) {
    return records.read(stream, offset, length);
  }

  private int fixedBytes() {
    return (2 * projectedColumnCount + FIXED_HEADER_LONGS + 1
        + decoded.nulls.nullWordCount()) * Long.BYTES
        + generatedText.recordBytes();
  }

  private StatusCode ensureStorage(int projections) {
    StatusCode decodedStatus = decoded.reserve(projections, containsText, allocator);
    if (!decodedStatus.isOk()) return decodedStatus;
    try {
      int wordCount = (projections + Long.SIZE - 1) >>> 6;
      int recordBytes = Integer.BYTES
          + (2 * projections + FIXED_HEADER_LONGS + 1 + wordCount) * Long.BYTES
          + projections * (1 + SqlProjectedRow.MAXIMUM_GENERATED_TEXT)
          + Integer.BYTES + TableSchema.MAXIMUM_ROW_BYTES + Integer.BYTES;
      StatusCode recordStatus = records.reserve(recordBytes, allocator);
      if (!recordStatus.isOk()) return recordStatus;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode ensureTextStorage(boolean required) {
    return required ? decoded.reserve(projectedColumnCount, true, allocator) : StatusCode.OK;
  }

  static final class OffsetResult { long value; long output; }
}
