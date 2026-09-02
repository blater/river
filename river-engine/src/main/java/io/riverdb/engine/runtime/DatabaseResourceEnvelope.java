package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagePool;

/** Compiles one ordinary embedded database's physical envelope from the few public budgets. */
public final class DatabaseResourceEnvelope {
  private static final long MINIMUM_PROGRESS_BYTES = 1_000_000L;
  private static final long MAXIMUM_PROGRESS_BYTES = 64_000_000L;
  private static final long OWNER_PROGRESS_BYTES = 64_000L;
  private static final long ESTIMATED_WRITE_BYTES = 16_384L;
  private static final long MINIMUM_LOCK_PROVIDER_BYTES = 1L << 20;
  private static final long LOCK_PROVIDER_MEMORY_DIVISOR = 8;
  private static final long MAXIMUM_STAGED_PAGES = 1_048_576L;

  private DatabaseResourceEnvelope() {}

  public static StatusCode create(
      long maximumBytes,
      int maximumOwners,
      long retainedRuntimeBytes,
      Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (maximumBytes <= 0 || maximumOwners <= 0 || retainedRuntimeBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long progress = bounded(maximumBytes / 64, MINIMUM_PROGRESS_BYTES,
        MAXIMUM_PROGRESS_BYTES);
    long ownerBytes = multiply(OWNER_PROGRESS_BYTES, maximumOwners);
    long provisional = subtract(maximumBytes, retainedRuntimeBytes, progress, ownerBytes);
    if (provisional <= MINIMUM_LOCK_PROVIDER_BYTES + ESTIMATED_WRITE_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long lockProvider = Math.max(
        MINIMUM_LOCK_PROVIDER_BYTES, provisional / LOCK_PROVIDER_MEMORY_DIVISOR);
    long deliveryBytes = subtract(provisional, lockProvider);
    if (deliveryBytes <= ESTIMATED_WRITE_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long writes = Math.min(
        DatabaseResourceDefaults.MAXIMUM_TRANSACTION_WRITE_ENTRIES,
        deliveryBytes / ESTIMATED_WRITE_BYTES);
    long stagedPages = Math.min(MAXIMUM_STAGED_PAGES,
        Math.max(1, deliveryBytes / RiverRuntimeConfig.DEFAULT_PAGE_BYTES));
    DatabaseResourcePlanRequest request = new DatabaseResourcePlanRequest()
        .memory(maximumBytes, 0, progress, OWNER_PROGRESS_BYTES, deliveryBytes)
        .lockProviderBytes(lockProvider)
        .capacity(maximumOwners, writes, stagedPages, deliveryBytes)
        .maximumDelivery(writes, stagedPages, deliveryBytes);
    DatabaseResourcePlan.Result planResult = new DatabaseResourcePlan.Result();
    StatusCode status = DatabaseResourcePlan.compile(request, planResult);
    if (!status.isOk()) return status;
    RuntimeResourceRoot.Result rootResult = new RuntimeResourceRoot.Result();
    status = RuntimeResourceRoot.create(maximumBytes, rootResult);
    if (status.isOk()) result.set(rootResult.root(), planResult.plan());
    return status;
  }

  public static long retainedSqlRuntimeBytes(RiverRuntimeConfig config) {
    if (config == null) return -1;
    long metadata = multiply(
        config.cachePages(), SqlMaterializedPagePool.MAXIMUM_METADATA_BYTES_PER_FRAME);
    return add(config.cacheBytes(), metadata,
        config.schemaCacheBytes(), config.sessionShapeCacheBytes());
  }

  private static long bounded(long value, long minimum, long maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private static long multiply(long value, long count) {
    return value < 0 || count < 0 || value != 0 && count > Long.MAX_VALUE / value
        ? -1 : value * count;
  }

  private static long add(long first, long second, long third, long fourth) {
    long pairOne = add(first, second);
    long pairTwo = add(third, fourth);
    return pairOne < 0 || pairTwo < 0 ? -1 : add(pairOne, pairTwo);
  }

  private static long add(long first, long second) {
    return first < 0 || second < 0 || first > Long.MAX_VALUE - second
        ? -1 : first + second;
  }

  private static long subtract(long value, long... charges) {
    for (long charge : charges) {
      if (charge < 0 || charge > value) return -1;
      value -= charge;
    }
    return value;
  }

  public static final class Result {
    private RuntimeResourceRoot root;
    private DatabaseResourcePlan plan;

    public void reset() { root = null; plan = null; }
    private void set(RuntimeResourceRoot value, DatabaseResourcePlan compiled) {
      root = value;
      plan = compiled;
    }
    public RuntimeResourceRoot root() { return root; }
    public DatabaseResourcePlan plan() { return plan; }
  }
}
