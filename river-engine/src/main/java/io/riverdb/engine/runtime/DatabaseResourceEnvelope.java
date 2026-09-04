package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagePool;

/** Admits one explicit database resource request before any durable file is opened. */
public final class DatabaseResourceEnvelope {
  private DatabaseResourceEnvelope() {}

  public static StatusCode create(
      DatabaseResourcePlanRequest request,
      long initialRetainedRuntimeBytes,
      Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    DatabaseResourcePlan.Result planResult = new DatabaseResourcePlan.Result();
    StatusCode status = DatabaseResourcePlanCompiler.compile(
        request, initialRetainedRuntimeBytes, planResult);
    if (!status.isOk()) return status;
    RuntimeResourceRoot.Result rootResult = new RuntimeResourceRoot.Result();
    status = RuntimeResourceRoot.create(
        planResult.plan().maximumAccountedBytes(), rootResult);
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
