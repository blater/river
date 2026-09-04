package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable bounded sort storage and spill lifecycle for one SQL session. */
final class SqlSortWorkspace implements SqlNullWords {
  private final SqlSessionShapeBudget budget;
  private final SqlSortAdmission admission = new SqlSortAdmission();
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
    this.budget = budget;
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
    if (tupleKeys > 1) {
      status = groupComparator.configure(
          command, bound, tupleKeys, projectionCount, groupTuple);
    }
    if (!status.isOk()) return status;
    long availableBytes = budget.maximumReplacementBytes(retainedBytes());
    if (availableBytes < 0) return StatusCode.INVARIANT_BROKEN;
    status = admission.select(
        spill.configuredRunPages(), spill.sortPageBytes(),
        projectionCount, textRows, generatedTextRows, availableBytes);
    if (!status.isOk()) return status;
    long retainedTarget = SqlSortRunCapacity.add(
        storage.requiredBytes(
            projectionCount, textRows, generatedTextRows, admission.runPayloadBytes()),
        spill.requiredBytes(
            projectionCount, textRows, generatedTextRows, admission.pages()));
    if (retainedTarget > availableBytes) {
      status = releaseInactiveStorage();
      if (!status.isOk()) return status;
    }
    status = storage.prepare(
        projectionCount, textRows, generatedTextRows, admission.runPayloadBytes());
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
        storage.runRows(), admission.pages());
    if (!status.isOk()) {
      storage.closeHighWater();
      return status;
    }
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
    long ordinal = totalRows++;
    status = storage.append(
        rowIndex, ordinal, keyHigh, key, keyNull, primaryKey,
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

  long retainedBytes() {
    return SqlSortRunCapacity.add(storage.retainedBytes(), spill.retainedBytes());
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

  private StatusCode releaseInactiveStorage() {
    long retained = retainedBytes();
    long reclaimable = SqlSortRunCapacity.add(
        storage.reclaimableRetainedBytes(), spill.reclaimableRetainedBytes());
    if (reclaimable != retained) return StatusCode.INVARIANT_BROKEN;
    if (retained == 0) return StatusCode.OK;
    StatusCode status = budget.release(retained);
    if (!status.isOk()) return status;
    storage.releaseRetainedStorage();
    spill.releaseRetainedStorage();
    return retainedBytes() == 0 ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
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
  int admittedRunPages() { return admission.pages(); }
}
