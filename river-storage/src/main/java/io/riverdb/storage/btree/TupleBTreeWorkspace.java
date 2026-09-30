package io.riverdb.storage.btree;

import io.riverdb.format.btree.TupleBTreeInternalEntry;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleBTreePageHeader;
import io.riverdb.format.btree.TupleBTreePageMutationCapability;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import io.riverdb.format.btree.TupleKeyPrefix;

/** Reusable decoded carriers for variable-key page operations. */
public final class TupleBTreeWorkspace {
  final TupleBTreePageHeader header = new TupleBTreePageHeader();
  final TupleBTreePageValidationProof validation = new TupleBTreePageValidationProof();
  final TupleBTreePageMutationCapability mutation = new TupleBTreePageMutationCapability();
  final TupleBTreeLeafEntry leaf = new TupleBTreeLeafEntry();
  final TupleBTreeInternalEntry internal = new TupleBTreeInternalEntry();
  final TupleKeyPrefix prefix = new TupleKeyPrefix();
  private int removedOverflowPageId;
  private long removedOverflowGeneration;

  void clearRemovedValue() {
    removedOverflowPageId = 0;
    removedOverflowGeneration = 0;
  }

  void removedValue(int pageId, long generation) {
    removedOverflowPageId = pageId;
    removedOverflowGeneration = generation;
  }

  int removedOverflowPageId() { return removedOverflowPageId; }
  long removedOverflowGeneration() { return removedOverflowGeneration; }
}
