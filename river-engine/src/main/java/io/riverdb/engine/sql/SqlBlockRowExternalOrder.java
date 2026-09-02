package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchFileKind;

/** Configured page-backed run generation and stable external merge lifecycle. */
final class SqlBlockRowExternalOrder {
  private final SqlMaterializedPagedByteStream.Result opened =
      new SqlMaterializedPagedByteStream.Result();
  private final StatusDetail detail = new StatusDetail(128);
  private final SqlBlockRowSortProbe probe;
  private final SqlBlockRowOrdinalStream ordinals = new SqlBlockRowOrdinalStream();
  private final SqlBlockRowRunHeap heap = new SqlBlockRowRunHeap();
  private final SqlBlockRowRunMerge merger;
  private final SqlPagedExternalOrder external = new SqlPagedExternalOrder();
  private final SqlPagedExternalOrder.Result merged = new SqlPagedExternalOrder.Result();
  private final SqlMaterializedSortLease reservation = new SqlMaterializedSortLease();
  private SqlMaterializedPagedByteStream output;

  SqlBlockRowExternalOrder(SqlSessionShapeBudget budget) {
    probe = new SqlBlockRowSortProbe(budget);
    merger = new SqlBlockRowRunMerge(budget);
  }

  StatusCode build(
      SqlMaterializedStatement statement,
      SqlMaterializedPagedByteStream index,
      SqlMaterializedPagedByteStream keys,
      SqlBlockRowSortKeyCodec shape,
      long rowCount) {
    StatusCode status = reservation.release(StatusCode.OK);
    if (status.isOk()) status = closeOutput();
    if (!status.isOk() || rowCount <= 0) return status;
    if (statement == null || index == null || keys == null || shape == null
        || rowCount > Long.MAX_VALUE / Long.BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    SqlMaterializedPagedByteStream source = open(
        statement, SqlMaterializedScratchFileKind.RUNS0);
    if (source == null) return detail.code();
    SqlMaterializedPagedByteStream target = open(
        statement, SqlMaterializedScratchFileKind.RUNS1);
    if (target == null) return closeAfter(source, detail.code());
    status = reservation.acquire(statement);
    boolean reserved = status.isOk();
    long width = runRows(statement.sortRunPages(), source.pageBytes());
    if (status.isOk() && width <= 0) status = StatusCode.RESOURCE_EXHAUSTED;
    if (status.isOk()) status = generate(source, index, keys, shape, rowCount, width);
    if (status.isOk()) {
      merger.configure(ordinals, index, keys, shape, probe);
      status = external.merge(
          source, target, rowCount, width, statement.sortRunPages(), merger, merged);
      if (status.isOk()) {
        source = merged.output();
        target = merged.spare();
      }
    }
    if (reserved) {
      status = reservation.release(status);
    }
    if (!status.isOk()) return closeAfter(source, target, status);
    status = target.close(detail);
    if (!status.isOk()) return closeAfter(source, status);
    output = source;
    return StatusCode.OK;
  }

  StatusCode stored(long position, long rowCount, SqlBlockRowOrdinalStream.Result target) {
    if (target == null || output == null || position < 0 || position >= rowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = ordinals.read(output, position, target);
    if (!status.isOk()) return status;
    return target.value < 0 || target.value >= rowCount
        ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  StatusCode close() {
    StatusCode status = closeOutput();
    merger.close();
    return reservation.release(status);
  }

  StatusCode clearForReuse() {
    probe.reset();
    return reservation.release(closeOutput());
  }

  private StatusCode generate(
      SqlMaterializedPagedByteStream target,
      SqlMaterializedPagedByteStream index,
      SqlMaterializedPagedByteStream keys,
      SqlBlockRowSortKeyCodec shape,
      long rowCount,
      long runRows) {
    for (long start = 0; start < rowCount; ) {
      long count = Math.min(runRows, rowCount - start);
      for (long item = 0; item < count; item++) {
        StatusCode status = ordinals.append(target, start + item);
        if (!status.isOk()) return status;
      }
      probe.reset();
      StatusCode status = heap.sort(
          ordinals, target, start, count, probe, index, keys, shape);
      if (!status.isOk()) return status;
      start += count;
    }
    return StatusCode.OK;
  }

  private SqlMaterializedPagedByteStream open(
      SqlMaterializedStatement statement, SqlMaterializedScratchFileKind kind) {
    StatusCode status = statement.openStream(kind, Long.BYTES, 0, opened, detail);
    return status.isOk() ? opened.stream() : null;
  }

  private StatusCode closeOutput() {
    if (output == null) return StatusCode.OK;
    StatusCode status = output.close(detail);
    if (status.isOk()) output = null;
    return status;
  }

  private StatusCode closeAfter(
      SqlMaterializedPagedByteStream one, StatusCode prior) {
    StatusCode closed = one.close(detail);
    return prior.isOk() ? closed : prior;
  }

  private StatusCode closeAfter(
      SqlMaterializedPagedByteStream one,
      SqlMaterializedPagedByteStream two,
      StatusCode prior) {
    StatusCode status = closeAfter(one, prior);
    return closeAfter(two, status);
  }

  private static long runRows(int pages, int pageBytes) {
    int payload = pageBytes - 32;
    return pages <= 0 || payload <= 0 ? 0 : (long) pages * payload / Long.BYTES;
  }
}
