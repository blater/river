package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.file.Path;

/** Immutable database-local runtime settings admitted before database files open. */
public final class RiverRuntimeConfig {
  public static final String FILE_NAME = "river.properties";
  public static final int DEFAULT_MATERIALIZED_PAGE_BYTES = 64_000;
  public static final long MAXIMUM_AUTO_CACHE_BYTES = 256_000_000L;
  public static final long MINIMUM_SCHEMA_CACHE_BYTES = 8_000_000L;
  public static final long MAXIMUM_AUTO_SCHEMA_CACHE_BYTES = 32_000_000L;
  public static final long MAXIMUM_SCHEMA_CACHE_BYTES = 1_000_000_000L;
  public static final long MINIMUM_SESSION_SHAPE_CACHE_BYTES = 8_000_000L;
  public static final long MAXIMUM_AUTO_SESSION_SHAPE_CACHE_BYTES = 64_000_000L;
  public static final long MAXIMUM_SESSION_SHAPE_CACHE_BYTES = 1_000_000_000L;
  public static final long MINIMUM_SUPPORTED_HEAP_BYTES = 32_000_000L;
  public static final int MINIMUM_CACHE_PAGES = 4;
  public static final int MINIMUM_SORT_RUN_PAGES = 2;
  public static final int MAXIMUM_CONFIG_BYTES = 16_384;
  public static final int MAXIMUM_LINE_BYTES = 4_096;

  private final long cacheBytes;
  private final long schemaCacheBytes;
  private final long sessionShapeCacheBytes;
  private final int pageBytes;
  private final int cachePages;
  private final long sortRunBytes;
  private final int sortRunPages;
  private final int hashBuildRows;
  private final int hashBuckets;
  private final int hashPages;
  private final long lockWaitTimeoutNanos;
  private final Path spillDirectory;

  RiverRuntimeConfig(
      long configuredCacheBytes,
      long configuredSchemaCacheBytes,
      long configuredSessionShapeCacheBytes,
      int configuredPageBytes,
      int configuredCachePages,
      long configuredSortRunBytes,
      int configuredSortRunPages,
      int configuredHashBuildRows,
      int configuredHashBuckets,
      int configuredHashPages,
      long configuredLockWaitTimeoutNanos,
      Path configuredSpillDirectory) {
    cacheBytes = configuredCacheBytes;
    schemaCacheBytes = configuredSchemaCacheBytes;
    sessionShapeCacheBytes = configuredSessionShapeCacheBytes;
    pageBytes = configuredPageBytes;
    cachePages = configuredCachePages;
    sortRunBytes = configuredSortRunBytes;
    sortRunPages = configuredSortRunPages;
    hashBuildRows = configuredHashBuildRows;
    hashBuckets = configuredHashBuckets;
    hashPages = configuredHashPages;
    lockWaitTimeoutNanos = configuredLockWaitTimeoutNanos;
    spillDirectory = configuredSpillDirectory;
  }

  public static StatusCode load(
      Path databaseDirectory,
      Result result,
      StatusDetail detail) {
    return RiverRuntimeConfigLoader.load(databaseDirectory, result, detail);
  }

  public static StatusCode load(
      Path databaseDirectory,
      long maximumMemoryBytes,
      Result result,
      StatusDetail detail) {
    return RiverRuntimeConfigLoader.load(
        databaseDirectory, maximumMemoryBytes, result, detail);
  }

  public static StatusCode load(
      Path databaseDirectory,
      long maximumMemoryBytes,
      CharSequence temporaryDirectory,
      Result result,
      StatusDetail detail) {
    return RiverRuntimeConfigLoader.load(
        databaseDirectory,
        maximumMemoryBytes,
        temporaryDirectory,
        result,
        detail,
        RuntimeSpillDirectory.DEFAULT_PROBE);
  }

  static StatusCode load(
      Path databaseDirectory,
      long maximumMemoryBytes,
      CharSequence temporaryDirectory,
      Result result,
      StatusDetail detail,
      RuntimeSpillProbe spillProbe) {
    return RiverRuntimeConfigLoader.load(
        databaseDirectory,
        maximumMemoryBytes,
        temporaryDirectory,
        result,
        detail,
        spillProbe);
  }

  public long cacheBytes() { return cacheBytes; }
  public long schemaCacheBytes() { return schemaCacheBytes; }
  public long sessionShapeCacheBytes() { return sessionShapeCacheBytes; }
  public int pageBytes() { return pageBytes; }
  public int cachePages() { return cachePages; }
  public long sortRunBytes() { return sortRunBytes; }
  public int sortRunPages() { return sortRunPages; }
  public int hashBuildRows() { return hashBuildRows; }
  public int hashBuckets() { return hashBuckets; }
  public int hashPages() { return hashPages; }
  public long lockWaitTimeoutNanos() { return lockWaitTimeoutNanos; }
  public Path spillDirectory() { return spillDirectory; }

  public static final class Result {
    private RiverRuntimeConfig config;

    public void reset() { config = null; }
    void set(RiverRuntimeConfig loaded) { config = loaded; }
    public RiverRuntimeConfig config() { return config; }
  }
}
