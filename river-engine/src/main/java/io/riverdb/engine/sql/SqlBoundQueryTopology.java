package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlQuery;

/** Actual-count retained storage for one bound query's block topology. */
final class SqlBoundQueryTopology {
  private static final long CHARGED_BYTES_PER_EDGE = 16;
  private final SqlSessionShapeBudget budget;
  private byte[] kinds = new byte[0];
  private byte[] parents = new byte[0];
  private byte[] leaves = new byte[0];
  private byte[] children = new byte[0];
  private boolean[] negated = new boolean[0];
  private SqlComparison[] comparisons = new SqlComparison[0];
  private byte[] depths = new byte[0];

  SqlBoundQueryTopology(SqlSessionShapeBudget shapeBudget) { budget = shapeBudget; }

  StatusCode capture(SqlQuery query, int blockCount) {
    int edgeCount = query.edgeCount();
    StatusCode status = reserve(blockCount, edgeCount);
    if (!status.isOk()) return status;
    for (int block = 0; block < blockCount; block++) depths[block] = 0;
    for (int edge = 0; edge < edgeCount; edge++) {
      int parent = query.edgeParent(edge);
      int leaf = query.edgeLeaf(edge);
      kinds[edge] = (byte) query.edgeKind(edge);
      parents[edge] = (byte) parent;
      leaves[edge] = (byte) leaf;
      children[edge] = (byte) query.edgeChild(edge);
      negated[edge] = query.block(parent).wherePredicates().leafNegated(leaf);
      comparisons[edge] = query.block(parent).wherePredicates().comparison(leaf);
    }
    return StatusCode.OK;
  }

  void reset(int edgeCount) {
    for (int edge = 0; edge < edgeCount; edge++) comparisons[edge] = null;
  }

  void depth(int block, int value) { depths[block] = (byte) value; }
  int depth(int block) { return Byte.toUnsignedInt(depths[block]); }
  int kind(int edge) { return Byte.toUnsignedInt(kinds[edge]); }
  int parent(int edge) { return Byte.toUnsignedInt(parents[edge]); }
  int leaf(int edge) { return Byte.toUnsignedInt(leaves[edge]); }
  int child(int edge) { return Byte.toUnsignedInt(children[edge]); }
  boolean negated(int edge) { return negated[edge]; }
  SqlComparison comparison(int edge) { return comparisons[edge]; }

  private StatusCode reserve(int blockCount, int edgeCount) {
    int blockCapacity = BoundedArrayGrowth.capacity(
        depths.length, blockCount, SqlQuery.MAXIMUM_QUERY_BLOCKS, 1);
    int edgeCapacity = BoundedArrayGrowth.capacity(
        kinds.length, edgeCount, SqlQuery.MAXIMUM_EDGES, 1);
    if (blockCapacity < 0 || edgeCapacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (blockCapacity == depths.length && edgeCapacity == kinds.length) return StatusCode.OK;
    long charged = blockCapacity - depths.length
        + (long) (edgeCapacity - kinds.length) * CHARGED_BYTES_PER_EDGE;
    StatusCode status = budget.reserve(charged);
    if (!status.isOk()) return status;
    try {
      grow(blockCapacity, edgeCapacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private void grow(int blockCapacity, int edgeCapacity) {
    byte[] nextKinds = java.util.Arrays.copyOf(kinds, edgeCapacity);
    byte[] nextParents = java.util.Arrays.copyOf(parents, edgeCapacity);
    byte[] nextLeaves = java.util.Arrays.copyOf(leaves, edgeCapacity);
    byte[] nextChildren = java.util.Arrays.copyOf(children, edgeCapacity);
    boolean[] nextNegated = java.util.Arrays.copyOf(negated, edgeCapacity);
    SqlComparison[] nextComparisons = java.util.Arrays.copyOf(comparisons, edgeCapacity);
    byte[] nextDepths = java.util.Arrays.copyOf(depths, blockCapacity);
    kinds = nextKinds;
    parents = nextParents;
    leaves = nextLeaves;
    children = nextChildren;
    negated = nextNegated;
    comparisons = nextComparisons;
    depths = nextDepths;
  }
}
