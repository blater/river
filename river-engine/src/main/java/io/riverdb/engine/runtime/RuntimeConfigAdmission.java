package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagePool;
import java.nio.file.Path;

/** Validates memory budgets and retains only admitted primitive values. */
final class RuntimeConfigAdmission {
  private static final int MINIMUM_PAGE_BYTES = 4_000;
  private static final int MAXIMUM_PAGE_BYTES = 16_000_000;

  private long cacheBytes;
  private final RuntimeRetainedCacheAdmission retainedCaches =
      new RuntimeRetainedCacheAdmission();
  private int pageBytes;
  private int cachePages;
  private long sortRunBytes;
  private int sortRunPages;
  private int hashBuildRows;
  private int hashBuckets;
  private int hashPages;
  private long lockWaitTimeoutNanos;

  StatusCode admit(
      RuntimeConfigProperties properties,
      long maximumMemoryBytes,
      StatusDetail detail) {
    if (maximumMemoryBytes < RiverRuntimeConfig.MINIMUM_SUPPORTED_HEAP_BYTES) {
      return RuntimeConfigNumbers.invalid(
          detail,
          RuntimeConfigProperties.SCHEMA_CACHE,
          properties.schemaCache(),
          "maximum heap must be at least 32MB");
    }
    StatusCode status = admitLockWaitTimeout(properties, detail);
    if (!status.isOk()) return status;
    status = admitPage(properties, detail);
    if (!status.isOk()) return status;
    status = admitCache(properties, maximumMemoryBytes, detail);
    if (!status.isOk()) return status;
    status = retainedCaches.admit(properties, maximumMemoryBytes, detail);
    if (!status.isOk()) return status;
    status = admitSortRun(properties, detail);
    if (!status.isOk()) return status;
    return admitHash(properties, detail);
  }

  RiverRuntimeConfig toConfig(Path spillDirectory) {
    return new RiverRuntimeConfig(
        cacheBytes,
        retainedCaches.schemaCacheBytes(),
        retainedCaches.sessionShapeCacheBytes(),
        pageBytes,
        cachePages,
        sortRunBytes,
        sortRunPages,
        hashBuildRows,
        hashBuckets,
        hashPages,
        lockWaitTimeoutNanos,
        spillDirectory);
  }

  private StatusCode admitLockWaitTimeout(
      RuntimeConfigProperties properties,
      StatusDetail detail) {
    lockWaitTimeoutNanos = RuntimeConfigNumbers.parseDurationNanos(
        RuntimeConfigProperties.LOCK_WAIT_TIMEOUT,
        properties.lockWaitTimeout(),
        detail);
    return lockWaitTimeoutNanos < 0 ? detail.code() : StatusCode.OK;
  }

  private StatusCode admitPage(
      RuntimeConfigProperties properties,
      StatusDetail detail) {
    long value = RuntimeConfigNumbers.parseSize(
        RuntimeConfigProperties.PAGE, properties.page(), detail);
    if (value < 0) return detail.code();
    if (value < MINIMUM_PAGE_BYTES
        || value > MAXIMUM_PAGE_BYTES
        || (value & 7) != 0) {
      return RuntimeConfigNumbers.invalid(
          detail,
          RuntimeConfigProperties.PAGE,
          properties.page(),
          "expected multiple of 8, 4KB..16MB");
    }
    pageBytes = (int) value;
    return StatusCode.OK;
  }

