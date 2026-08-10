package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;

/** Caller-owned decoded checkpoint authority and compacted-row tombstone map. */
public final class CheckpointState {
  public static final int MAXIMUM_ROWS = 2048;

  private final long[] deletedWords = new long[MAXIMUM_ROWS / Long.SIZE];
  private DatabaseIncarnation database;
  private WalGeneration walGeneration;
  private long checkpointId;
  private long commitSequence;
  private long maximumTransactionId;
  private int pageCount;
  private int rowCount;
  private boolean available;

  public void reset() {
    database = null;
    walGeneration = null;
    checkpointId = 0;
    commitSequence = 0;
    maximumTransactionId = 0;
    pageCount = 0;
    rowCount = 0;
    available = false;
    for (int index = 0; index < deletedWords.length; index++) {
      deletedWords[index] = 0;
    }
  }

  public StatusCode set(
      DatabaseIncarnation incarnation,
      WalGeneration generation,
      long id,
      long committedAt,
      long maximumTx,
      int pages,
      int rows) {
    if (incarnation == null
        || !incarnation.isValid()
        || generation == null
        || !generation.isValid()
        || id <= 0
        || committedAt <= 0
        || maximumTx <= 0
        || pages <= 0
        || rows < 0
        || rows > MAXIMUM_ROWS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
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

  public StatusCode setDeleted(int rowId) {
    if (rowId <= 0 || rowId > rowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int bit = rowId - 1;
    deletedWords[bit >>> 6] |= 1L << (bit & 63);
    return StatusCode.OK;
  }

  public boolean isDeleted(int rowId) {
    if (rowId <= 0 || rowId > rowCount) {
      return false;
    }
    int bit = rowId - 1;
    return (deletedWords[bit >>> 6] & 1L << (bit & 63)) != 0;
  }

  long deletedWord(int index) {
    return deletedWords[index];
  }

  void setDeletedWord(int index, long value) {
    deletedWords[index] = value;
  }

  boolean hasDeletedRowsBeyond(int rows) {
    int completeWords = rows >>> 6;
    int remainingBits = rows & 63;
    if (remainingBits != 0) {
      long validMask = (1L << remainingBits) - 1;
      if ((deletedWords[completeWords] & ~validMask) != 0) {
        return true;
      }
      completeWords++;
    }
    for (int index = completeWords; index < deletedWords.length; index++) {
      if (deletedWords[index] != 0) {
        return true;
      }
    }
    return false;
  }

  public DatabaseIncarnation database() {
    return database;
  }

  public WalGeneration walGeneration() {
    return walGeneration;
  }

  public long checkpointId() {
    return checkpointId;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public long maximumTransactionId() {
    return maximumTransactionId;
  }

  public int pageCount() {
    return pageCount;
  }

  public int rowCount() {
    return rowCount;
  }

  public boolean isAvailable() {
    return available;
  }
}
