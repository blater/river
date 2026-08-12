package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;

/** Caller-owned checkpoint authority with the MVCC metadata needed by its stable page base. */
public final class CheckpointState {
  public static final int MAXIMUM_ROWS = 64 * 1024;

  private final long[] deletedWords = new long[MAXIMUM_ROWS / Long.SIZE];
  private final long[] rowCommitSequences = new long[MAXIMUM_ROWS + 1];
  private final int[] previousRowIds = new int[MAXIMUM_ROWS + 1];
  private DatabaseIncarnation database;
  private WalGeneration walGeneration;
  private long checkpointId;
  private long commitSequence;
  private long maximumTransactionId;
  private int pageCount;
  private int rowCount;
  private boolean available;

  public void reset() {
    int previousRows = rowCount;
    database = null;
    walGeneration = null;
    checkpointId = 0;
    commitSequence = 0;
    maximumTransactionId = 0;
    pageCount = 0;
    rowCount = 0;
    available = false;
    for (int rowId = 1; rowId <= previousRows; rowId++) {
      rowCommitSequences[rowId] = 0;
      previousRowIds[rowId] = 0;
    }
    int previousWords = (previousRows + Long.SIZE - 1) / Long.SIZE;
    for (int index = 0; index < previousWords; index++) {
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
    reset();
    database = incarnation;
    walGeneration = generation;
    checkpointId = id;
    commitSequence = committedAt;
    maximumTransactionId = maximumTx;
    pageCount = pages;
    rowCount = rows;
    available = true;
    for (int rowId = 1; rowId <= rows; rowId++) {
      rowCommitSequences[rowId] = committedAt;
      previousRowIds[rowId] = 0;
    }
    return StatusCode.OK;
  }

  public StatusCode setRowVersion(
      int rowId,
      long committedAt,
      int previousRowId,
      boolean deleted) {
    if (!available
        || rowId <= 0
        || rowId > rowCount
        || committedAt <= 0
        || committedAt > commitSequence
        || previousRowId < 0
        || previousRowId >= rowId) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    rowCommitSequences[rowId] = committedAt;
    previousRowIds[rowId] = previousRowId;
    int bit = rowId - 1;
    long mask = 1L << (bit & 63);
    if (deleted) {
      deletedWords[bit >>> 6] |= mask;
    } else {
      deletedWords[bit >>> 6] &= ~mask;
    }
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

  public long rowCommitSequence(int rowId) {
    return rowId > 0 && rowId <= rowCount ? rowCommitSequences[rowId] : 0;
  }

  public int previousRowId(int rowId) {
    return rowId > 0 && rowId <= rowCount ? previousRowIds[rowId] : 0;
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
