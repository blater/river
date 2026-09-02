package io.riverdb.sql;

/** Bounded parser handoff from synthetic subquery spans to canonical leaves. */
final class SqlSubqueryLeafRegistry {
  private final int[] offsets = new int[SqlQuery.MAXIMUM_EDGES];
  private final int[] kinds = new int[SqlQuery.MAXIMUM_EDGES];
  private final int[] edges = new int[SqlQuery.MAXIMUM_EDGES];
  private final int[] leaves = new int[SqlQuery.MAXIMUM_EDGES];
  private int count;

  void begin(int[] sourceOffsets, int[] sourceKinds, int[] sourceEdges, int sourceCount) {
    clear();
    count = sourceCount;
    for (int index = 0; index < count; index++) {
      offsets[index] = sourceOffsets[index];
      kinds[index] = sourceKinds[index];
      edges[index] = sourceEdges[index];
      leaves[index] = -1;
    }
  }

  int find(int offset, int kind) {
    for (int index = 0; index < count; index++) {
      if (offsets[index] == offset && kinds[index] == kind) return index;
    }
    return -1;
  }

  int edge(int index) { return edges[index]; }
  int leaf(int index) { return index >= 0 && index < count ? leaves[index] : -1; }
  void setLeaf(int index, int leaf) { leaves[index] = leaf; }

  void clear() {
    for (int index = 0; index < count; index++) {
      offsets[index] = 0;
      kinds[index] = 0;
      edges[index] = 0;
      leaves[index] = -1;
    }
    count = 0;
  }
}
