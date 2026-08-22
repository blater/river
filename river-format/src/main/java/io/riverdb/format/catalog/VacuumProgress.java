package io.riverdb.format.catalog;

/** Caller-owned decoded authority for one incremental replacement storage generation. */
public final class VacuumProgress {
  private int state;
  private long sourceStorageGeneration;
  private long replacementStorageGeneration;
  private long sourceMaximumLogicalRowId;
  private long lastCopiedLogicalRowId;
  private int sourceRootDirectoryPageId;
  private int replacementRootDirectoryPageId;
  private int replacementLogicalDirectoryPageId;
  private int replacementVersionDirectoryPageId;
  private int replacementFreePageRootId;
  private int nextPageId;
  private long sourceRootDirectoryGeneration;
  private long replacementRootDirectoryGeneration;
  private long replacementLogicalDirectoryGeneration;
  private long replacementVersionDirectoryGeneration;
  private long replacementFreePageGeneration;
  private long rowsCopied;
  private long versionsReclaimed;
  private long progressGeneration;
  private long sourceCommitSequence;
  private long appliedCommitSequence;

  void set(
      int progressState,
      long sourceStorage,
      long replacementStorage,
      long sourceMaximumLogical,
      long lastCopiedLogical,
      int sourceRootPage,
      int replacementRootPage,
      int replacementLogicalPage,
      int replacementVersionPage,
      int replacementFreePage,
      int nextPage,
      long sourceRootGeneration,
      long replacementRootGeneration,
      long replacementLogicalGeneration,
      long replacementVersionGeneration,
      long replacementFreeGeneration,
      long copiedRows,
      long reclaimedVersions,
      long generation,
      long sourceCommit,
      long appliedCommit) {
    state = progressState;
    sourceStorageGeneration = sourceStorage;
    replacementStorageGeneration = replacementStorage;
    sourceMaximumLogicalRowId = sourceMaximumLogical;
    lastCopiedLogicalRowId = lastCopiedLogical;
    sourceRootDirectoryPageId = sourceRootPage;
    replacementRootDirectoryPageId = replacementRootPage;
    replacementLogicalDirectoryPageId = replacementLogicalPage;
    replacementVersionDirectoryPageId = replacementVersionPage;
    replacementFreePageRootId = replacementFreePage;
    nextPageId = nextPage;
    sourceRootDirectoryGeneration = sourceRootGeneration;
    replacementRootDirectoryGeneration = replacementRootGeneration;
    replacementLogicalDirectoryGeneration = replacementLogicalGeneration;
    replacementVersionDirectoryGeneration = replacementVersionGeneration;
    replacementFreePageGeneration = replacementFreeGeneration;
    rowsCopied = copiedRows;
    versionsReclaimed = reclaimedVersions;
    progressGeneration = generation;
    sourceCommitSequence = sourceCommit;
    appliedCommitSequence = appliedCommit;
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public int state() { return state; }
  public long sourceStorageGeneration() { return sourceStorageGeneration; }
  public long replacementStorageGeneration() { return replacementStorageGeneration; }
  public long sourceMaximumLogicalRowId() { return sourceMaximumLogicalRowId; }
  public long lastCopiedLogicalRowId() { return lastCopiedLogicalRowId; }
  public int sourceRootDirectoryPageId() { return sourceRootDirectoryPageId; }
  public int replacementRootDirectoryPageId() { return replacementRootDirectoryPageId; }
  public int replacementLogicalDirectoryPageId() { return replacementLogicalDirectoryPageId; }
  public int replacementVersionDirectoryPageId() { return replacementVersionDirectoryPageId; }
  public int replacementFreePageRootId() { return replacementFreePageRootId; }
  public int nextPageId() { return nextPageId; }
  public long sourceRootDirectoryGeneration() { return sourceRootDirectoryGeneration; }
  public long replacementRootDirectoryGeneration() { return replacementRootDirectoryGeneration; }
  public long replacementLogicalDirectoryGeneration() {
    return replacementLogicalDirectoryGeneration;
  }
  public long replacementVersionDirectoryGeneration() {
    return replacementVersionDirectoryGeneration;
  }
  public long replacementFreePageGeneration() { return replacementFreePageGeneration; }
  public long rowsCopied() { return rowsCopied; }
  public long versionsReclaimed() { return versionsReclaimed; }
  public long progressGeneration() { return progressGeneration; }
  public long sourceCommitSequence() { return sourceCommitSequence; }
  public long appliedCommitSequence() { return appliedCommitSequence; }
}
