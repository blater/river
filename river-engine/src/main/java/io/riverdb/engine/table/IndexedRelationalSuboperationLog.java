package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Admission, storage, and accounting lifecycle for relational suboperations. */
class IndexedRelationalSuboperationLog extends IndexedRelationalSuboperationView {
  private int coveredMutations;

  IndexedRelationalSuboperationLog(int capacity) { super(capacity); }

  StatusCode reserve(int additional) {
    if (additional < 0 || additional > columns.capacity() - count) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return columns.reserve(count + additional);
  }

  StatusCode append(
      long owner, long keyId, int descriptor, int firstMutation, int mutations,
      int expectedTupleRoot, int resultingTupleRoot,
      int expectedScalarRoot, int resultingScalarRoot,
      int expectedNextPage, int resultingNextPage,
      long expectedGeneration, long resultingGeneration,
      long expectedHeapVersion, long resultingHeapVersion,
      int expectedRegistryState, int resultingRegistryState,
      long expectedPrivateOwner, long resultingPrivateOwner) {
    int expectedCursor = expectedRegistryState == REGISTRY_DROPPING && expectedTupleRoot == 0
        ? resultingRegistryState == REGISTRY_ABSENT ? expectedNextPage : 4 : 0;
    int resultingCursor = resultingRegistryState == REGISTRY_DROPPING && resultingTupleRoot == 0
        ? expectedRegistryState == REGISTRY_DROPPING && expectedTupleRoot == 0
            ? resultingNextPage : 4
        : 0;
    return append(owner, keyId, descriptor, firstMutation, mutations,
        expectedTupleRoot, resultingTupleRoot, expectedScalarRoot, resultingScalarRoot,
        expectedNextPage, resultingNextPage, expectedGeneration, resultingGeneration,
        expectedHeapVersion, resultingHeapVersion, expectedRegistryState, resultingRegistryState,
        expectedPrivateOwner, resultingPrivateOwner, expectedCursor, resultingCursor);
  }

  StatusCode append(
      long owner, long keyId, int descriptor, int firstMutation, int mutations,
      int expectedTupleRoot, int resultingTupleRoot,
      int expectedScalarRoot, int resultingScalarRoot,
      int expectedNextPage, int resultingNextPage,
      long expectedGeneration, long resultingGeneration,
      long expectedHeapVersion, long resultingHeapVersion,
      int expectedRegistryState, int resultingRegistryState,
      long expectedPrivateOwner, long resultingPrivateOwner,
      int expectedCleanupCursor, int resultingCleanupCursor) {
    if (!IndexedRelationalSuboperationAdmission.valid(
        columns, count, coveredMutations, owner, keyId, descriptor, firstMutation, mutations,
        expectedTupleRoot, resultingTupleRoot, expectedScalarRoot, resultingScalarRoot,
        expectedNextPage, resultingNextPage, expectedGeneration, resultingGeneration,
        expectedHeapVersion, resultingHeapVersion, expectedRegistryState, resultingRegistryState,
        expectedPrivateOwner, resultingPrivateOwner, expectedCleanupCursor, resultingCleanupCursor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    columns.append(count, owner, keyId, descriptor, firstMutation, mutations,
        expectedTupleRoot, resultingTupleRoot, expectedScalarRoot, resultingScalarRoot,
        expectedNextPage, resultingNextPage, expectedGeneration, resultingGeneration,
        expectedHeapVersion, resultingHeapVersion, expectedRegistryState, resultingRegistryState,
        expectedPrivateOwner, resultingPrivateOwner, expectedCleanupCursor, resultingCleanupCursor);
    coveredMutations += mutations;
    count++;
    return StatusCode.OK;
  }

  void reset() { count = 0; coveredMutations = 0; }
  long accountedBytes() { return columns.allocatedBytes(); }
  long accountedBytesForReservation(int additional) {
    if (additional < 0 || additional > columns.capacity() - count) return -1;
    long reserved = columns.accountedBytesForCapacity(count + additional);
    return reserved < 0 ? -1 : Math.max(accountedBytes(), reserved);
  }
  boolean complete(int mutations) { return count > 0 && coveredMutations == mutations; }
  void release() { columns.release(); count = coveredMutations = 0; }
}
