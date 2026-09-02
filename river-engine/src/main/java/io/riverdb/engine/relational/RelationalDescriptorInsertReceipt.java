package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Retained primitive row measurements captured before descriptor INSERT reservation. */
final class RelationalDescriptorInsertReceipt {
  private static final int MAXIMUM_ROWS =
      io.riverdb.base.sql.SqlShapeLimits.MAX_INSERT_ROWS_PER_STATEMENT;
  private static final long RETAINED_BYTES =
      (long) MAXIMUM_ROWS * (Integer.BYTES + Long.BYTES);
  private final RelationalRetainedBudget budget;
  private final RelationalDescriptorBatchAllocator allocator;
  private int[] mutationLengths = new int[0];
  private long[] contentFingerprints = new long[0];

  RelationalDescriptorInsertReceipt(
      RelationalRetainedBudget retainedBudget,
      RelationalDescriptorBatchAllocator batchAllocator) {
    budget = retainedBudget;
    allocator = batchAllocator;
  }

  StatusCode prepare() {
    if (mutationLengths.length == MAXIMUM_ROWS) return StatusCode.OK;
    StatusCode status = budget == null ? StatusCode.OK : budget.reserve(RETAINED_BYTES);
    if (!status.isOk()) return status;
    try {
      int[] nextLengths = allocator.integers(MAXIMUM_ROWS);
      long[] nextFingerprints = allocator.longs(MAXIMUM_ROWS);
      mutationLengths = nextLengths;
      contentFingerprints = nextFingerprints;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      if (budget != null) budget.rollback(RETAINED_BYTES);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void reset(int rows) {
    for (int index = 0; index < rows; index++) {
      mutationLengths[index] = 0;
      contentFingerprints[index] = 0;
    }
  }

  void capture(int row, int bytes, long fingerprint) {
    mutationLengths[row] = bytes;
    contentFingerprints[row] = fingerprint;
  }

  boolean matches(int row, int bytes, long fingerprint) {
    return row >= 0 && row < mutationLengths.length
        && mutationLengths[row] == bytes && contentFingerprints[row] == fingerprint;
  }

  int[] mutationLengths() { return mutationLengths; }

  long retainedBytes() {
    return mutationLengths.length * (long) Integer.BYTES
        + contentFingerprints.length * (long) Long.BYTES;
  }
}
