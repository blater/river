package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Statement-owned paged records for the shared external-order engine. */
final class SqlSortSpill {
  private final SqlSortSpillStorage storage;
  private final SqlSortSpillDecodedRows decoded;
  private final SqlSortGeneratedTextSpill generatedText;
  private final SqlSortSpillStreams streams;
  private final SqlSortSpillRecordIO records;
  private final SqlSortSpillResidentEncoder resident;
  private final SqlSortSpillRecordReader reader;
  private final OffsetResult leftNext = new OffsetResult();
  private final SqlSortSpillMerge merger;
  private final SqlSortSpillHeadComparator comparator = new SqlSortSpillHeadComparator();
  private TableDefinition table;
  private boolean containsText;
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
    streams = new SqlSortSpillStreams(budget.materialized());
    storage = new SqlSortSpillStorage(retainedAllocator, budget);
    decoded = storage.decoded();
    generatedText = storage.generatedText();
    records = storage.records();
    resident = new SqlSortSpillResidentEncoder(records, generatedText);
    reader = new SqlSortSpillRecordReader(records, decoded, generatedText);
    merger = new SqlSortSpillMerge(this, storage.cursors());
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
      int runRows,
      int runPages) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    status = streams.configure(runPages);
    if (!status.isOk()) return status;
    containsText = textRows;
    reader.configure(projectionCount, textRows);
    status = storage.prepare(
        projectionCount, textRows, generatedTextRows, runPages);
    if (!status.isOk()) {
      storage.deactivate();
      return status;
    }
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
      long[] ordinals,
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
          keyHighs, keys, keyNulls, primaryKeys, ordinals, nulls, highs, values,
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
    StatusCode status = reader.decodeOutput(streams.source(), readOffset, leftNext);
    if (!status.isOk()) return status;
    decoded.outputPrimaryKey = decoded.primaryKeys[0];
    decoded.nulls.select(0);
    for (int index = 0; index < count; index++) {
      targetHighs[index] = decoded.highs[index];
      targetValues[index] = decoded.values[index];
    }
    if (containsText) captureOutputRow(0);
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

  int configuredRunPages() { return streams.configuredRunPages(); }
  int sortPageBytes() { return streams.sortPageBytes(); }

  long requiredBytes(
      int projections, boolean textRows, boolean generatedTextRows, int runPages) {
    return storage.requiredBytes(projections, textRows, generatedTextRows, runPages);
  }

  long reclaimableRetainedBytes() { return storage.reclaimableRetainedBytes(); }

  void releaseRetainedStorage() { storage.releaseRetainedStorage(); }

  StatusCode close() {
    StatusCode status = streams.close();
    if (status.isOk()) {
      table = null;
      decoded.outputRowLength = 0;
      readOffset = 0;
      rowsRemaining = 0;
      storage.deactivate();
    }
    return status;
  }

  StatusCode skipRecords(
      SqlMaterializedPagedByteStream stream, long offset, long count, OffsetResult result) {
    return reader.skip(stream, offset, count, result);
  }

  StatusCode prepareMergeHeads(int slots) {
    return storage.requirePreparedFanIn(slots);
  }

  StatusCode loadMergeHead(
      SqlMaterializedPagedByteStream stream, long offset, int slot, OffsetResult next) {
    return reader.decodeHead(stream, offset, slot, next);
  }

  int compareMergeHeads(int left, int right) { return compareRows(left, right); }

  StatusCode copyRecord(
      SqlMaterializedPagedByteStream input,
      long inputOffset,
      SqlMaterializedPagedByteStream output,
      long outputOffset,
      OffsetResult result) {
    return reader.copy(input, inputOffset, output, outputOffset, result);
  }

  private int compareRows(int left, int right) {
    return comparator.compare(
        left, right, decoded.keyHighs, decoded.keys, decoded.keyNulls, decoded.ordinals,
        decoded.highs, decoded.values, decoded.nulls, decoded.rows);
  }

  private void captureOutputRow(int slot) {
    decoded.captureRow(slot);
  }

  private StatusCode append(SqlMaterializedPagedByteStream stream) {
    return records.append(stream);
  }

  private int fixedBytes() {
    return reader.fixedBytes();
  }

  long retainedBytes() { return storage.retainedBytes(); }

  static final class OffsetResult { long value; long output; }
}
