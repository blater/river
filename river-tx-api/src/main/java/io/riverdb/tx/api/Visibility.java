package io.riverdb.tx.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Resolves tuple or index ownership without exposing a transaction-manager implementation. */
public interface Visibility {
  /**
   * Resolves an owning transaction. A non-zero cached commit sequence may avoid a status lookup,
   * but an owner captured active by the snapshot remains hidden. Indeterminate ownership fences
   * the read; an unavailable retained outcome returns {@code RETRY}.
   */
  StatusCode resolve(
      TransactionContext context,
      long owningTransactionId,
      long cachedCommitSequence,
      VisibilityResult result,
      StatusDetail detail);
}
