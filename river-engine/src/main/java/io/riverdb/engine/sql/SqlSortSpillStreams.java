package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
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
  private int runPages;

  SqlSortSpillStreams(SqlMaterializedStatement materialized) { statement = materialized; }

  StatusCode ensureSource() {
    if (source != null) {
      return reservation.active() ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
    }
    if (runPages < RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES) {
      return StatusCode.INVARIANT_BROKEN;
    }
    SqlMaterializedPagedByteStream openedSource = open(
        SqlMaterializedScratchFileKind.RUNS0);
    if (openedSource == null) return detail.code();
    StatusCode status = reservation.acquire(statement, runPages);
    if (status.isOk()) {
      source = openedSource;
      return StatusCode.OK;
    }
    StatusCode closed = openedSource.close(detail);
    if (!closed.isOk()) source = openedSource;
    return closed.isOk() ? status : closed;
  }

  StatusCode merge(long rows, long width, SqlPagedExternalOrder.MergePass pass) {
    if (source == null || rows <= width) {
      if (source == null && rows > 0) return release(StatusCode.CORRUPTION);
      return release(StatusCode.OK);
    }
    target = open(SqlMaterializedScratchFileKind.RUNS1);
    if (target == null) return release(detail.code());
    StatusCode status = external.merge(
        source, target, rows, width, runPages, pass, merged);
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
      runPages = 0;
    }
    return release(status);
  }

  SqlMaterializedPagedByteStream source() { return source; }
  boolean active() { return source != null || target != null; }
  int configuredRunPages() { return statement.effectiveSortRunPages(); }
  int sortPageBytes() { return statement.sortPageBytes(); }

  StatusCode configure(int admittedRunPages) {
    if (source != null || target != null || runPages != 0
        || admittedRunPages < RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES
        || admittedRunPages > configuredRunPages()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    runPages = admittedRunPages;
    return StatusCode.OK;
  }

  private SqlMaterializedPagedByteStream open(SqlMaterializedScratchFileKind kind) {
    StatusCode status = statement.openStream(kind, 0, 0, opened, detail);
    return status.isOk() ? opened.stream() : null;
  }

  private StatusCode close(SqlMaterializedPagedByteStream stream) {
    return stream == null ? StatusCode.OK : stream.close(detail);
  }

  private StatusCode release(StatusCode prior) { return reservation.release(prior); }
}
