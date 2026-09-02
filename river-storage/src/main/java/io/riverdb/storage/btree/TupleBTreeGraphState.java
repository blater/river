package io.riverdb.storage.btree;

/** Reusable counters and leaf-chain evidence for one whole-tree validation. */
final class TupleBTreeGraphState {
  int pages;
  int leaves;
  int height;
  int expectedLeaf;
  int previousLeaf;
  int leafDepth;
  long entries;

  void reset() {
    pages = 0;
    leaves = 0;
    height = 0;
    expectedLeaf = 0;
    previousLeaf = 0;
    leafDepth = -1;
    entries = 0;
  }
}
