package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Retained, exactly charged primitive storage for one configured sort run. */
final class SqlSortRunStorage implements SqlNullWords, SqlRetainedReclaimer {
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  private final SqlSortNullWords nulls;
  private final SqlSortGeneratedText generatedText;
  private final SqlSortArrays arrays;
  private final SqlSortRowComparator comparator = new SqlSortRowComparator();
  private final HeapRowResult rowView = new HeapRowResult();
  private ByteBuffer rows;
  private long retainedBytes;
  private int runRows;
  private int projections;
  private boolean textRows;
  private boolean generatedTextRows;
  private boolean registered;
  private boolean active;

  SqlSortRunStorage(SqlRetainedArrayAllocator retainedAllocator, SqlSessionShapeBudget shapeBudget) {
    allocator = retainedAllocator;
    budget = shapeBudget;
    nulls = new SqlSortNullWords(1, allocator);
    generatedText = new SqlSortGeneratedText(allocator);
    arrays = new SqlSortArrays(allocator);
  }

  StatusCode prepare(
      int projectionCount, boolean containsText, boolean containsGeneratedText,
      long runPayloadBytes) {
    if (!registered && budget != null) {
      StatusCode registration = budget.registerReclaimer(this);
      if (!registration.isOk()) return registration;
      registered = true;
    }
    active = true;
    runRows = SqlSortRunCapacity.rows(
        projectionCount, containsText, containsGeneratedText, runPayloadBytes);
    projections = projectionCount;
    textRows = containsText;
    generatedTextRows = containsGeneratedText;
    StatusCode status = reserveCharge();
    if (status == StatusCode.RESOURCE_EXHAUSTED && budget != null) {
      long reclaimed = retainedBytes;
      StatusCode release = reclaimed > 0 ? budget.release(reclaimed) : StatusCode.OK;
      if (release.isOk()) {
        releaseAllStorage();
        status = reserveCharge();
      } else {
        status = release;
      }
    }
    if (status.isOk()) status = nulls.reserve(
        projections, SqlShapeLimits.MAX_RESULT_COLUMNS, runRows);
    if (status.isOk()) status = arrays.reserve(runRows, projections);
    if (status.isOk()) status = ensureRows();
    if (status.isOk()) status = generatedText.reserve(
        runRows, projections, generatedTextRows);
    if (!status.isOk()) {
      adjustCharge(actualBytes());
      active = false;
    }
    return status;
  }

