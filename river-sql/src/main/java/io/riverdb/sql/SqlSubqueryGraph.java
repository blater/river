package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Bounded ownership and shape validation for canonical predicate-subquery edges. */
final class SqlSubqueryGraph {
  private final byte[] kinds = new byte[SqlQuery.MAXIMUM_EDGES];
  private final byte[] parents = new byte[SqlQuery.MAXIMUM_EDGES];
  private final byte[] leaves = new byte[SqlQuery.MAXIMUM_EDGES];
  private final byte[] children = new byte[SqlQuery.MAXIMUM_EDGES];
  private final byte[] blockParents = new byte[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final byte[] depths = new byte[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final boolean[] reached = new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private int count;
  private int root = -1;
  private int maximumDepth;

  void reset() {
    for (int edge = 0; edge < count; edge++) {
      kinds[edge] = 0;
      parents[edge] = 0;
      leaves[edge] = 0;
      children[edge] = 0;
    }
    for (int block = root; block >= 0 && block < reached.length; block++) {
      blockParents[block] = 0;
      depths[block] = 0;
      reached[block] = false;
    }
    count = 0;
    root = -1;
    maximumDepth = 0;
  }

  void begin(int rootBlock) {
    root = rootBlock;
    depths[rootBlock] = 1;
    maximumDepth = 1;
  }

  int append(int parent, int kind, int blockCount) {
    if (count >= kinds.length || root < 0 || parent < root || parent >= blockCount
        || kind < SqlQuery.SUBQUERY_SCALAR || kind > SqlQuery.SUBQUERY_MEMBERSHIP) {
      return -1;
    }
    int edge = count++;
    kinds[edge] = (byte) kind;
    parents[edge] = (byte) parent;
    leaves[edge] = -1;
    children[edge] = -1;
    return edge;
  }

  void setLeaf(int edge, int leaf) {
    if (edge >= 0 && edge < count) leaves[edge] = (byte) leaf;
  }

  void setChild(int edge, int child, int blockCount) {
    if (edge < 0 || edge >= count || child < 0 || child >= blockCount) return;
    children[edge] = (byte) child;
    int parent = Byte.toUnsignedInt(parents[edge]);
    blockParents[child] = (byte) parent;
    depths[child] = (byte) (Byte.toUnsignedInt(depths[parent]) + 1);
    maximumDepth = Math.max(maximumDepth, Byte.toUnsignedInt(depths[child]));
  }

  StatusCode validate(SqlQuery query) {
    if (root < 0 || root != query.sourceBlockCount() - 1 || count == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int block = root; block < query.blockCount(); block++) reached[block] = false;
    reached[root] = true;
    for (int edge = 0; edge < count; edge++) {
      int parent = Byte.toUnsignedInt(parents[edge]);
      int child = children[edge];
      int leaf = leaves[edge];
      if (parent < root || parent >= query.blockCount() || child <= parent
          || child >= query.blockCount() || leaf < 0 || reached[child]) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      SqlBooleanPredicateProgram program = query.block(parent).wherePredicates();
      if (leaf >= program.leafCount() || program.leafTest(leaf) != expected(edge)
          || program.subqueryEdge(leaf) != edge) return StatusCode.INVALID_EXTERNAL_INPUT;
      reached[child] = true;
    }
    for (int block = root + 1; block < query.blockCount(); block++) {
      if (!reached[block]) return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  int count() { return count; }
  int root() { return root; }
  int maximumDepth() { return maximumDepth; }
  int kind(int edge) { return valid(edge) ? Byte.toUnsignedInt(kinds[edge]) : 0; }
  int parent(int edge) { return valid(edge) ? Byte.toUnsignedInt(parents[edge]) : -1; }
  int leaf(int edge) { return valid(edge) ? leaves[edge] : -1; }
  int child(int edge) { return valid(edge) ? children[edge] : -1; }
  int blockParent(int block) {
    return block > root && block < blockParents.length ? blockParents[block] : -1;
  }
  int blockDepth(int block) {
    return block >= root && block < depths.length ? Byte.toUnsignedInt(depths[block]) : 0;
  }

  private int expected(int edge) {
    return switch (Byte.toUnsignedInt(kinds[edge])) {
      case SqlQuery.SUBQUERY_SCALAR -> SqlBooleanPredicateProgram.TEST_SUBQUERY_COMPARISON;
      case SqlQuery.SUBQUERY_EXISTS -> SqlBooleanPredicateProgram.TEST_SUBQUERY_EXISTS;
      case SqlQuery.SUBQUERY_MEMBERSHIP -> SqlBooleanPredicateProgram.TEST_SUBQUERY_MEMBERSHIP;
      default -> 0;
    };
  }

  private boolean valid(int edge) { return edge >= 0 && edge < count; }
}