  private StatusCode admitCache(
      RuntimeConfigProperties properties,
      long maximumMemoryBytes,
      StatusDetail detail) {
    long requested = properties.cache().equals("auto")
        ? Math.min(maximumMemoryBytes / 8, RiverRuntimeConfig.MAXIMUM_AUTO_CACHE_BYTES)
        : RuntimeConfigNumbers.parseSize(
            RuntimeConfigProperties.CACHE, properties.cache(), detail);
    if (requested < 0) return detail.code();
    cacheBytes = RuntimeConfigNumbers.roundDown(requested, pageBytes);
    long pageCount = cacheBytes / pageBytes;
    if (pageCount < RiverRuntimeConfig.MINIMUM_CACHE_PAGES) {
      return RuntimeConfigNumbers.invalid(
          detail,
          RuntimeConfigProperties.CACHE,
          properties.cache(),
          "must hold at least 4 pages");
    }
    if (pageCount > Integer.MAX_VALUE
        || pageCount * SqlMaterializedPagePool.MAXIMUM_METADATA_BYTES_PER_FRAME
            > Integer.MAX_VALUE) {
      return RuntimeConfigNumbers.invalid(
          detail,
          RuntimeConfigProperties.CACHE,
          properties.cache(),
          "frame metadata is not addressable");
    }
    cachePages = (int) pageCount;
    return StatusCode.OK;
  }

  private StatusCode admitSortRun(
      RuntimeConfigProperties properties,
      StatusDetail detail) {
    long requested;
    if (properties.sortRun().equals("auto")) {
      requested = Math.max(
          (long) RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES * pageBytes,
          cacheBytes / 4);
    } else {
      requested = RuntimeConfigNumbers.parseSize(
          RuntimeConfigProperties.SORT_RUN, properties.sortRun(), detail);
      if (requested < 0) return detail.code();
    }
    sortRunBytes = RuntimeConfigNumbers.roundDown(requested, pageBytes);
    long pageCount = sortRunBytes / pageBytes;
    if (pageCount < RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES
        || pageCount > Integer.MAX_VALUE
        || pageCount + 2 > cachePages) {
      return RuntimeConfigNumbers.invalid(
          detail,
          RuntimeConfigProperties.SORT_RUN,
          properties.sortRun(),
          "must hold at least 2 pages and leave 2 cache pages");
    }
    sortRunPages = (int) pageCount;
    return StatusCode.OK;
  }

  private StatusCode admitHash(
      RuntimeConfigProperties properties,
      StatusDetail detail) {
    long rows = RuntimeConfigNumbers.parseUnsignedDecimal(
        RuntimeConfigProperties.HASH_BUILD_ROWS, properties.hashBuildRows(), detail);
    if (rows < 1 || rows > RiverRuntimeConfig.MAXIMUM_HASH_BUILD_ROWS) {
      if (rows >= 0) {
        RuntimeConfigNumbers.invalid(
            detail,
            RuntimeConfigProperties.HASH_BUILD_ROWS,
            properties.hashBuildRows(),
            "expected 1..1048576");
      }
      return detail.code();
    }
    long buckets = RuntimeConfigNumbers.parseUnsignedDecimal(
        RuntimeConfigProperties.HASH_BUCKETS, properties.hashBuckets(), detail);
    if (buckets < 2
        || buckets > RiverRuntimeConfig.MAXIMUM_HASH_BUCKETS
        || (buckets & (buckets - 1)) != 0) {
      if (buckets >= 0) {
        RuntimeConfigNumbers.invalid(
            detail,
            RuntimeConfigProperties.HASH_BUCKETS,
            properties.hashBuckets(),
            "expected power of two, 2..1048576");
      }
      return detail.code();
    }
    long bytes = rows * 12 + buckets * 8;
    long pageCount = (bytes + pageBytes - 1) / pageBytes;
    if (pageCount + 2 > cachePages) return incompatibleHash(properties, detail);
    hashBuildRows = (int) rows;
    hashBuckets = (int) buckets;
    hashPages = (int) pageCount;
    return StatusCode.OK;
  }

  private static StatusCode incompatibleHash(
      RuntimeConfigProperties properties,
      StatusDetail detail) {
    detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
        .append("incompatible hash workspace: ")
        .append(RuntimeConfigProperties.HASH_BUILD_ROWS)
        .append('=')
        .append(properties.hashBuildRows())
        .append(", ")
        .append(RuntimeConfigProperties.HASH_BUCKETS)
        .append('=')
        .append(properties.hashBuckets())
        .append(" (must leave 2 cache pages)");
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
