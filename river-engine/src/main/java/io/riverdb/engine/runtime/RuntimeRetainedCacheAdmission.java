package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Joint admission for database-owned retained SQL metadata and session shapes. */
final class RuntimeRetainedCacheAdmission {
  private long schemaCacheBytes;
  private long sessionShapeCacheBytes;

  StatusCode admit(
      RuntimeConfigProperties properties,
      long maximumMemoryBytes,
      StatusDetail detail) {
    StatusCode status = admitSchema(properties, maximumMemoryBytes, detail);
    if (!status.isOk()) return status;
    status = admitSessionShapes(properties, maximumMemoryBytes, detail);
    if (!status.isOk()) return status;
    if (schemaCacheBytes + sessionShapeCacheBytes > maximumMemoryBytes / 2) {
      return incompatible(properties, detail);
    }
    return StatusCode.OK;
  }

  long schemaCacheBytes() { return schemaCacheBytes; }
  long sessionShapeCacheBytes() { return sessionShapeCacheBytes; }

  private StatusCode admitSchema(
      RuntimeConfigProperties properties,
      long maximumMemoryBytes,
      StatusDetail detail) {
    String configured = properties.schemaCache();
    long requested = configured.equals("auto")
        ? Math.max(
            RiverRuntimeConfig.MINIMUM_SCHEMA_CACHE_BYTES,
            Math.min(
                maximumMemoryBytes / 16,
                RiverRuntimeConfig.MAXIMUM_AUTO_SCHEMA_CACHE_BYTES))
        : RuntimeConfigNumbers.parseSize(
            RuntimeConfigProperties.SCHEMA_CACHE, configured, detail);
    long maximum = Math.min(
        RiverRuntimeConfig.MAXIMUM_SCHEMA_CACHE_BYTES, maximumMemoryBytes / 2);
    if (requested < RiverRuntimeConfig.MINIMUM_SCHEMA_CACHE_BYTES || requested > maximum) {
      if (requested >= 0) {
        RuntimeConfigNumbers.invalid(
            detail,
            RuntimeConfigProperties.SCHEMA_CACHE,
            configured,
            "expected 8MB..min(1GB, half maximum heap)");
      }
      return detail.code();
    }
    schemaCacheBytes = requested;
    return StatusCode.OK;
  }

  private StatusCode admitSessionShapes(
      RuntimeConfigProperties properties,
      long maximumMemoryBytes,
      StatusDetail detail) {
    String configured = properties.sessionShapeCache();
    if (configured.endsWith("KB")) {
      return RuntimeConfigNumbers.invalid(
          detail,
          RuntimeConfigProperties.SESSION_SHAPE_CACHE,
          configured,
          "expected bytes, MB, or GB");
    }
    long requested = configured.equals("auto")
        ? Math.max(
            RiverRuntimeConfig.MINIMUM_SESSION_SHAPE_CACHE_BYTES,
            Math.min(
                maximumMemoryBytes / 8,
                RiverRuntimeConfig.MAXIMUM_AUTO_SESSION_SHAPE_CACHE_BYTES))
        : RuntimeConfigNumbers.parseSize(
            RuntimeConfigProperties.SESSION_SHAPE_CACHE, configured, detail);
    if (requested < RiverRuntimeConfig.MINIMUM_SESSION_SHAPE_CACHE_BYTES
        || requested > RiverRuntimeConfig.MAXIMUM_SESSION_SHAPE_CACHE_BYTES) {
      if (requested >= 0) {
        RuntimeConfigNumbers.invalid(
            detail,
            RuntimeConfigProperties.SESSION_SHAPE_CACHE,
            configured,
            "expected 8MB..1GB");
      }
      return detail.code();
    }
    sessionShapeCacheBytes = requested;
    return StatusCode.OK;
  }

  private static StatusCode incompatible(
      RuntimeConfigProperties properties,
      StatusDetail detail) {
    detail.set(StatusCode.INVALID_EXTERNAL_INPUT)
        .append("incompatible retained SQL caches: ")
        .append(RuntimeConfigProperties.SCHEMA_CACHE)
        .append('=')
        .append(properties.schemaCache())
        .append(", ")
        .append(RuntimeConfigProperties.SESSION_SHAPE_CACHE)
        .append('=')
        .append(properties.sessionShapeCache())
        .append(" (combined budget exceeds half maximum heap)");
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
