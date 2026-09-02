package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;

/** Scalar authority metadata for one checkpoint generation. */
final class CheckpointMetadata {
  private DatabaseIncarnation database;
  private WalGeneration walGeneration;
  private long checkpointId;
  private long commitSequence;
  private long maximumTransactionId;
  private long obsoleteVersionCount;
  private long rowCount;
  private int pageCount;
  private boolean versionsRequired;
  private boolean available;

  StatusCode set(
      DatabaseIncarnation incarnation, WalGeneration generation, long id,
      long committedAt, long maximumTx, int pages, long rows) {
    if (invalid(incarnation, generation, id, committedAt, maximumTx, pages, rows)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    clear();
    database = incarnation;
    walGeneration = generation;
    checkpointId = id;
    commitSequence = committedAt;
    maximumTransactionId = maximumTx;
    pageCount = pages;
    rowCount = rows;
    available = true;
    return StatusCode.OK;
  }

  void clear() {
    database = null;
    walGeneration = null;
    checkpointId = 0;
    commitSequence = 0;
    maximumTransactionId = 0;
    obsoleteVersionCount = 0;
    rowCount = 0;
    pageCount = 0;
    versionsRequired = false;
    available = false;
  }

  DatabaseIncarnation database() { return database; }
  WalGeneration walGeneration() { return walGeneration; }
  long checkpointId() { return checkpointId; }
  long commitSequence() { return commitSequence; }
  long maximumTransactionId() { return maximumTransactionId; }
  long obsoleteVersionCount() { return obsoleteVersionCount; }
  long rowCount() { return rowCount; }
  int pageCount() { return pageCount; }
  boolean versionsRequired() { return versionsRequired; }
  boolean available() { return available; }
  void obsoleteVersionCount(long value) { obsoleteVersionCount = value; }
  void requireVersions() { versionsRequired = true; }

  private static boolean invalid(
      DatabaseIncarnation incarnation, WalGeneration generation, long id,
      long committedAt, long maximumTx, int pages, long rows) {
    return incarnation == null || !incarnation.isValid()
        || generation == null || !generation.isValid() || id <= 0
        || committedAt <= 0 || maximumTx <= 0 || pages <= 0
        || rows < 0 || rows > CheckpointState.MAXIMUM_RUNTIME_ROWS;
  }
}
