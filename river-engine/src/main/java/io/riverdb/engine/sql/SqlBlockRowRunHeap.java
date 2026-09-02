package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;

/** In-place heap ordering of one configured page-backed ordinal run. */
final class SqlBlockRowRunHeap {
  private final SqlBlockRowOrdinalStream.Result left = new SqlBlockRowOrdinalStream.Result();
  private final SqlBlockRowOrdinalStream.Result right = new SqlBlockRowOrdinalStream.Result();
  private final SqlBlockRowOrdinalStream.Result current = new SqlBlockRowOrdinalStream.Result();

  StatusCode sort(
      SqlBlockRowOrdinalStream ordinals,
      SqlMaterializedPagedByteStream stream,
      long start,
      long count,
      SqlBlockRowSortProbe probe,
      SqlMaterializedPagedByteStream index,
      SqlMaterializedPagedByteStream keys,
      SqlBlockRowSortKeyCodec shape) {
    long root = count / 2;
    while (root > 0) {
      root--;
      StatusCode status = sift(
          ordinals, stream, start, root, count, probe, index, keys, shape);
      if (!status.isOk()) return status;
    }
    for (long end = count - 1; end > 0; end--) {
      StatusCode status = ordinals.read(stream, start, left);
      if (status.isOk()) status = ordinals.read(stream, start + end, right);
      if (status.isOk()) status = ordinals.overwrite(stream, start, right.value);
      if (status.isOk()) status = ordinals.overwrite(stream, start + end, left.value);
      if (status.isOk()) status = sift(
          ordinals, stream, start, 0, end, probe, index, keys, shape);
      if (!status.isOk()) return status;
    }
    return probe.status();
  }

  private StatusCode sift(
      SqlBlockRowOrdinalStream ordinals,
      SqlMaterializedPagedByteStream stream,
      long start,
      long root,
      long count,
      SqlBlockRowSortProbe probe,
      SqlMaterializedPagedByteStream index,
      SqlMaterializedPagedByteStream keys,
      SqlBlockRowSortKeyCodec shape) {
    StatusCode status = ordinals.read(stream, start + root, current);
    long at = root;
    while (status.isOk() && count > 1 && at <= (count - 2) / 2) {
      long child = at * 2 + 1;
      status = ordinals.read(stream, start + child, left);
      if (status.isOk() && child + 1 < count) {
        status = ordinals.read(stream, start + child + 1, right);
        if (status.isOk() && probe.compare(left.value, right.value, index, keys, shape) < 0) {
          child++;
          left.value = right.value;
        }
      }
      if (!status.isOk() || !probe.status().isOk()) break;
      if (probe.compare(current.value, left.value, index, keys, shape) >= 0) {
        return probe.status();
      }
      status = ordinals.overwrite(stream, start + at, left.value);
      if (status.isOk()) status = ordinals.overwrite(stream, start + child, current.value);
      at = child;
    }
    return status.isOk() ? probe.status() : status;
  }
}
