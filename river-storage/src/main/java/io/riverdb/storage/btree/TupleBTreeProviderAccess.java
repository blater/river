package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;

/** Cleanup rules for provider pins. */
final class TupleBTreeProviderAccess {
  private TupleBTreeProviderAccess() { }

  static StatusCode release(
      TupleBTreePageProvider provider,
      TupleBTreePageReference reference,
      StatusCode operation) {
    if (!reference.isAttached()) return operation;
    StatusCode released = provider.release(reference);
    if (released.isOk()) reference.reset();
    return released.isOk() ? operation : released;
  }
}
