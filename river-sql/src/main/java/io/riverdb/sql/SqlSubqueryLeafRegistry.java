package io.riverdb.sql;

/** Bounded parser handoff from synthetic subquery spans to canonical leaves. */
final class SqlSubqueryLeafRegistry {
  private final int[] offsets = new int[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private final byte[] kinds = new byte[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private final byte[] edges = new byte[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private final byte[] leaves = new byte[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private int count;

  void begin(int[] sourceOffsets, int[] sourceKinds, int[] sourceEdges, int sourceCount) {
    clear();
    count = sourceCount;
    for (int index = 0; index < count; index++) {
      offsets[index] = sourceOffsets[index];
      kinds[index] = (byte) sourceKinds[index];
      edges[index] = (byte) sourceEdges[index];
      leaves[index] = -1;
    }
  }

  int find(int offset, int kind) {
    for (int index = 0; index < count; index++) {
      if (offsets[index] == offset && Byte.toUnsignedInt(kinds[index]) == kind) return index;
    }
    return -1;
  }

  int edge(int index) { return Byte.toUnsignedInt(edges[index]); }
  int leaf(int index) { return index >= 0 && index < count ? leaves[index] : -1; }
  void setLeaf(int index, int leaf) { leaves[index] = (byte) leaf; }

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
