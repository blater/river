package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;

/** Multiway merge adapter for variable-length legacy sort records. */
final class SqlSortSpillMerge implements SqlPagedExternalOrder.MergePass {
  private final SqlSortSpill owner;
  private final SqlSortSpillCursors cursors;
  private final SqlSortSpill.OffsetResult next = new SqlSortSpill.OffsetResult();
  private final SqlSortSpill.OffsetResult headNext = new SqlSortSpill.OffsetResult();
  private SqlMaterializedPagedByteStream source;
  private StatusCode status = StatusCode.OK;

  SqlSortSpillMerge(SqlSortSpill spill, SqlSortSpillCursors retainedCursors) {
    owner = spill;
    cursors = retainedCursors;
  }

  @Override
  public StatusCode merge(
      SqlMaterializedPagedByteStream input,
      SqlMaterializedPagedByteStream target,
      long rowCount,
      long width,
      int fanIn) {
    status = owner.prepareMergeHeads(fanIn);
    if (!status.isOk()) return status;
    source = input;
    long inputOffset = 0;
    long outputOffset = 0;
    for (long start = 0; status.isOk() && start < rowCount; ) {
      int runs = prepare(inputOffset, rowCount - start, width, fanIn);
      long rows = groupRows(runs);
      inputOffset = advanceGroup(runs);
      if (status.isOk()) outputOffset = mergeGroup(target, outputOffset, runs);
      start += rows;
    }
    if (status.isOk() && (inputOffset != input.logicalLength()
        || outputOffset != input.logicalLength())) status = StatusCode.CORRUPTION;
    return status;
  }

  private int prepare(long offset, long available, long width, int fanIn) {
    int runs = 0;
    while (status.isOk() && runs < fanIn && available > 0) {
      long count = Math.min(width, available);
      cursors.offsets()[runs] = offset;
      cursors.remaining()[runs] = count;
      cursors.heap()[runs] = runs;
      status = owner.skipRecords(source, offset, count, next);
      if (status.isOk()) {
        status = owner.loadMergeHead(source, cursors.offsets()[runs], runs, headNext);
      }
      offset = next.value;
      available -= count;
      runs++;
    }
    for (int root = runs / 2; status.isOk() && root-- > 0; ) sift(root, runs);
    return runs;
  }

  private long mergeGroup(
      SqlMaterializedPagedByteStream target, long outputOffset, int heapSize) {
    while (status.isOk() && heapSize > 0) {
      int run = cursors.heap()[0];
      status = owner.copyRecord(source, cursors.offsets()[run], target, outputOffset, next);
      if (!status.isOk()) break;
      outputOffset = next.output;
      cursors.offsets()[run] = next.value;
      if (--cursors.remaining()[run] == 0) {
        cursors.heap()[0] = cursors.heap()[--heapSize];
      } else {
        status = owner.loadMergeHead(source, cursors.offsets()[run], run, headNext);
      }
      if (heapSize > 0) sift(0, heapSize);
    }
    return outputOffset;
  }

  private void sift(int root, int size) {
    int run = cursors.heap()[root];
    while (status.isOk() && root < size / 2) {
      int child = root * 2 + 1;
      if (child + 1 < size && compareHeap(child + 1, child) < 0) child++;
      if (compare(run, cursors.heap()[child]) <= 0) break;
      cursors.heap()[root] = cursors.heap()[child];
      root = child;
    }
    cursors.heap()[root] = run;
  }

  private int compareHeap(int left, int right) {
    return compare(cursors.heap()[left], cursors.heap()[right]);
  }

  private int compare(int left, int right) {
    if (!status.isOk()) return 0;
    return owner.compareMergeHeads(left, right);
  }

  private long groupRows(int runs) {
    long rows = 0;
    for (int run = 0; run < runs; run++) rows += cursors.remaining()[run];
    return rows;
  }

  private long advanceGroup(int runs) {
    return runs == 0 ? 0 : next.value;
  }
}
