package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Resolves one version chain at a snapshot without retaining cursor state. */
final class IndexedVersionVisibility {
  private IndexedVersionVisibility() { }

  static StatusCode visible(
      IndexedVersionState versions,
      long rowId,
      long commitSequence,
      long rowCount,
      IndexedVersionRecord result) {
    if (versions == null || result == null || rowId <= 0 || rowId > rowCount
        || commitSequence < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long visible = rowId;
    while (visible > 0) {
      StatusCode status = versions.lookup(visible, rowCount, result);
      if (!status.isOk()) return status;
      if (result.commitSequence() <= commitSequence) return StatusCode.OK;
      visible = result.previousRowId();
    }
    result.reset();
    return StatusCode.OK;
  }
}
