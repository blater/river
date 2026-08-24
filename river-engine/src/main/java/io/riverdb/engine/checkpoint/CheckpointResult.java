package io.riverdb.engine.checkpoint;

/** Caller-owned result for one durable checkpoint and WAL generation rotation. */
public final class CheckpointResult {
  private long checkpointId;
  private long previousWalGeneration;
  private long walGeneration;
  private long previousWalBytes;
  private long walBytes;
  private long commitSequence;
  private int pageCount;
  private long rowCount;
  private long rowsReclaimed;
  private boolean obsoleteFilesRetained;

  public void reset() {
    checkpointId = 0;
    previousWalGeneration = 0;
    walGeneration = 0;
    previousWalBytes = 0;
    walBytes = 0;
    commitSequence = 0;
    pageCount = 0;
    rowCount = 0;
    rowsReclaimed = 0;
    obsoleteFilesRetained = false;
  }

  void set(
      long id,
      long previousGeneration,
      long nextGeneration,
      long previousBytes,
      long nextBytes,
      long committedAt,
      int pages,
      long rows,
      long reclaimed,
      boolean retained) {
    checkpointId = id;
    previousWalGeneration = previousGeneration;
    walGeneration = nextGeneration;
    previousWalBytes = previousBytes;
    walBytes = nextBytes;
    commitSequence = committedAt;
    pageCount = pages;
    rowCount = rows;
    rowsReclaimed = reclaimed;
    obsoleteFilesRetained = retained;
  }

  public long checkpointId() {
    return checkpointId;
  }

  public long previousWalGeneration() {
    return previousWalGeneration;
  }

  public long walGeneration() {
    return walGeneration;
  }

  public long previousWalBytes() {
    return previousWalBytes;
  }

  public long walBytes() {
    return walBytes;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public int pageCount() {
    return pageCount;
  }

  public long rowCount() {
    return rowCount;
  }

  public long rowsReclaimed() {
    return rowsReclaimed;
  }

  public boolean obsoleteFilesRetained() {
    return obsoleteFilesRetained;
  }
}
