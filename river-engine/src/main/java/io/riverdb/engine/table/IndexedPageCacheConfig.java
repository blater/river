package io.riverdb.engine.table;

/** Bounded resident and staged frame ownership supplied at page-set construction. */
final class IndexedPageCacheConfig {
  static final IndexedPageCacheConfig DEFAULT = new IndexedPageCacheConfig(
      4096, 128, 128, IndexedTableLimits.MAX_LOGICAL_CHANGED_PAGES);

  private final int currentFrames;
  private final int stagingFrames;
  private final int eagerFrames;
  private final int activeStagedPages;
  private final int activeMetadataEntries;

  IndexedPageCacheConfig(int current, int staging, int eager) {
    this(current, staging, eager, staging);
  }

  IndexedPageCacheConfig(int current, int staging, int eager, int activeStaged) {
    if (current <= 0 || staging <= 0 || eager < 0
        || eager > current || eager > staging || activeStaged <= 0) {
      throw new IllegalArgumentException("invalid page cache capacity");
    }
    currentFrames = current;
    stagingFrames = staging;
    eagerFrames = eager;
    activeStagedPages = activeStaged;
    activeMetadataEntries = current + activeStaged;
  }

  int currentFrames() { return currentFrames; }
  int stagingFrames() { return stagingFrames; }
  int eagerFrames() { return eagerFrames; }
  int activeStagedPages() { return activeStagedPages; }
  int activeMetadataEntries() { return activeMetadataEntries; }
  int currentMapCapacity() { return mapCapacity(currentFrames); }
  int stagingMapCapacity() { return mapCapacity(stagingFrames); }
  int metadataMapCapacity() { return mapCapacity(activeMetadataEntries); }

  private static int mapCapacity(int entries) {
    int required = entries >= 1 << 29 ? 1 << 30 : entries << 1;
    int capacity = 2;
    while (capacity < required) capacity <<= 1;
    return capacity;
  }
}
