package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Allocation-free validation of immutable identities retained by a successor proposal. */
final class CatalogSuccessorProposalValidation {
  private CatalogSuccessorProposalValidation() {
  }

  static StatusCode validate(
      TableDescriptor current, TableDescriptor proposed, StatusDetail detail) {
    if (current == null || proposed == null) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    if (proposed.tableId() != current.tableId()
        || proposed.rowLayoutId() != current.rowLayoutId()
        || proposed.catalogGeneration() != current.catalogGeneration()
        || !CatalogSuccessorMetadataEquality.columns(current, proposed)
        || !CatalogSuccessorMetadataEquality.key(
            current.primaryKey(), proposed.primaryKey())
        || proposed.foreignKeyCount() != current.foreignKeyCount()) {
      return fail(detail, StatusCode.CONFLICT);
    }
    for (int index = 0; index < current.foreignKeyCount(); index++) {
      if (!CatalogSuccessorMetadataEquality.key(
          proposed.foreignKeyAt(index), current.foreignKeyAt(index))) {
        return fail(detail, StatusCode.CONFLICT);
      }
    }
    int lastRetained = -1;
    for (int index = 0; index < proposed.secondaryKeyCount(); index++) {
      KeyDescriptor key = proposed.secondaryKeyAt(index);
      // A private successor may add an unbound key; reservation binds its durable ID.
      if (key.keyId() == 0) continue;
      int retained = findIdentity(current, key);
      if (retained <= lastRetained
          || !CatalogSuccessorMetadataEquality.key(
              current.secondaryKeyAt(retained), key)) {
        return fail(detail, StatusCode.CONFLICT);
      }
      lastRetained = retained;
    }
    return StatusCode.OK;
  }

  private static int findIdentity(TableDescriptor current, KeyDescriptor key) {
    for (int index = 0; index < current.secondaryKeyCount(); index++) {
      if (current.secondaryKeyAt(index).keyId() == key.keyId()) return index;
    }
    return -1;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status) {
    if (detail != null && detail.code() == StatusCode.OK) detail.set(status);
    return status;
  }
}
