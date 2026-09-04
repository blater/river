package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Owns checked page-cache retained-memory accounting and structural map geometry. */
final class DatabasePageCachePlanCompiler {
  /** Parent/child traversal must retain two current frames simultaneously. */
  private static final int MINIMUM_CURRENT_FRAMES = 2;
  /** One mutation frame plus one frame reserved for rollback/cleanup progress. */
  private static final int MINIMUM_STAGING_FRAMES = 2;
  /** A 2x open-addressed map can represent at most this many entries in a Java array. */
  private static final int MAXIMUM_MAP_ENTRIES = 1 << 29;

  private DatabasePageCachePlanCompiler() {}

  static StatusCode compile(
      long maximumCacheBytes,
      long maximumStagingFrameBytes,
      long activeStagedPageCapacity,
      DatabasePageCachePlan.Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (maximumCacheBytes <= 0 || maximumStagingFrameBytes <= 0
        || maximumStagingFrameBytes >= maximumCacheBytes
        || activeStagedPageCapacity <= 0
        || activeStagedPageCapacity > MAXIMUM_MAP_ENTRIES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int activeStaged = (int) activeStagedPageCapacity;
    int staging = maximumStagingFrames(maximumStagingFrameBytes);
    if (staging < MINIMUM_STAGING_FRAMES) return StatusCode.RESOURCE_EXHAUSTED;
    int current = maximumCurrentFrames(maximumCacheBytes, staging, activeStaged);
    if (current < MINIMUM_CURRENT_FRAMES) return StatusCode.RESOURCE_EXHAUSTED;
    return publish(
        current, staging, activeStaged,
        maximumCacheBytes, maximumStagingFrameBytes, result);
  }

  static DatabasePageCachePlan testingGeometry(
      int current, int staging, int activeStaged) {
    int currentMap = mapCapacity(current);
    int stagingMap = mapCapacity(staging);
    int metadataMap = mapCapacity(checkedAdd(current, activeStaged));
    if (current <= 0 || staging <= 0 || activeStaged <= 0
        || currentMap == 0 || stagingMap == 0 || metadataMap == 0) {
      throw new IllegalArgumentException("invalid structural test geometry");
    }
    return create(current, staging, activeStaged, currentMap, stagingMap, metadataMap);
  }

  private static StatusCode publish(
      int current,
      int staging,
      int activeStaged,
      long maximumCacheBytes,
      long maximumStagingFrameBytes,
      DatabasePageCachePlan.Result result) {
    int currentMap = mapCapacity(current);
    int stagingMap = mapCapacity(staging);
    int metadataMap = mapCapacity(checkedAdd(current, activeStaged));
    if (currentMap == 0 || stagingMap == 0 || metadataMap == 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    try {
      DatabasePageCachePlan plan = create(
          current, staging, activeStaged, currentMap, stagingMap, metadataMap);
      if (plan.maximumRetainedBytes() < 0
          || plan.maximumRetainedBytes() > maximumCacheBytes
          || plan.stagingRetainedBytes() < 0
          || plan.stagingRetainedBytes() > maximumStagingFrameBytes) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      result.set(plan);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static DatabasePageCachePlan create(
      int current,
      int staging,
      int activeStaged,
      int currentMap,
      int stagingMap,
      int metadataMap) {
    return new DatabasePageCachePlan(
        current, staging, activeStaged, currentMap, stagingMap, metadataMap,
        DatabasePageCacheRetainedLayout.retainedBytes(
            current, staging, activeStaged, currentMap, stagingMap, metadataMap),
        DatabasePageCacheRetainedLayout.stagingFrameBytes(staging, stagingMap));
  }

  private static int maximumStagingFrames(long budget) {
    int low = 0;
    int high = DatabasePageCacheRetainedLayout.maximumFrameCandidate(budget);
    while (low < high) {
      int candidate = low + (high - low + 1) / 2;
      int map = mapCapacity(candidate);
      long bytes = map == 0 ? -1
          : DatabasePageCacheRetainedLayout.stagingFrameBytes(candidate, map);
      if (bytes >= 0 && bytes <= budget) low = candidate;
      else high = candidate - 1;
    }
    return low;
  }

  private static int maximumCurrentFrames(
      long budget, int staging, int activeStaged) {
    int upper = Math.min(
        DatabasePageCacheRetainedLayout.maximumFrameCandidate(budget),
        MAXIMUM_MAP_ENTRIES - activeStaged);
    int low = 0;
    int high = upper;
    int stagingMap = mapCapacity(staging);
    while (low < high) {
      int candidate = low + (high - low + 1) / 2;
      int currentMap = mapCapacity(candidate);
      int metadataMap = mapCapacity(checkedAdd(candidate, activeStaged));
      long bytes = currentMap == 0 || metadataMap == 0 ? -1
          : DatabasePageCacheRetainedLayout.retainedBytes(
              candidate, staging, activeStaged, currentMap, stagingMap, metadataMap);
      if (bytes >= 0 && bytes <= budget) low = candidate;
      else high = candidate - 1;
    }
    return low;
  }

  private static int mapCapacity(int entries) {
    if (entries <= 0 || entries > MAXIMUM_MAP_ENTRIES) return 0;
    int required = entries << 1;
    int capacity = 2;
    while (capacity < required) capacity <<= 1;
    return capacity;
  }

  private static int checkedAdd(int left, int right) {
    return left < 0 || right < 0 || left > Integer.MAX_VALUE - right ? -1 : left + right;
  }
}
