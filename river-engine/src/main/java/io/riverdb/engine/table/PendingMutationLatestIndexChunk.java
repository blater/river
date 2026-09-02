package io.riverdb.engine.table;

/** Lazily allocated primitive storage for a consecutive range of latest-index AVL nodes. */
final class PendingMutationLatestIndexChunk {
  final long[] spaces;
  final long[] keys;
  final int[] latest;
  final int[] left;
  final int[] right;
  final int[] parents;
  final int[] heights;

  PendingMutationLatestIndexChunk(int entries) {
    spaces = new long[entries];
    keys = new long[entries];
    latest = new int[entries];
    left = new int[entries];
    right = new int[entries];
    parents = new int[entries];
    heights = new int[entries];
  }
}
