package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeLookupResult;
import io.riverdb.storage.btree.BTreePage;
import java.nio.ByteBuffer;

/** Owns B-tree head and capacity validation for indexed mutations. */
final class IndexedMutationValidator {
  private final IndexedPageSet pages;
  private final BTreeLookupResult lookup = new BTreeLookupResult();
  private int leafPageId;

  IndexedMutationValidator(IndexedPageSet pageSet) {
    pages = pageSet;
  }

  int leafPageId() {
    return leafPageId;
  }

  StatusCode validateNewAt(
      int candidateLeafPageId, int space, long key, int earlierEntries) {
    leafPageId = candidateLeafPageId;
    if (!pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID) || leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    return validateNewIn(
        pages.currentPayload(leafPageId), space, key, earlierEntries);
  }

  StatusCode validateNewIn(
      ByteBuffer leaf, int space, long key, int earlierEntries) {
    if (leaf == null) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = BTreePage.lookupLeaf(leaf, space, key, lookup);
    if (status.isOk()) {
      return StatusCode.CONFLICT;
    }
    if (status != StatusCode.CONFLICT) {
      return status;
    }
    return BTreePage.entryCount(leaf) + earlierEntries >= BTreePage.MAX_ENTRIES
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  StatusCode validateMutationAt(
      int candidateLeafPageId,
      int operation,
      int space,
      long key,
      long previousRowId,
      int earlierEntries,
      boolean previousDeleted) {
    if (operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0) {
      return validateNewAt(candidateLeafPageId, space, key, earlierEntries);
    }
    leafPageId = candidateLeafPageId;
    if (!pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID) || leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    return validateMutationIn(
        pages.currentPayload(leafPageId),
        operation,
        space,
        key,
        previousRowId,
        earlierEntries,
        previousDeleted);
  }

  StatusCode validateMutationIn(
      ByteBuffer leaf,
      int operation,
      int space,
      long key,
      long previousRowId,
      int earlierEntries,
      boolean previousDeleted) {
    if (operation == IndexedWalCodec.MUTATION_INSERT && previousRowId == 0) {
      return validateNewIn(leaf, space, key, earlierEntries);
    }
    if (leaf == null) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = BTreePage.lookupLeaf(leaf, space, key, lookup);
    boolean validHead = status.isOk()
        && lookup.rowId() == previousRowId
        && previousRowId > 0;
    validHead &= operation == IndexedWalCodec.MUTATION_INSERT
        ? previousDeleted : !previousDeleted;
    return validHead ? StatusCode.OK : StatusCode.CONFLICT;
  }

  StatusCode validateVacuumAt(
      int candidateLeafPageId, int space, long key, long rowId) {
    leafPageId = candidateLeafPageId;
    if (leafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = BTreePage.lookupLeaf(
        pages.currentPayload(leafPageId), space, key, lookup);
    return status.isOk() && lookup.rowId() == rowId
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
