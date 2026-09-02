package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Shared preflight and max-publication path for live and recovered logical-row floors. */
final class IndexedLogicalRowIdPublication {
  private final IndexedLogicalRowIdRegistry registry;

  IndexedLogicalRowIdPublication(IndexedLogicalRowIdRegistry logicalRowIds) {
    registry = logicalRowIds;
  }

  StatusCode validate(IndexedRelationalMutationBuffer mutations) {
    if (mutations == null || !mutations.sealed()) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int index = 0; index < mutations.logicalRowFloorCount(); index++) {
      StatusCode status = registry.validatePublication(
          mutations.logicalRowFloorObjectIdAt(index),
          mutations.logicalRowFloorNextAt(index));
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode publish(IndexedRelationalMutationBuffer mutations) {
    StatusCode status = validate(mutations);
    for (int index = 0; status.isOk() && index < mutations.logicalRowFloorCount(); index++) {
      status = registry.publishMax(
          mutations.logicalRowFloorObjectIdAt(index),
          mutations.logicalRowFloorNextAt(index));
    }
    return status;
  }

  StatusCode recover(IndexedRelationalMutationBuffer mutations) {
    if (mutations == null || !mutations.sealed()) return StatusCode.CORRUPTION;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < mutations.logicalRowFloorCount(); index++) {
      status = registry.load(
          mutations.logicalRowFloorObjectIdAt(index),
          mutations.logicalRowFloorNextAt(index));
    }
    return status;
  }
}
