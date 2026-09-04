package io.riverdb.bench.tpcc;

import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;

/** Explicit resource fixture for benchmark tests that do not test admission policy. */
final class TpccTestDatabaseResources {
  private static final long MAXIMUM_ACCOUNTED_BYTES = 256_000_000L;
  private static final long MAXIMUM_DELIVERY_BYTES = 64_000_000L;
  private static final long STAGED_PAGE_CAPACITY = 800L;

  private TpccTestDatabaseResources() {}

  static DatabaseResourcePlanRequest databaseRequest(int maximumOwners) {
    return new DatabaseResourcePlanRequest()
        .memory(MAXIMUM_ACCOUNTED_BYTES, 0, 0, 0, MAXIMUM_DELIVERY_BYTES)
        .lockProviderBytes(8_000_000L)
        .versionWorkspaceBytes(8_000_000L)
        .indexedPageCache(32_000_000L, 8_000_000L)
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
