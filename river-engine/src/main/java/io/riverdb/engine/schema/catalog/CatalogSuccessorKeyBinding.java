package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Preserves live key identities while binding only newly added successor keys. */
final class CatalogSuccessorKeyBinding {
  private CatalogSuccessorKeyBinding() {
  }

  static StatusCode bind(
      TableDescriptor current,
      TableDescriptor proposed,
      CatalogReservation reservation,
      TableDescriptor.Result result,
      StatusDetail detail) {
    if (result == null || reservation == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = CatalogSuccessorProposalValidation.validate(
        current, proposed, detail);
    if (!status.isOk()) return status;
    if (!validReservation(current, reservation)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int unbound = CatalogTableKeys.unboundCount(proposed);
    if (!CatalogReservedKeyRange.matches(reservation, unbound)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    KeyDescriptor[] secondary;
    KeyDescriptor[] foreign;
    KeyDescriptor.Result bound;
    try {
      secondary = new KeyDescriptor[proposed.secondaryKeyCount()];
      foreign = new KeyDescriptor[current.foreignKeyCount()];
      bound = new KeyDescriptor.Result();
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = bindSecondary(
        current, proposed, reservation.firstKeyId(), secondary, bound, detail);
    for (int index = 0; status.isOk() && index < foreign.length; index++) {
      foreign[index] = proposed.foreignKeyAt(index);
    }
    return status.isOk() ? TableDescriptor.createCatalogBound(
        current.tableId(), reservation.schemaId(),
        current.rowLayoutId(), reservation.catalogGeneration(),
        proposed.columns(), proposed.primaryKey(), secondary, foreign, result, detail) : status;
  }

  private static StatusCode bindSecondary(
      TableDescriptor current,
      TableDescriptor proposed,
      long firstKeyId,
      KeyDescriptor[] target,
      KeyDescriptor.Result bound,
      StatusDetail detail) {
    int lastExisting = -1;
    long nextKeyId = firstKeyId;
    for (int index = 0; index < target.length; index++) {
      KeyDescriptor key = proposed.secondaryKeyAt(index);
      if (key.keyId() == 0) {
        StatusCode status = CatalogKeyIdentityBinding.bind(
            key, proposed.columns(), nextKeyId++, bound, detail);
        if (!status.isOk()) return status;
        target[index] = bound.value();
      } else {
        int existing = find(current, key.keyId());
        if (existing <= lastExisting || existing < 0) return StatusCode.CONFLICT;
        lastExisting = existing;
        target[index] = key;
      }
    }
    return StatusCode.OK;
  }

  private static int find(TableDescriptor table, long keyId) {
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      if (table.secondaryKeyAt(index).keyId() == keyId) return index;
    }
    return -1;
  }

  private static boolean validReservation(
      TableDescriptor current, CatalogReservation reservation) {
    return reservation.objectId() == current.tableId()
        && reservation.rowLayoutId() == current.rowLayoutId()
        && current.catalogGeneration() < Long.MAX_VALUE
        && reservation.catalogGeneration() == current.catalogGeneration() + 1;
  }
}
