package io.riverdb.tx.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Resolves tuple or index ownership without exposing a transaction-manager implementation. */
public interface Visibility {
  /**
   * Resolves an owning transaction from the authoritative outcome provider. Status freezing is
   * deliberately absent until P09 accepts its proof and representation. Indeterminate ownership
   * fences the read; an unavailable retained outcome returns {@code RETRY}.
   */
  StatusCode resolve(
      TransactionContext context,
      long owningTransactionId,
      VisibilityResult result,
      StatusDetail detail);
}
