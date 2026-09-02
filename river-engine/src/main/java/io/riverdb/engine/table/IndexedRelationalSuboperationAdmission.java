package io.riverdb.engine.table;

import io.riverdb.format.catalog.CatalogKeyspace;

/** Cross-entry validation for a candidate relational recovery suboperation. */
final class IndexedRelationalSuboperationAdmission {
  private IndexedRelationalSuboperationAdmission() {}

  static boolean valid(
      IndexedRelationalSuboperationColumns columns, int count, int coveredMutations,
      long owner, long keyId, int descriptor, int firstMutation, int mutations,
      int expectedTupleRoot, int resultingTupleRoot,
      int expectedScalarRoot, int resultingScalarRoot,
      int expectedNextPage, int resultingNextPage,
      long expectedGeneration, long resultingGeneration,
      long expectedHeapVersion, long resultingHeapVersion,
      int expectedRegistryState, int resultingRegistryState,
      long expectedPrivateOwner, long resultingPrivateOwner,
      int expectedCleanupCursor, int resultingCleanupCursor) {
    return validAdmission(columns, count, coveredMutations, owner, descriptor,
            firstMutation, mutations, expectedScalarRoot, resultingScalarRoot,
            expectedNextPage, resultingNextPage, expectedRegistryState, resultingRegistryState)
        && IndexedRelationalSuboperationTransitions.validTuple(
            keyId, descriptor, expectedTupleRoot, resultingTupleRoot,
            expectedGeneration, resultingGeneration, expectedRegistryState,
            resultingRegistryState, expectedPrivateOwner, resultingPrivateOwner)
        && IndexedRelationalSuboperationTransitions.validCleanup(
            descriptor, expectedTupleRoot, resultingTupleRoot,
            expectedNextPage, resultingNextPage, expectedRegistryState,
            resultingRegistryState, expectedCleanupCursor, resultingCleanupCursor)
        && validHeap(descriptor, mutations, expectedHeapVersion, resultingHeapVersion)
        && chainsPerKey(columns, count, keyId, descriptor, expectedTupleRoot,
            expectedGeneration, expectedRegistryState, expectedPrivateOwner)
        && (count == 0 || columns.getLong(
            IndexedRelationalSuboperationColumns.RESULTING_HEAP_VERSION, count - 1)
            == expectedHeapVersion);
  }

  private static boolean validAdmission(
      IndexedRelationalSuboperationColumns columns, int count, int coveredMutations,
      long owner, int descriptor, int firstMutation, int mutations,
      int expectedScalarRoot, int resultingScalarRoot,
      int expectedNextPage, int resultingNextPage,
      int expectedRegistryState, int resultingRegistryState) {
    boolean ownerValid = descriptor == IndexedRelationalMutationBuffer.SCALAR_SUBOPERATION
        ? owner == 0 : CatalogKeyspace.validObjectHead(owner);
    boolean registryValid = descriptor < 0 || expectedRegistryState == resultingRegistryState
        || mutations == 0
        || expectedRegistryState == IndexedRelationalSuboperations.REGISTRY_BUILDING
            && resultingRegistryState == IndexedRelationalSuboperations.REGISTRY_READY;
    return ownerValid && count < columns.capacity() && count < columns.allocatedCapacity()
        && descriptor >= IndexedRelationalMutationBuffer.SCALAR_SUBOPERATION
        && firstMutation == coveredMutations && mutations >= 0 && registryValid
        && expectedScalarRoot > 0 && resultingScalarRoot > 0
        && expectedNextPage > 0 && resultingNextPage >= expectedNextPage
        && (count == 0
            || columns.getInt(IndexedRelationalSuboperationColumns.RESULTING_SCALAR_ROOT, count - 1)
                == expectedScalarRoot
            && columns.getInt(IndexedRelationalSuboperationColumns.RESULTING_NEXT_PAGE, count - 1)
                == expectedNextPage);
  }

  private static boolean validHeap(
      int descriptor, int mutations, long expected, long resulting) {
    int appendedVersions = descriptor < 0 ? mutations : 1;
    return appendedVersions > 0 && expected >= 0
        && expected <= Long.MAX_VALUE - appendedVersions
        && resulting == expected + appendedVersions;
  }

  private static boolean chainsPerKey(
      IndexedRelationalSuboperationColumns columns, int count,
      long keyId, int descriptor, int root, long generation, int state, long privateOwner) {
    if (descriptor < 0) return true;
    for (int index = count - 1; index >= 0; index--) {
      if (columns.getLong(IndexedRelationalSuboperationColumns.KEY_ID, index) == keyId) {
        return columns.getInt(IndexedRelationalSuboperationColumns.RESULTING_TUPLE_ROOT, index) == root
            && columns.getLong(IndexedRelationalSuboperationColumns.RESULTING_GENERATION, index)
                == generation
            && columns.getInt(IndexedRelationalSuboperationColumns.RESULTING_REGISTRY_STATE, index)
                == state
            && columns.getLong(IndexedRelationalSuboperationColumns.RESULTING_PRIVATE_OWNER, index)
                == privateOwner;
      }
    }
    return true;
  }
}
