package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Shared result handling for key descriptor construction. */
final class KeyDescriptorStatus {
  private KeyDescriptorStatus() {
  }

  static void reset(KeyDescriptor.Result result, StatusDetail detail) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
  }

  static StatusCode fail(StatusDetail detail, StatusCode status, CharSequence message) {
    if (detail != null) detail.set(status).append(message);
    return status;
  }
}
