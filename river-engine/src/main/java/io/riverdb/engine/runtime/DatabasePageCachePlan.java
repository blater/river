package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Immutable indexed-page-cache geometry compiled from embedding-owned byte budgets. */
public final class DatabasePageCachePlan {
  private final int currentFrames;
  private final int stagingFrames;
  private final int activeStagedPages;
  private final int activeMetadataEntries;
  private final int currentMapCapacity;
  private final int stagingMapCapacity;
  private final int metadataMapCapacity;
  private final long maximumRetainedBytes;
  private final long stagingRetainedBytes;

  DatabasePageCachePlan(
      int current,
      int staging,
      int activeStaged,
      int currentMap,
      int stagingMap,
      int metadataMap,
      long retainedBytes,
      long retainedStagingBytes) {
    currentFrames = current;
    stagingFrames = staging;
    activeStagedPages = activeStaged;
    activeMetadataEntries = current + activeStaged;
    currentMapCapacity = currentMap;
    stagingMapCapacity = stagingMap;
    metadataMapCapacity = metadataMap;
    maximumRetainedBytes = retainedBytes;
    stagingRetainedBytes = retainedStagingBytes;
  }

  public static StatusCode compile(
      long maximumCacheBytes,
      long maximumStagingFrameBytes,
      long activeStagedPageCapacity,
      Result result) {
    return DatabasePageCachePlanCompiler.compile(
        maximumCacheBytes, maximumStagingFrameBytes, activeStagedPageCapacity, result);
  }

  static DatabasePageCachePlan testingGeometry(
      int current, int staging, int activeStaged) {
    return DatabasePageCachePlanCompiler.testingGeometry(current, staging, activeStaged);
  }

  public int currentFrames() { return currentFrames; }
  public int stagingFrames() { return stagingFrames; }
  public int activeStagedPages() { return activeStagedPages; }
  public int activeMetadataEntries() { return activeMetadataEntries; }
  public int currentMapCapacity() { return currentMapCapacity; }
  public int stagingMapCapacity() { return stagingMapCapacity; }
  public int metadataMapCapacity() { return metadataMapCapacity; }
  public long maximumRetainedBytes() { return maximumRetainedBytes; }
  public long stagingRetainedBytes() { return stagingRetainedBytes; }

  public static final class Result {
    private DatabasePageCachePlan plan;

    public void reset() { plan = null; }
    void set(DatabasePageCachePlan value) { plan = value; }
    public DatabasePageCachePlan plan() { return plan; }
  }
}
