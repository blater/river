package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Embedding-owned River memory envelope and database-child admission authority. */
public final class RuntimeResourceRoot {
  private final long maximumAccountedBytes;
  private long admittedAccountedBytes;
  private long nextDatabaseToken = 1;

  private RuntimeResourceRoot(long maximumBytes) { maximumAccountedBytes = maximumBytes; }

  public static StatusCode create(long maximumBytes, Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (maximumBytes <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    try {
      result.set(new RuntimeResourceRoot(maximumBytes));
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  public synchronized StatusCode admit(
      DatabaseResourcePlan plan, DatabaseResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (plan == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!ResourceOwnerIndex.supports(plan.maximumOwners())) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long requested = plan.maximumAccountedBytes();
    if (requested > maximumAccountedBytes) return StatusCode.RESOURCE_EXHAUSTED;
    if (requested > maximumAccountedBytes - admittedAccountedBytes) return StatusCode.RETRY;
    if (nextDatabaseToken <= 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      result.set(new DatabaseResourceGovernor(this, plan, nextDatabaseToken++));
      admittedAccountedBytes += requested;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  synchronized StatusCode release(long token, long bytes) {
    if (token <= 0 || bytes <= 0 || bytes > admittedAccountedBytes) {
      return StatusCode.INVARIANT_BROKEN;
    }
    admittedAccountedBytes -= bytes;
    return StatusCode.OK;
  }

  public long maximumAccountedBytes() { return maximumAccountedBytes; }
  public synchronized long admittedAccountedBytes() { return admittedAccountedBytes; }
  public synchronized long availableAccountedBytes() {
    return maximumAccountedBytes - admittedAccountedBytes;
  }

  public static final class Result {
    private RuntimeResourceRoot root;
    public void reset() { root = null; }
    void set(RuntimeResourceRoot value) { root = value; }
    public RuntimeResourceRoot root() { return root; }
  }

  public static final class DatabaseResult {
    private DatabaseResourceGovernor governor;
    public void reset() { governor = null; }
    void set(DatabaseResourceGovernor value) { governor = value; }
    public DatabaseResourceGovernor governor() { return governor; }
  }
}
