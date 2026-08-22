package io.riverdb.format.checkpoint;

/** Caller-owned decoded checkpoint roots and high-watermarks. */
public final class CheckpointManifest {
  private long databaseHigh;
  private long databaseLow;
  private long walGeneration;
  private long checkpointId;
  private long commitSequence;
  private long maximumTransactionId;
  private long maximumLogicalRowId;
  private long maximumVersionId;
  private int nextPageId;
  private int rootDirectoryPageId;
  private int logicalDirectoryPageId;
  private int versionDirectoryPageId;
  private int freePageRootId;
  private int extentCount;
  private long rootDirectoryGeneration;
  private long logicalDirectoryGeneration;
  private long versionDirectoryGeneration;
  private long freePageGeneration;
  private long storageGeneration;

  void set(
      long dbHigh,
      long dbLow,
      long wal,
      long checkpoint,
      long commit,
      long maximumTransaction,
      long maximumLogical,
      long maximumVersion,
      int nextPage,
      int rootDirectoryPage,
      int logicalDirectoryPage,
      int versionDirectoryPage,
      int freeRootPage,
      int extents,
      long rootDirectoryGen,
      long logicalDirectoryGen,
      long versionDirectoryGen,
      long freePageGen,
      long storageGen) {
    databaseHigh = dbHigh;
    databaseLow = dbLow;
    walGeneration = wal;
    checkpointId = checkpoint;
    commitSequence = commit;
    maximumTransactionId = maximumTransaction;
    maximumLogicalRowId = maximumLogical;
    maximumVersionId = maximumVersion;
    nextPageId = nextPage;
    rootDirectoryPageId = rootDirectoryPage;
    logicalDirectoryPageId = logicalDirectoryPage;
    versionDirectoryPageId = versionDirectoryPage;
    freePageRootId = freeRootPage;
    extentCount = extents;
    rootDirectoryGeneration = rootDirectoryGen;
    logicalDirectoryGeneration = logicalDirectoryGen;
    versionDirectoryGeneration = versionDirectoryGen;
    freePageGeneration = freePageGen;
    storageGeneration = storageGen;
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public long databaseHigh() { return databaseHigh; }
  public long databaseLow() { return databaseLow; }
  public long walGeneration() { return walGeneration; }
  public long checkpointId() { return checkpointId; }
  public long commitSequence() { return commitSequence; }
  public long maximumTransactionId() { return maximumTransactionId; }
  public long maximumLogicalRowId() { return maximumLogicalRowId; }
  public long maximumVersionId() { return maximumVersionId; }
  public int nextPageId() { return nextPageId; }
  public int rootDirectoryPageId() { return rootDirectoryPageId; }
  public int logicalDirectoryPageId() { return logicalDirectoryPageId; }
  public int versionDirectoryPageId() { return versionDirectoryPageId; }
  public int freePageRootId() { return freePageRootId; }
  public int extentCount() { return extentCount; }
  public long rootDirectoryGeneration() { return rootDirectoryGeneration; }
  public long logicalDirectoryGeneration() { return logicalDirectoryGeneration; }
  public long versionDirectoryGeneration() { return versionDirectoryGeneration; }
  public long freePageGeneration() { return freePageGeneration; }
  public long storageGeneration() { return storageGeneration; }
}
