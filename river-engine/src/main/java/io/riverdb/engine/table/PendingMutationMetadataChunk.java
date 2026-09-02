package io.riverdb.engine.table;

/** One fixed-size primitive metadata slab; its owner allocates slabs only during reserve. */
final class PendingMutationMetadataChunk {
  final int[] operations;
  final int[] rowLengths;
  final int[] rowOffsets;
  final long[] keys;
  final long[] spaces;
  final long[] previousRowIds;
  final boolean[] retained;

  PendingMutationMetadataChunk(int entries) {
    operations = new int[entries];
    rowLengths = new int[entries];
    rowOffsets = new int[entries];
    keys = new long[entries];
    spaces = new long[entries];
    previousRowIds = new long[entries];
    retained = new boolean[entries];
  }
}
