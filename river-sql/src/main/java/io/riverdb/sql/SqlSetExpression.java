package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Bounded reusable topology and root modifiers for one SQL set expression. */
final class SqlSetExpression {
  static final int MAXIMUM_NODES = SqlQuery.MAXIMUM_QUERY_BLOCKS * 2 - 1;

  private final byte[] kinds = new byte[MAXIMUM_NODES];
  private final int[] left = new int[MAXIMUM_NODES];
  private final int[] right = new int[MAXIMUM_NODES];
  private final int[] blocks = new int[MAXIMUM_NODES];
  private final SqlSetLeafTopology leafTopology = new SqlSetLeafTopology();
  private final SqlOrderByList order = new SqlOrderByList();
  private int count;
  private int root = -1;
  private long rowLimit = Long.MAX_VALUE;

  int appendLeaf(int block) {
    if (count >= MAXIMUM_NODES) return -1;
    int node = count++;
    kinds[node] = SqlQuery.SET_LEAF;
    left[node] = -1;
    right[node] = -1;
    blocks[node] = block;
    leafTopology.append(node, block);
    root = node;
    return node;
  }

  int appendUnion(int kind, int leftNode, int rightNode) {
    if (count >= MAXIMUM_NODES
        || kind != SqlQuery.SET_UNION_ALL && kind != SqlQuery.SET_UNION_DISTINCT
        || !valid(leftNode) || !valid(rightNode)) return -1;
    int node = count++;
    kinds[node] = (byte) kind;
    left[node] = leftNode;
    right[node] = rightNode;
    blocks[node] = -1;
    root = node;
    return node;
  }

  SqlIdentifier appendOrder() { return order.append(); }
  void orderDescending(int expression, boolean descending) {
    order.descending(expression, descending);
  }
  void rowLimit(long value) { rowLimit = value; }

  StatusCode captureLeaf(int node, SqlSubqueryGraph graph) {
    return !valid(node) || kinds[node] != SqlQuery.SET_LEAF || graph == null
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : leafTopology.capture(node, blocks[node], graph);
  }

  StatusCode publish(SqlCommand first, SqlCommand destination) {
    StatusCode status = destination.copyBlockFrom(first);
    if (!status.isOk()) return status;
    destination.orderBy.reset();
    if (!destination.orderBy.copyFrom(order)) return StatusCode.RESOURCE_EXHAUSTED;
    destination.descendingOrder = order.descending(0);
    destination.rowLimit = rowLimit;
    return StatusCode.OK;
  }

  void reset() {
    for (int node = 0; node < count; node++) {
      kinds[node] = 0;
      left[node] = 0;
      right[node] = 0;
      blocks[node] = 0;
    }
    leafTopology.reset(count);
    count = 0;
    root = -1;
    order.reset();
    rowLimit = Long.MAX_VALUE;
  }

  int count() { return count; }
  int root() { return root; }
  int kind(int node) { return valid(node) ? kinds[node] : 0; }
  int left(int node) { return valid(node) ? left[node] : -1; }
  int right(int node) { return valid(node) ? right[node] : -1; }
  int block(int node) {
    return valid(node) && kinds[node] == SqlQuery.SET_LEAF ? blocks[node] : -1;
  }
  StatusCode copyLeaf(
      int rootBlock, SqlQuery source, SqlQuery destination, SqlCommand result) {
    return leafTopology.copy(rootBlock, source, destination, result);
  }
  int orderCount() { return order.count(); }
  SqlIdentifier orderName(int expression) { return order.name(expression); }
  boolean orderDescending(int expression) { return order.descending(expression); }
  long rowLimit() { return rowLimit; }

  private boolean valid(int node) { return node >= 0 && node < count; }
}
