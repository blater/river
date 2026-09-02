package io.riverdb.engine.checkpoint;

/** Caller-owned decoded row-version metadata from a checkpoint base. */
public final class CheckpointVersionResult {
  private long commitSequence;
  private long previousRowId;
  private boolean deleted;

  public void reset() {
    commitSequence = 0;
    previousRowId = 0;
    deleted = false;
  }

  void set(long committedAt, long previous, boolean isDeleted) {
    commitSequence = committedAt;
    previousRowId = previous;
    deleted = isDeleted;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public long previousRowId() {
    return previousRowId;
  }

  public boolean deleted() {
    return deleted;
  }
}
