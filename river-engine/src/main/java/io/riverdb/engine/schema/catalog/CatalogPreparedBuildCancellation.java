package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Cancels one pre-intent catalog admission and reclaims a known durable private build. */
final class CatalogPreparedBuildCancellation {
  private CatalogPreparedBuildCancellation() {
  }

  static StatusCode cancel(
      CatalogPreparedTable prepared, StatusCode status, StatusDetail detail,
      CatalogBuildCleaner cleaner, long objectId, boolean intentDurable) {
    if (prepared.admission().isActive()) {
      StatusCode cancelled = prepared.admission().cancel();
      if (status.isOk()) status = cancelled;
    }
    if (intentDurable) {
      StatusCode cleanup = cleaner.cleanup(objectId);
      if (status.isOk()) status = cleanup;
    }
    prepared.clear();
    if (detail != null && detail.code() == StatusCode.OK) detail.set(status);
    return status;
  }
}
