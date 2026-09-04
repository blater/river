package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;

/** Retained primitive row measurements captured before descriptor INSERT reservation. */
final class RelationalDescriptorInsertReceipt {
  private static final int INITIAL_ROWS = 8;
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

  StatusCode prepare(int requiredRows) {
    if (requiredRows <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (requiredRows <= mutationLengths.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        mutationLengths.length, requiredRows, Integer.MAX_VALUE, INITIAL_ROWS);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long replacementBytes = retainedBytes(capacity);
    StatusCode status = budget == null ? StatusCode.OK : budget.reserve(replacementBytes);
    if (!status.isOk()) return status;
    try {
      // begin() has reset the prior statement, so growth replaces rather than copies its receipt.
      int[] nextLengths = allocator.integers(capacity);
      long[] nextFingerprints = allocator.longs(capacity);
      long retiredBytes = retainedBytes();
      mutationLengths = nextLengths;
      contentFingerprints = nextFingerprints;
      if (budget != null && retiredBytes > 0) budget.rollback(retiredBytes);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      if (budget != null) budget.rollback(replacementBytes);
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
    return retainedBytes(mutationLengths.length);
  }

  private static long retainedBytes(int rows) {
    return (long) rows * (Integer.BYTES + Long.BYTES);
  }
}
