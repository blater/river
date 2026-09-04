package io.riverdb.jdbc;

import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;

/** Explicit configurable resource fixture for JDBC tests unrelated to resource admission. */
final class JdbcTestDatabaseResources {
  private static final long MAXIMUM_ACCOUNTED_BYTES = 256_000_000L;
  private static final long MAXIMUM_DELIVERY_BYTES = 64_000_000L;
  private static final long LOCK_PROVIDER_BYTES = 8_000_000L;
  private static final long VERSION_WORKSPACE_BYTES = 8_000_000L;
  private static final long PAGE_CACHE_BYTES = 32_000_000L;
  private static final long STAGING_FRAME_BYTES = 8_000_000L;
  private static final long STAGED_PAGE_CAPACITY = 800L;

  private JdbcTestDatabaseResources() {}

  static DatabaseResourcePlanRequest databaseRequest(int maximumOwners) {
    return new DatabaseResourcePlanRequest()
        .memory(MAXIMUM_ACCOUNTED_BYTES, 0, 0, 0, MAXIMUM_DELIVERY_BYTES)
        .lockProviderBytes(LOCK_PROVIDER_BYTES)
        .versionWorkspaceBytes(VERSION_WORKSPACE_BYTES)
        .indexedPageCache(PAGE_CACHE_BYTES, STAGING_FRAME_BYTES)
        .capacity(
            maximumOwners,
            Integer.MAX_VALUE,
            STAGED_PAGE_CAPACITY,
            MAXIMUM_DELIVERY_BYTES)
        .maximumDelivery(
            Integer.MAX_VALUE,
            STAGED_PAGE_CAPACITY,
            MAXIMUM_DELIVERY_BYTES);
  }
}
