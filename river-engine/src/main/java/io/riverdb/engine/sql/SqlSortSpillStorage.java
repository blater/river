package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Exact retained-memory and pressure-reclamation owner for one spill executor. */
final class SqlSortSpillStorage implements SqlRetainedReclaimer {
  private static final int INACTIVE = 0;
  private static final int PREPARING = 1;
  private static final int ACTIVE = 2;
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  private final SqlSortSpillDecodedRows decoded;
  private final SqlSortGeneratedTextSpill generatedText;
  private final SqlSortSpillRecordIO records;
  private final SqlSortSpillCursors cursors;
  private boolean registered;
  private int state;
  private int preparedFanIn;

  SqlSortSpillStorage(
      SqlRetainedArrayAllocator allocator, SqlSessionShapeBudget shapeBudget) {
    this.allocator = allocator;
    budget = shapeBudget;
    decoded = new SqlSortSpillDecodedRows(allocator, budget);
    generatedText = new SqlSortGeneratedTextSpill(allocator, budget);
    records = new SqlSortSpillRecordIO(budget);
    cursors = new SqlSortSpillCursors(budget);
  }

  StatusCode prepare(
      int projections, boolean textRows, boolean generatedTextRows, int fanIn) {
    if (!registered) {
      StatusCode status = budget.registerReclaimer(this);
      if (!status.isOk()) return status;
      registered = true;
    }
    state = PREPARING;
    StatusCode status = prepareOnce(projections, textRows, generatedTextRows, fanIn);
    if (status == StatusCode.RESOURCE_EXHAUSTED && retainedBytes() > 0) {
      StatusCode release = budget.release(retainedBytes());
      if (release.isOk()) {
        releaseStorage();
        status = prepareOnce(projections, textRows, generatedTextRows, fanIn);
      } else {
        status = release;
      }
    }
    state = status.isOk() ? ACTIVE : INACTIVE;
    preparedFanIn = status.isOk() ? fanIn : 0;
    return status;
  }

  void deactivate() { state = INACTIVE; }

  StatusCode requirePreparedFanIn(int fanIn) {
    return state == ACTIVE && fanIn >= 2 && fanIn <= preparedFanIn
        ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  SqlSortSpillDecodedRows decoded() { return decoded; }
  SqlSortGeneratedTextSpill generatedText() { return generatedText; }
  SqlSortSpillRecordIO records() { return records; }
  SqlSortSpillCursors cursors() { return cursors; }

  long retainedBytes() {
    long retained = SqlSortRunCapacity.add(decoded.retainedBytes(), records.retainedBytes());
    retained = SqlSortRunCapacity.add(retained, generatedText.retainedBytes());
    return SqlSortRunCapacity.add(retained, cursors.retainedBytes());
  }

  static long cleanRequiredBytes(
      int projections, boolean textRows, boolean generatedTextRows, int fanIn) {
    long retained = SqlSortSpillDecodedRows.cleanRequiredBytes(
        projections, textRows, fanIn);
    retained = SqlSortRunCapacity.add(retained,
        SqlSortGeneratedTextSpill.cleanRequiredBytes(generatedTextRows, projections));
    retained = SqlSortRunCapacity.add(retained,
        SqlSortSpillCursors.cleanRequiredBytes(fanIn));
    int generatedBytes = SqlSortGeneratedTextSpill.recordBytes(
        generatedTextRows, projections);
    int nullWords = (projections + Long.SIZE - 1) >>> 6;
    int recordBytes = generatedBytes < 0 ? -1
        : SqlSortSpillRecordLayout.maximumRecordBytes(
            projections, nullWords, generatedBytes, textRows);
    return recordBytes < 0 ? Long.MAX_VALUE
        : SqlSortRunCapacity.add(retained, recordBytes);
  }

  long requiredBytes(
      int projections, boolean textRows, boolean generatedTextRows, int fanIn) {
    long retained = decoded.requiredBytes(projections, textRows, fanIn);
    retained = SqlSortRunCapacity.add(retained,
        generatedText.requiredBytes(generatedTextRows, projections));
    retained = SqlSortRunCapacity.add(retained, cursors.requiredBytes(fanIn));
    int generatedBytes = SqlSortGeneratedTextSpill.recordBytes(
        generatedTextRows, projections);
    int nullWords = (projections + Long.SIZE - 1) >>> 6;
    int recordBytes = generatedBytes < 0 ? -1
        : SqlSortSpillRecordLayout.maximumRecordBytes(
            projections, nullWords, generatedBytes, textRows);
    return recordBytes < 0 ? Long.MAX_VALUE
        : SqlSortRunCapacity.add(retained, records.requiredBytes(recordBytes));
  }

  @Override
  public long reclaimableRetainedBytes() {
    return state == INACTIVE ? retainedBytes() : 0;
  }

  @Override
  public void releaseRetainedStorage() {
    if (state != INACTIVE) return;
    releaseStorage();
  }

  private StatusCode prepareOnce(
      int projections, boolean textRows, boolean generatedTextRows, int fanIn) {
    StatusCode status = generatedText.begin(generatedTextRows, projections);
    if (status.isOk()) status = decoded.reserve(projections, textRows, fanIn, allocator);
    if (status.isOk()) status = cursors.reserve(fanIn);
    int recordBytes = status.isOk() ? SqlSortSpillRecordLayout.maximumRecordBytes(
        projections, decoded.nulls.nullWordCount(),
        generatedText.recordBytes(), textRows) : 0;
    if (status.isOk()) status = recordBytes < 0
        ? StatusCode.RESOURCE_EXHAUSTED : records.reserve(recordBytes, allocator);
    return status;
  }

  private void releaseStorage() {
    decoded.releaseRetainedStorage();
    records.releaseRetainedStorage();
    generatedText.releaseRetainedStorage();
    cursors.releaseRetainedStorage();
    preparedFanIn = 0;
  }
}
