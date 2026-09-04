package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.engine.runtime.DatabaseProviderLease;
import io.riverdb.engine.runtime.DatabaseResourcePlan;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.engine.runtime.RuntimeResourceRoot;

/** Shared explicit resource profile for tests that do not exercise resource admission. */
public final class TestDatabaseResources {
  private static final long MAXIMUM_ACCOUNTED_BYTES = 256_000_000L;
  private static final long MAXIMUM_DELIVERY_BYTES = 64_000_000L;
  private static final long LOCK_PROVIDER_BYTES = 8_000_000L;
  private static final long VERSION_WORKSPACE_BYTES = 8_000_000L;
  private static final long PAGE_CACHE_BYTES = 32_000_000L;
  private static final long STAGING_FRAME_BYTES = 8_000_000L;
  private static final long STAGED_PAGE_CAPACITY = 800L;

  private TestDatabaseResources() {}

  /** Returns a fresh caller-owned request with addressable transaction capacities. */
  public static DatabaseResourcePlanRequest databaseRequest(int maximumOwners) {
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

  /** Returns a fresh resource root for direct embedded-kernel lifecycle tests. */
  public static RuntimeResourceRoot runtimeRoot() {
    RuntimeResourceRoot.Result result = new RuntimeResourceRoot.Result();
    require(StatusCode.OK, RuntimeResourceRoot.create(MAXIMUM_ACCOUNTED_BYTES, result));
    return result.root();
  }

  /** Returns the compiled profile for direct embedded-kernel lifecycle tests. */
  public static DatabaseResourcePlan databasePlan(int maximumOwners) {
    DatabaseResourcePlan.Result result = new DatabaseResourcePlan.Result();
    require(
        StatusCode.OK,
        DatabaseResourcePlan.compile(databaseRequest(maximumOwners), result));
    return result.plan();
  }

  /** Returns the profile's immutable page-cache plan for indexed-store tests. */
  public static DatabasePageCachePlan pageCachePlan() {
    return databasePlan(1).indexedPageCache();
  }

  /** Returns one admitted database governor with its fixed providers retained. */
  public static DatabaseResourceGovernor databaseGovernor(int maximumOwners) {
    RuntimeResourceRoot root = runtimeRoot();
    DatabaseResourcePlan plan = databasePlan(maximumOwners);
    RuntimeResourceRoot.DatabaseResult result = new RuntimeResourceRoot.DatabaseResult();
    require(StatusCode.OK, root.admit(plan, result));
    return result.governor();
  }

  /** Returns one governor-authenticated physical-provider lease for an indexed store. */
  public static DatabaseProviderLease databaseProviderLease(int maximumOwners) {
    DatabaseResourceGovernor governor = databaseGovernor(maximumOwners);
    DatabaseProviderLease lease = new DatabaseProviderLease();
    require(StatusCode.OK, governor.claimDatabaseProviders(0, lease));
    return lease;
  }

  private static void require(StatusCode expected, StatusCode actual) {
    if (actual != expected) {
      throw new AssertionError(
          "test resource profile compilation failed: expected=" + expected + " actual=" + actual);
    }
  }
}
