package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable bounded sort storage and spill lifecycle for one SQL session. */
final class SqlSortWorkspace implements SqlNullWords {
  private final SqlSortSpill spill;
  private final SqlSortRunStorage storage;
  private final SqlSortHeap heap = new SqlSortHeap();
  private final SqlLegacyGroupTupleComparator groupComparator =
      new SqlLegacyGroupTupleComparator();
  private TableDefinition table;
  private boolean spilled;
  private boolean textKey;
  private boolean descending;
  private int keyDescriptor;
  private int groupKeyCount;
  private int rowCount;
  private long totalRows;
  private long outputPrimaryKey;

  SqlSortWorkspace() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlSortWorkspace(SqlRetainedArrayAllocator retainedAllocator) {
    this(retainedAllocator, new SqlSessionShapeBudget(null));
  }

  SqlSortWorkspace(
      SqlRetainedArrayAllocator retainedAllocator, SqlSessionShapeBudget budget) {
    spill = new SqlSortSpill(retainedAllocator, budget);
    storage = new SqlSortRunStorage(retainedAllocator, budget);
  }

  StatusCode begin(
      TableDefinition definition,
      boolean descendingOrder,
      int projectionCount,
      boolean textRows,
      boolean generatedTextRows,
      boolean textualKey,
      int sortKeyDescriptor) {
    return begin(
        definition, descendingOrder, projectionCount, textRows,
        generatedTextRows, textualKey, sortKeyDescriptor, null, null, 0, false);
  }

  StatusCode begin(
      TableDefinition definition,
      boolean descendingOrder,
      int projectionCount,
      boolean textRows,
      boolean generatedTextRows,
      boolean textualKey,
      int sortKeyDescriptor,
      io.riverdb.sql.SqlCommand command,
      BoundSqlStatement bound,
      int tupleKeys,
      boolean groupTuple) {
    if (definition == null
        || projectionCount <= 0
        || projectionCount > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = close();
    if (!status.isOk()) {
      return status;
    }
    status = storage.prepare(
        projectionCount, textRows, generatedTextRows, spill.sortRunPayloadBytes());
    if (!status.isOk()) return status;
    if (status.isOk() && tupleKeys > 1) {
      status = groupComparator.configure(
          command, bound, tupleKeys, projectionCount, groupTuple);
    }
    if (!status.isOk()) return status;
    status = spill.begin(
        definition,
        descendingOrder,
        textRows,
        generatedTextRows,
        textualKey,
        projectionCount,
        sortKeyDescriptor,
        tupleKeys > 1 ? groupComparator : null,
        tupleKeys,
        storage.runRows());
    if (!status.isOk()) return status;
    table = definition;
    descending = descendingOrder;
    textKey = textualKey;
    keyDescriptor = sortKeyDescriptor;
    groupKeyCount = tupleKeys;
    rowCount = 0;
    totalRows = 0;
    spilled = false;
    return status;
  }

  StatusCode append(
      long keyHigh,
      long key,
      boolean keyNull,
      long primaryKey,
      long[] projectedHighs,
      long[] projectedValues,
      SqlNullWords projectedNulls,
      HeapRowResult source,
      SqlProjectedRow projected) {
    if (table == null || projectedHighs == null || projectedValues == null
        || projectedHighs.length < storage.projections()
        || projectedValues.length < storage.projections()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = rowCount < storage.runRows()
        ? StatusCode.OK
        : spillRun();
    if (!status.isOk()) {
      return status;
    }
    if (totalRows == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    int rowIndex = rowCount++;
    totalRows++;
    status = storage.append(
        rowIndex, keyHigh, key, keyNull, primaryKey,
        projectedHighs, projectedValues, projectedNulls, source, projected);
    if (!status.isOk()) {
      rowCount--;
      totalRows--;
    }
    return status;
  }

  StatusCode finish() {
    if (table == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!spilled) {
      heap.sort(this, rowCount);
      return StatusCode.OK;
    }
    StatusCode status = spillRun();
    return status.isOk() ? spill.initializeMerge(totalRows) : status;
  }

  boolean isSpilled() {
    return spilled;
  }

  boolean containsText() {
    return storage.textRows();
  }

  long totalRows() {
    return totalRows;
  }

  boolean hasResources() {
    return table != null || spill.hasResources();
  }

  long retainedProjectionBytes() {
    return storage.retainedProjectionBytes();
  }

  long primaryKeyAt(int index) {
    return storage.primaryKey(index);
  }

  void selectNullWordsAt(int index) {
    storage.selectNulls(index);
  }

  void copyValuesAt(int row, int count, long[] target) {
    storage.copyValues(row, count, target);
  }

  void copyHighsAt(int row, int count, long[] target) {
    storage.copyHighs(row, count, target);
  }

  HeapRowResult rowAt(int index) {
    return storage.row(index);
  }

  HeapRowResult spilledRow() {
    return spill.outputRow();
  }

  StatusCode nextSpilled(int count, long[] targetHighs, long[] targetValues) {
    StatusCode status = spill.next(count, targetHighs, targetValues);
    outputPrimaryKey = spill.outputPrimaryKey();
    return status;
  }

  StatusCode setGeneratedText(SqlScanRowResult result, int row) {
    return storage.setGeneratedText(result, row, spilled, spill);
  }

  void copyGeneratedText(SqlProjectedRow result, int row) {
    storage.copyGeneratedText(result, row, spilled, spill);
  }

  long outputPrimaryKey() {
    return outputPrimaryKey;
  }

  @Override public long nullWord(int word) {
    return spilled ? spill.outputNullWord(word) : storage.nullWord(word);
  }

  @Override public int nullWordCount() { return storage.nullWordCount(); }

  StatusCode close() {
    StatusCode status = StatusCode.OK;
    status = spill.close();
    if (status.isOk()) {
      table = null;
      spilled = false;
      rowCount = 0;
      totalRows = 0;
      storage.closeHighWater();
    }
    return status;
  }

  private StatusCode spillRun() {
    if (rowCount <= 0) {
      return StatusCode.OK;
    }
    heap.sort(this, rowCount);
    StatusCode status = storage.write(spill, rowCount);
    if (status.isOk()) {
      spilled = true;
      rowCount = 0;
    }
    return status;
  }

  int compareRows(int left, int right) {
    return storage.compare(
        left, right, groupKeyCount, groupComparator,
        textKey, keyDescriptor, descending);
  }

  void swapRows(int left, int right) {
    storage.swap(left, right);
  }

  int configuredRunRows() { return storage.runRows(); }
}