  StatusCode append(
      int row, long ordinal, long keyHigh, long key, boolean keyNull, long primaryKey,
      long[] highs, long[] values, SqlNullWords sourceNulls,
      HeapRowResult source, SqlProjectedRow projected) {
    if (highs == null || values == null || highs.length < projections
        || values.length < projections || sourceNulls == null
        || sourceNulls.nullWordCount() != nulls.nullWordCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    arrays.append(
        row, projections, ordinal, keyHigh, key, keyNull, primaryKey, highs, values);
    StatusCode status = textRows ? copyRow(row, source) : StatusCode.OK;
    if (status.isOk()) status = nulls.copyFrom(row, sourceNulls);
    if (status.isOk() && generatedTextRows) generatedText.copyFrom(row, projections, projected);
    return status;
  }

  StatusCode write(SqlSortSpill spill, int count) {
    return spill.writeRun(
        arrays.keyHighs(), arrays.keys(), arrays.keyNulls(),
        arrays.primaryKeys(), arrays.ordinals(),
        nulls, arrays.highs(), arrays.values(), arrays.rowSlots(), arrays.rowLengths(), rows,
        generatedText.lengths(), generatedText.text(), count);
  }

  int compare(
      int left, int right, int groupKeys, SqlLegacyGroupTupleComparator groupComparator,
      boolean textKey, int descriptor, boolean descending) {
    return comparator.compare(
        left, right, groupKeys, groupComparator, arrays, nulls, rows,
        textKey, descriptor, descending);
  }

  void swap(int left, int right) {
    arrays.swap(left, right, projections);
    nulls.swap(left, right);
    if (generatedTextRows) generatedText.swap(left, right, projections);
  }

  void closeHighWater() {
    adjustCharge(actualBytes());
    active = false;
  }

  @Override
  public long reclaimableRetainedBytes() {
    return active ? 0 : retainedBytes;
  }

  @Override
  public void releaseRetainedStorage() {
    if (!active) releaseAllStorage();
  }

  int runRows() { return runRows; }
  int projections() { return projections; }
  boolean textRows() { return textRows; }
  boolean generatedTextRows() { return generatedTextRows; }
  long retainedProjectionBytes() {
    return arrays.retainedProjectionBytes() + generatedText.retainedBytes();
  }
  long retainedBytes() { return retainedBytes; }

  static long cleanRequiredBytes(
      int projectionCount, boolean containsText, boolean containsGeneratedText,
      long runPayloadBytes) {
    int rows = SqlSortRunCapacity.rows(
        projectionCount, containsText, containsGeneratedText, runPayloadBytes);
    long required = SqlSortArrays.cleanRequiredBytes(rows, projectionCount);
    required = SqlSortRunCapacity.add(required, SqlSortNullWords.cleanRequiredBytes(
        projectionCount, SqlShapeLimits.MAX_RESULT_COLUMNS, rows));
    required = SqlSortRunCapacity.add(required, SqlSortGeneratedText.cleanRequiredBytes(
        rows, projectionCount, containsGeneratedText));
    long rowBytes = containsText ? (long) rows * TableSchema.MAXIMUM_ROW_BYTES : 0;
    return SqlSortRunCapacity.add(required, rowBytes);
  }

  long requiredBytes(
      int projectionCount, boolean containsText, boolean containsGeneratedText,
      long runPayloadBytes) {
    int rows = SqlSortRunCapacity.rows(
        projectionCount, containsText, containsGeneratedText, runPayloadBytes);
    return requiredBytes(
        rows, projectionCount, containsText, containsGeneratedText);
  }
  long primaryKey(int row) { return arrays.primaryKey(row); }
  void selectNulls(int row) { nulls.select(row); }
  void copyValues(int row, int count, long[] target) {
    arrays.copyValues(row, projections, count, target);
  }
  void copyHighs(int row, int count, long[] target) {
    arrays.copyHighs(row, projections, count, target);
  }
  HeapRowResult row(int index) {
    int slot = arrays.rowSlot(index);
    rowView.set(rows, 0, slot * TableSchema.MAXIMUM_ROW_BYTES, arrays.rowLengthAtSlot(slot));
    return rowView;
  }
  StatusCode setGeneratedText(SqlScanRowResult target, int row, boolean spilled, SqlSortSpill spill) {
    return generatedTextRows
        ? generatedText.setResult(target, row, projections, spilled, spill) : StatusCode.OK;
  }
  void copyGeneratedText(SqlProjectedRow target, int row, boolean spilled, SqlSortSpill spill) {
    if (generatedTextRows) generatedText.copyTo(target, row, projections, spilled, spill);
  }
  @Override public long nullWord(int word) { return nulls.nullWord(word); }
  @Override public int nullWordCount() { return nulls.nullWordCount(); }

  private StatusCode copyRow(int row, HeapRowResult source) {
    if (source == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    int offset = row * TableSchema.MAXIMUM_ROW_BYTES;
    rows.position(offset);
    rows.limit(offset + source.length());
    StatusCode status = source.copyTo(rows);
    rows.clear();
    if (status.isOk()) arrays.rowLocation(row, row, source.length());
    return status;
  }

  private StatusCode ensureRows() {
    if (!textRows) return StatusCode.OK;
    long required = (long) runRows * TableSchema.MAXIMUM_ROW_BYTES;
    if (required > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    if (rows != null && rows.capacity() >= required) return StatusCode.OK;
    try {
      rows = allocator.direct((int) required);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode reserveCharge() {
    long required = requiredBytes(
        runRows, projections, textRows, generatedTextRows);
    if (required == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    if (required <= retainedBytes || budget == null) return StatusCode.OK;
    StatusCode status = budget.reserve(required - retainedBytes);
    if (status.isOk()) retainedBytes = required;
    return status;
  }

  private long requiredBytes(
      int requiredRows, int projectionCount,
      boolean containsText, boolean containsGeneratedText) {
    long required = arrays.requiredBytes(requiredRows, projectionCount);
    required = SqlSortRunCapacity.add(
        required, nulls.requiredBytes(
            projectionCount, SqlShapeLimits.MAX_RESULT_COLUMNS, requiredRows));
    required = SqlSortRunCapacity.add(
        required, generatedText.requiredBytes(
            requiredRows, projectionCount, containsGeneratedText));
    long rowBytes = containsText
        ? (long) requiredRows * TableSchema.MAXIMUM_ROW_BYTES : 0;
    return SqlSortRunCapacity.add(
        required, Math.max(rowBytes, rows == null ? 0 : rows.capacity()));
  }

  private long actualBytes() {
    long retained = SqlSortRunCapacity.add(arrays.retainedBytes(), nulls.retainedBytes());
    retained = SqlSortRunCapacity.add(retained, generatedText.retainedBytes());
    return SqlSortRunCapacity.add(retained, rows == null ? 0 : rows.capacity());
  }

  private void adjustCharge(long retained) {
    if (budget != null && retained < retainedBytes) budget.rollback(retainedBytes - retained);
    retainedBytes = retained;
  }

  private void releaseAllStorage() {
    arrays.release();
    nulls.release();
    generatedText.release();
    rows = null;
    retainedBytes = 0;
  }
}
