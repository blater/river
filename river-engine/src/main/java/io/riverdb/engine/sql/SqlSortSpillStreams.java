package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchFileKind;

/** Exact two-stream alternation and retry-retained sort reservation lifecycle. */
final class SqlSortSpillStreams {
  private final SqlMaterializedStatement statement;
  private final SqlMaterializedPagedByteStream.Result opened =
      new SqlMaterializedPagedByteStream.Result();
  private final StatusDetail detail = new StatusDetail(160);
  private final SqlMaterializedSortLease reservation = new SqlMaterializedSortLease();
  private final SqlPagedExternalOrder external = new SqlPagedExternalOrder();
  private final SqlPagedExternalOrder.Result merged = new SqlPagedExternalOrder.Result();
  private SqlMaterializedPagedByteStream source;
  private SqlMaterializedPagedByteStream target;

  SqlSortSpillStreams(SqlMaterializedStatement materialized) { statement = materialized; }

  StatusCode ensureSource() {
    if (source != null) return StatusCode.OK;
    source = open(SqlMaterializedScratchFileKind.RUNS0);
    if (source == null) return detail.code();
    return reservation.acquire(statement);
  }

  StatusCode merge(long rows, long width, SqlPagedExternalOrder.MergePass pass) {
    if (source == null || rows <= width) {
      if (source == null && rows > 0) return release(StatusCode.CORRUPTION);
      return release(StatusCode.OK);
    }
    target = open(SqlMaterializedScratchFileKind.RUNS1);
    if (target == null) return release(detail.code());
    StatusCode status = external.merge(
        source, target, rows, width, statement.effectiveSortRunPages(), pass, merged);
    if (status.isOk()) {
      source = merged.output();
      target = merged.spare();
      status = target.close(detail);
      if (status.isOk()) target = null;
    }
    return release(status);
  }

  StatusCode close() {
    StatusCode status = close(source);
    if (target != source) {
      StatusCode closed = close(target);
      if (status.isOk()) status = closed;
    }
    if (status.isOk()) {
      source = null;
      target = null;
    }
    return release(status);
  }

  SqlMaterializedPagedByteStream source() { return source; }
  boolean active() { return source != null || target != null; }
  long runPayloadBytes() { return statement.sortRunPayloadBytes(); }

  private SqlMaterializedPagedByteStream open(SqlMaterializedScratchFileKind kind) {
    StatusCode status = statement.openStream(kind, 0, 0, opened, detail);
    return status.isOk() ? opened.stream() : null;
  }

  private StatusCode close(SqlMaterializedPagedByteStream stream) {
    return stream == null ? StatusCode.OK : stream.close(detail);
  }

  private StatusCode release(StatusCode prior) { return reservation.release(prior); }
}
