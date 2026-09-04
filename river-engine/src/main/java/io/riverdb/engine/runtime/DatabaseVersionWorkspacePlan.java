package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Byte-derived capacity for the reusable one-long/three-int version-operation workspace. */
public final class DatabaseVersionWorkspacePlan {
  private final long maximumRetainedBytes;
  private final int maximumOperations;

  private DatabaseVersionWorkspacePlan(long retainedBytes, int operations) {
    maximumRetainedBytes = retainedBytes;
    maximumOperations = operations;
  }

  public static StatusCode compile(long maximumBytes, Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (maximumBytes <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int low = 0;
    int high = Integer.MAX_VALUE;
    while (low < high) {
      int candidate = low + (int) (((long) high - low + 1) / 2);
      long bytes = retainedBytes(candidate);
      if (bytes >= 0 && bytes <= maximumBytes) low = candidate;
      else high = candidate - 1;
    }
    if (low == 0) return StatusCode.RESOURCE_EXHAUSTED;
    long retained = retainedBytes(low);
    try {
      result.set(new DatabaseVersionWorkspacePlan(retained, low));
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  public long maximumRetainedBytes() { return maximumRetainedBytes; }
  public int maximumOperations() { return maximumOperations; }

  public static long retainedBytes(int operations) {
    long longs = DatabasePrimitiveChunkLayout.retainedBytes(
        operations, operations, Long.BYTES);
    long ints = DatabasePrimitiveChunkLayout.retainedBytes(
        operations, operations, Integer.BYTES);
    return longs < 0 || ints < 0 || ints > (Long.MAX_VALUE - longs) / 3
        ? -1 : longs + ints * 3;
  }

  public static final class Result {
    private DatabaseVersionWorkspacePlan plan;
    public void reset() { plan = null; }
    private void set(DatabaseVersionWorkspacePlan value) { plan = value; }
    public DatabaseVersionWorkspacePlan plan() { return plan; }
  }
}
