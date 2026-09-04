package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.DurableDirectory;

/** Switches a live WAL provider to a forced empty next-generation file. */
final class LocalWalRotator {
  private LocalWalRotator() {
  }

  static StatusCode rotate(
      LocalWal wal,
      DurableDirectory directory,
      String nextFileName,
      WalGeneration nextGeneration,
      long checkpointTransactionId) {
    if (directory == null
        || nextFileName == null
        || nextFileName.isEmpty()
        || nextGeneration == null
        || !nextGeneration.isValid()
        || nextGeneration.value() <= wal.walGeneration().value()
        || checkpointTransactionId <= 0
        || wal.hasOpenLogicalStream()
        || wal.hasActiveReservation()
        || wal.hasPendingRecords()
        || wal.hasForcedBatch()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    LocalWalOpenResult opened = new LocalWalOpenResult();
    StatusCode status = LocalWal.createCheckpointGeneration(
        directory, nextFileName, wal.databaseIncarnation(), nextGeneration, opened);
    if (!status.isOk()) {
      return status;
    }
    LocalWal replacement = opened.wal();
    return wal.adoptRotatedState(
        replacement, nextFileName, nextGeneration, checkpointTransactionId);
  }
}
