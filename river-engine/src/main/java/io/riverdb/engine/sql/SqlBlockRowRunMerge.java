package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;

/** Stable fan-in merge from one ordinal generation into the alternate stream. */
final class SqlBlockRowRunMerge implements SqlPagedExternalOrder.MergePass {
  private final SqlBlockRowMergeCursors cursors;
  private SqlBlockRowOrdinalStream ordinals;
  private SqlMaterializedPagedByteStream index;
  private SqlMaterializedPagedByteStream keys;
  private SqlBlockRowSortKeyCodec shape;
  private SqlBlockRowSortProbe probe;
  private final SqlBlockRowOrdinalStream.Result decoded =
      new SqlBlockRowOrdinalStream.Result();

  SqlBlockRowRunMerge(SqlSessionShapeBudget budget) {
    cursors = new SqlBlockRowMergeCursors(budget);
  }

  void configure(
      SqlBlockRowOrdinalStream ordinalCodec,
      SqlMaterializedPagedByteStream rowIndex,
      SqlMaterializedPagedByteStream rowKeys,
      SqlBlockRowSortKeyCodec sortShape,
      SqlBlockRowSortProbe sortProbe) {
    ordinals = ordinalCodec;
    index = rowIndex;
    keys = rowKeys;
    shape = sortShape;
    probe = sortProbe;
  }

  @Override
  public StatusCode merge(
      SqlMaterializedPagedByteStream source,
      SqlMaterializedPagedByteStream target,
      long rowCount,
      long width,
      int fanIn) {
    long bytes = rowCount * Long.BYTES;
    boolean appendTarget = target.logicalLength() == 0;
    if (source.logicalLength() != bytes
        || !appendTarget && target.logicalLength() != bytes) return StatusCode.CORRUPTION;
    StatusCode status = cursors.reserve(fanIn);
    if (!status.isOk()) return status;
    long outputAt = 0;
    for (long start = 0; start < rowCount; ) {
      int runs = groupRunCount(start, rowCount, width, fanIn);
      status = prepareGroup(source, start, rowCount, width, runs);
      if (!status.isOk()) return status;
      long groupRows = 0;
      for (int run = 0; run < runs; run++) groupRows += cursors.ends()[run] - cursors.positions()[run];
      status = mergeGroup(source, target, outputAt, runs, appendTarget);
      if (!status.isOk()) return status;
      outputAt += groupRows;
      start += groupRows;
    }
    return StatusCode.OK;
  }

  void close() { cursors.close(); }

  private static int groupRunCount(long start, long rowCount, long width, int fanIn) {
    long remaining = rowCount - start;
    return (int) Math.min(fanIn, 1 + (remaining - 1) / width);
  }

  private StatusCode prepareGroup(
      SqlMaterializedPagedByteStream source,
      long start, long rowCount, long width, int runs) {
    long at = start;
    for (int run = 0; run < runs; run++) {
      cursors.positions()[run] = at;
      cursors.ends()[run] = at + Math.min(width, rowCount - at);
      StatusCode status = ordinals.read(source, at, decoded);
      if (!status.isOk()) return status;
      cursors.heads()[run] = decoded.value;
      cursors.heap()[run] = run;
      at = cursors.ends()[run];
    }
    probe.reset();
    for (int atHeap = runs / 2; atHeap-- > 0; ) sift(atHeap, runs);
    return probe.status();
  }

  private StatusCode mergeGroup(
      SqlMaterializedPagedByteStream source,
      SqlMaterializedPagedByteStream target,
      long outputAt,
      int heapSize,
      boolean appendTarget) {
    while (heapSize > 0) {
      int run = cursors.heap()[0];
      StatusCode status = ordinals.write(
          target, outputAt++, cursors.heads()[run], appendTarget);
      if (!status.isOk()) return status;
      long next = ++cursors.positions()[run];
      if (next >= cursors.ends()[run]) {
        cursors.heap()[0] = cursors.heap()[--heapSize];
      } else {
        status = ordinals.read(source, next, decoded);
        if (!status.isOk()) return status;
        cursors.heads()[run] = decoded.value;
      }
      if (heapSize > 0) sift(0, heapSize);
      if (!probe.status().isOk()) return probe.status();
    }
    return StatusCode.OK;
  }

  private void sift(int root, int size) {
    int run = cursors.heap()[root];
    while (root < size / 2) {
      int child = root * 2 + 1;
      if (child + 1 < size && compareHeap(child + 1, child) < 0) child++;
      if (compareRun(run, cursors.heap()[child]) <= 0) break;
      cursors.heap()[root] = cursors.heap()[child];
      root = child;
    }
    cursors.heap()[root] = run;
  }

  private int compareHeap(int left, int right) {
    return compareRun(cursors.heap()[left], cursors.heap()[right]);
  }

  private int compareRun(int left, int right) {
    return probe.compare(cursors.heads()[left], cursors.heads()[right], index, keys, shape);
  }
}
