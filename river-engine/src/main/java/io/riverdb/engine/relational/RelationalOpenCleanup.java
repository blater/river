package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Selects and diagnoses cleanup failures after relational database open fails. */
final class RelationalOpenCleanup {
  private RelationalOpenCleanup() {
  }

  static StatusCode result(
      StatusCode openFailure,
      StatusCode firstCleanup,
      StatusCode secondCleanup,
      StatusDetail detail) {
    StatusCode cleanupFailure = firstCleanup.isOk() ? secondCleanup : firstCleanup;
    if (cleanupFailure.isOk()) return openFailure;
    if (detail != null) {
      if (detail.code() != openFailure) detail.set(openFailure);
      if (detail.length() != 0) detail.append("; ");
      detail.append("cleanup also failed: ").append(cleanupFailure.name());
    }
    return openFailure;
  }
}
