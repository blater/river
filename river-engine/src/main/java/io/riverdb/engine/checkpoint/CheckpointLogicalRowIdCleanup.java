package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;

/** Removes one inactive logical-row-floor generation and durably publishes its absence. */
final class CheckpointLogicalRowIdCleanup {
  private final DirectoryOperationResult operation = new DirectoryOperationResult();
  private int uncertainSlot = -1;

  StatusCode remove(DurableDirectory directory, int slot) {
    if (directory == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (slot < 0) return StatusCode.OK;
    if (slot > 1) return StatusCode.CORRUPTION;
    StatusCode status = directory.remove(
        CheckpointLogicalRowIdGenerationWriter.fileName(slot), operation);
    if (status == StatusCode.CONFLICT && uncertainSlot != slot) return StatusCode.OK;
    if (status == StatusCode.CONFLICT) status = StatusCode.OK;
    if (!status.isOk()) return status;
    uncertainSlot = slot;
    status = directory.force(operation);
    if (status.isOk() || operation.durability() == DirectoryDurability.DURABLE) {
      uncertainSlot = -1;
      return StatusCode.OK;
    }
    return status;
  }
}
