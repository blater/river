package io.riverdb.engine.table;

import io.riverdb.format.catalog.CatalogKeyspace;

/** Stateless validation of one relational registry transition. */
final class IndexedRelationalSuboperationTransitions {
  private IndexedRelationalSuboperationTransitions() {}

  static boolean validTuple(
      long keyId, int descriptor, int expectedRoot, int resultingRoot,
      long expectedGeneration, long resultingGeneration,
      int expectedState, int resultingState,
      long expectedPrivateOwner, long resultingPrivateOwner) {
    if (descriptor < 0) {
      return keyId == 0 && expectedRoot == 0 && resultingRoot == 0
          && expectedGeneration == 0 && resultingGeneration == 0
          && expectedState == IndexedRelationalSuboperations.REGISTRY_ABSENT
          && resultingState == IndexedRelationalSuboperations.REGISTRY_ABSENT
          && expectedPrivateOwner == 0 && resultingPrivateOwner == 0;
    }
    return validIdentity(keyId, expectedRoot, resultingRoot, expectedState, resultingState)
        && expectedGeneration >= 0 && expectedGeneration < Long.MAX_VALUE
        && resultingGeneration == expectedGeneration + 1
        && validRecord(expectedState, expectedRoot, expectedGeneration, expectedPrivateOwner, true)
        && validRecord(resultingState, resultingRoot, resultingGeneration, resultingPrivateOwner, false)
        && validStateChange(expectedState, resultingState)
        && validLifecycleRoots(expectedState, resultingState, expectedRoot, resultingRoot);
  }

  static boolean validCleanup(
      int descriptor, int expectedRoot, int resultingRoot,
      int expectedNextPage, int resultingNextPage,
      int expectedState, int resultingState,
      int expectedCursor, int resultingCursor) {
    if (descriptor < 0) return expectedCursor == 0 && resultingCursor == 0;
    boolean expectedValid = expectedState == IndexedRelationalSuboperations.REGISTRY_DROPPING
        && expectedRoot == 0
            ? expectedCursor >= 4 && expectedCursor <= expectedNextPage
            : expectedCursor == 0;
    if (!expectedValid) return false;
    if (resultingState != IndexedRelationalSuboperations.REGISTRY_DROPPING || resultingRoot != 0) {
      return resultingCursor == 0
          && (resultingState != IndexedRelationalSuboperations.REGISTRY_ABSENT
              || expectedState != IndexedRelationalSuboperations.REGISTRY_DROPPING
              || expectedCursor <= expectedNextPage);
    }
    if (expectedState != IndexedRelationalSuboperations.REGISTRY_DROPPING || expectedRoot != 0) {
      return resultingCursor == 4;
    }
    return resultingCursor > expectedCursor && resultingCursor <= resultingNextPage;
  }

  private static boolean validIdentity(
      long keyId, int expectedRoot, int resultingRoot, int expectedState, int resultingState) {
    return CatalogKeyspace.validKeyId(keyId) && expectedRoot >= 0 && resultingRoot >= 0
        && (expectedRoot != 0 || resultingRoot != 0
            || expectedState == IndexedRelationalSuboperations.REGISTRY_ABSENT
                && resultingState == IndexedRelationalSuboperations.REGISTRY_BUILDING
            || expectedState == IndexedRelationalSuboperations.REGISTRY_BUILDING
                && resultingState == IndexedRelationalSuboperations.REGISTRY_ABSENT
            || expectedState == IndexedRelationalSuboperations.REGISTRY_DROPPING);
  }

  private static boolean validRecord(
      int state, int root, long generation, long privateOwner, boolean expected) {
    return switch (state) {
      case IndexedRelationalSuboperations.REGISTRY_ABSENT ->
          root == 0 && generation >= 0 && privateOwner == 0 && (expected || generation > 0);
      case IndexedRelationalSuboperations.REGISTRY_BUILDING,
          IndexedRelationalSuboperations.REGISTRY_DROPPING -> generation > 0 && privateOwner > 0;
      case IndexedRelationalSuboperations.REGISTRY_READY ->
          root > 0 && generation > 0 && privateOwner == 0;
      default -> false;
    };
  }

  private static boolean validStateChange(int expected, int resulting) {
    return switch (expected) {
      case IndexedRelationalSuboperations.REGISTRY_ABSENT ->
          resulting == IndexedRelationalSuboperations.REGISTRY_BUILDING;
      case IndexedRelationalSuboperations.REGISTRY_BUILDING ->
          resulting == IndexedRelationalSuboperations.REGISTRY_ABSENT
              || resulting == IndexedRelationalSuboperations.REGISTRY_BUILDING
              || resulting == IndexedRelationalSuboperations.REGISTRY_READY
              || resulting == IndexedRelationalSuboperations.REGISTRY_DROPPING;
      case IndexedRelationalSuboperations.REGISTRY_READY ->
          resulting == IndexedRelationalSuboperations.REGISTRY_READY
              || resulting == IndexedRelationalSuboperations.REGISTRY_DROPPING;
      case IndexedRelationalSuboperations.REGISTRY_DROPPING ->
          resulting == IndexedRelationalSuboperations.REGISTRY_DROPPING
              || resulting == IndexedRelationalSuboperations.REGISTRY_ABSENT;
      default -> false;
    };
  }

  private static boolean validLifecycleRoots(
      int expectedState, int resultingState, int expectedRoot, int resultingRoot) {
    if (resultingState == IndexedRelationalSuboperations.REGISTRY_ABSENT) {
      return expectedRoot == 0 && resultingRoot == 0;
    }
    if (resultingState != IndexedRelationalSuboperations.REGISTRY_DROPPING) return true;
    if (resultingRoot != 0) {
      return expectedState != IndexedRelationalSuboperations.REGISTRY_DROPPING
          && expectedRoot == resultingRoot;
    }
    return expectedState == IndexedRelationalSuboperations.REGISTRY_BUILDING
        || expectedState == IndexedRelationalSuboperations.REGISTRY_READY
            ? expectedRoot > 0
            : expectedState == IndexedRelationalSuboperations.REGISTRY_DROPPING;
  }
}
