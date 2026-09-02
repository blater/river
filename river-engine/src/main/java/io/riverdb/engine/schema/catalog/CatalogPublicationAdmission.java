package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.TransactionState;

/** Shared ownership and transaction admission for catalog publication. */
final class CatalogPublicationAdmission {
  private CatalogPublicationAdmission() { }

  static StatusCode validate(
      EmbeddedDatabase embedded,
      IndexedTransactionSession session,
      StatusDetail detail) {
    StatusCode status = !embedded.ownsSession(session)
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : !session.transaction().isActiveHandle()
            || session.transaction().state() != TransactionState.ACTIVE
                ? StatusCode.CONFLICT : StatusCode.OK;
    if (!status.isOk() && detail != null) detail.reset().set(status);
    return status;
  }
}
