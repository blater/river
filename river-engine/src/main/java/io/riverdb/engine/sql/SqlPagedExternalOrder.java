package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;

/** Shared long-addressed pass scheduler for paged external ordering codecs. */
final class SqlPagedExternalOrder {
  interface MergePass {
    StatusCode merge(
        SqlMaterializedPagedByteStream source,
        SqlMaterializedPagedByteStream target,
        long rowCount,
        long runRows,
        int fanIn);
  }

  static final class Result {
    private SqlMaterializedPagedByteStream output;
    private SqlMaterializedPagedByteStream spare;
    SqlMaterializedPagedByteStream output() { return output; }
    SqlMaterializedPagedByteStream spare() { return spare; }
  }

  StatusCode merge(
      SqlMaterializedPagedByteStream source,
      SqlMaterializedPagedByteStream target,
      long rowCount,
      long initialRunRows,
      int fanIn,
      MergePass pass,
      Result result) {
    if (source == null || target == null || pass == null || result == null
        || rowCount < 0 || initialRunRows <= 0 || fanIn < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long width = initialRunRows;
    while (width < rowCount) {
      StatusCode status = pass.merge(source, target, rowCount, width, fanIn);
      if (!status.isOk()) return status;
      SqlMaterializedPagedByteStream swap = source;
      source = target;
      target = swap;
      width = grow(width, fanIn);
    }
    result.output = source;
    result.spare = target;
    return StatusCode.OK;
  }

  static long grow(long width, int fanIn) {
    return width > Long.MAX_VALUE / fanIn ? Long.MAX_VALUE : width * fanIn;
  }
}
