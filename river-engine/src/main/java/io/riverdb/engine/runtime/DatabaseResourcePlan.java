package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Immutable database-local ledgers admitted before durable files open. */
public final class DatabaseResourcePlan {
  private final long maximumAccountedBytes;
  private final long accountedCapacityBytes;
  private final long minimumOwnerBytes;
  private final long maximumDeliveryAccountedBytes;
  private final int maximumOwners;
  private final long writeEntryCapacity;
  private final DatabaseVersionWorkspacePlan versionWorkspace;
  private final long lockProviderBytes;
  private final DatabasePageCachePlan indexedPageCache;
  private final long walByteCapacity;
  private final long maximumDeliveryWriteEntries;
  private final long maximumDeliveryStagedPages;
  private final long maximumDeliveryWalBytes;

  DatabaseResourcePlan(
      DatabaseResourcePlanRequest request,
      long accountedCapacity,
      DatabasePageCachePlan pageCache,
      DatabaseVersionWorkspacePlan compiledVersionWorkspace) {
    long maximumBytes = request.maximumAccountedBytes;
    maximumAccountedBytes = maximumBytes;
    accountedCapacityBytes = accountedCapacity;
    minimumOwnerBytes = request.minimumOwnerBytes;
    maximumDeliveryAccountedBytes = request.maximumDeliveryAccountedBytes;
    maximumOwners = request.maximumOwners;
    writeEntryCapacity = request.writeEntryCapacity;
    versionWorkspace = compiledVersionWorkspace;
    lockProviderBytes = request.lockProviderBytes;
    indexedPageCache = pageCache;
    walByteCapacity = request.walByteCapacity;
    maximumDeliveryWriteEntries = request.maximumDeliveryWriteEntries;
    maximumDeliveryStagedPages = request.maximumDeliveryStagedPages;
    maximumDeliveryWalBytes = request.maximumDeliveryWalBytes;
  }

  public static StatusCode compile(
      DatabaseResourcePlanRequest request, Result result) {
    return DatabaseResourcePlanCompiler.compile(request, result);
  }

  public long maximumAccountedBytes() { return maximumAccountedBytes; }
  /** Lendable Delivery bytes after fixed, progress, and every owner minimum reserve. */
  public long accountedCapacityBytes() { return accountedCapacityBytes; }
  public long minimumOwnerBytes() { return minimumOwnerBytes; }
  public long maximumDeliveryAccountedBytes() { return maximumDeliveryAccountedBytes; }
  public int maximumOwners() { return maximumOwners; }
  public long writeEntryCapacity() { return writeEntryCapacity; }
  public long versionOperationCapacity() { return versionWorkspace.maximumOperations(); }
  public DatabaseVersionWorkspacePlan versionWorkspace() { return versionWorkspace; }
  /** Database-lifetime retained bytes for the lazily growing canonical lock provider. */
  public long lockProviderBytes() { return lockProviderBytes; }
  public long stagedPageCapacity() { return indexedPageCache.activeStagedPages(); }
  /** Physical cache authority admitted before the indexed store opens. */
  public DatabasePageCachePlan indexedPageCache() { return indexedPageCache; }
  public long walByteCapacity() { return walByteCapacity; }
  public long maximumDeliveryWriteEntries() { return maximumDeliveryWriteEntries; }
  public long maximumDeliveryStagedPages() { return maximumDeliveryStagedPages; }
  public long maximumDeliveryWalBytes() { return maximumDeliveryWalBytes; }

  public static final class Result {
    private DatabaseResourcePlan plan;
    public void reset() { plan = null; }
    void set(DatabaseResourcePlan value) { plan = value; }
    public DatabaseResourcePlan plan() { return plan; }
  }
}
