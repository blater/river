package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Test-only access to exact small geometries needed by page-cache mechanics tests. */
public final class DatabasePageCacheTestPlan {
  private DatabasePageCacheTestPlan() {}

  public static DatabasePageCachePlan geometry(
      int currentFrames, int stagingFrames, int activeStagedPages) {
    return DatabasePageCachePlan.testingGeometry(
        currentFrames, stagingFrames, activeStagedPages);
  }

  /** Wraps an exact cache geometry in one matching addressability-bounded test governor. */
  public static DatabaseResourceGovernor governor(
      DatabasePageCachePlan pageCache, int maximumOwners) {
    long versionAddressabilityBytes = 64L * Integer.MAX_VALUE;
    long maximumBytes = pageCache.maximumRetainedBytes() + versionAddressabilityBytes + 2;
    DatabaseResourcePlanRequest request = new DatabaseResourcePlanRequest()
        .memory(maximumBytes, 0, 0, 0, 1)
        .lockProviderBytes(1)
        .versionWorkspaceBytes(versionAddressabilityBytes)
        .indexedPageCache(
            pageCache.maximumRetainedBytes(), pageCache.stagingRetainedBytes())
        .capacity(
            maximumOwners, Integer.MAX_VALUE, pageCache.activeStagedPages(),
            Integer.MAX_VALUE)
        .maximumDelivery(
            Integer.MAX_VALUE, pageCache.activeStagedPages(), Integer.MAX_VALUE);
    DatabaseVersionWorkspacePlan.Result versionResult =
        new DatabaseVersionWorkspacePlan.Result();
    require(StatusCode.OK,
        DatabaseVersionWorkspacePlan.compile(versionAddressabilityBytes, versionResult));
    DatabaseResourcePlan plan = new DatabaseResourcePlan(
        request, maximumBytes, pageCache, versionResult.plan());
    RuntimeResourceRoot.Result rootResult = new RuntimeResourceRoot.Result();
    require(StatusCode.OK, RuntimeResourceRoot.create(maximumBytes, rootResult));
    RuntimeResourceRoot.DatabaseResult databaseResult =
        new RuntimeResourceRoot.DatabaseResult();
    require(StatusCode.OK, rootResult.root().admit(plan, databaseResult));
    return databaseResult.governor();
  }

  public static DatabaseProviderLease providerLease(
      DatabasePageCachePlan pageCache, int maximumOwners) {
    DatabaseResourceGovernor governor = governor(pageCache, maximumOwners);
    DatabaseProviderLease lease = new DatabaseProviderLease();
    require(StatusCode.OK, governor.claimDatabaseProviders(0, lease));
    return lease;
  }

  private static void require(StatusCode expected, StatusCode actual) {
    if (actual != expected) {
      throw new AssertionError(
          "test page-cache resource admission failed: expected=" + expected
              + " actual=" + actual);
    }
  }
}
