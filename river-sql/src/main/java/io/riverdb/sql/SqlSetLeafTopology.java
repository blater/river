package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Bounded predicate-subquery topology snapshots owned by UNION leaves. */
final class SqlSetLeafTopology {
  private final byte[] blockCounts = new byte[SqlSetExpression.MAXIMUM_NODES];
  private final byte[] rootBlocks = new byte[SqlSetExpression.MAXIMUM_NODES];
  private final byte[] edgeCounts = new byte[SqlSetExpression.MAXIMUM_NODES];
  private final byte[] edgeStarts = new byte[SqlSetExpression.MAXIMUM_NODES];
  private final byte[] kinds = new byte[SqlQuery.MAXIMUM_EDGES];
  private final short[] leaves = new short[SqlQuery.MAXIMUM_EDGES];
  private final byte[] parents = new byte[SqlQuery.MAXIMUM_EDGES];
  private final byte[] children = new byte[SqlQuery.MAXIMUM_EDGES];
  private int count;

  void append(int node, int rootBlock) {
    rootBlocks[node] = (byte) rootBlock;
    blockCounts[node] = 1;
    edgeCounts[node] = 0;
    edgeStarts[node] = (byte) count;
  }

  StatusCode capture(int node, int rootBlock, SqlSubqueryGraph graph) {
    if (count + graph.count() > kinds.length) return StatusCode.QUERY_TOO_COMPLEX;
    edgeStarts[node] = (byte) count;
    edgeCounts[node] = (byte) graph.count();
    blockCounts[node] = (byte) (graph.count() + 1);
    for (int edge = 0; edge < graph.count(); edge++) {
      kinds[count] = (byte) graph.kind(edge);
      leaves[count] = (short) graph.leaf(edge);
      parents[count] = (byte) (graph.parent(edge) - rootBlock);
      children[count] = (byte) (graph.child(edge) - rootBlock);
      count++;
    }
    return StatusCode.OK;
  }

  void reset(int nodes) {
    for (int node = 0; node < nodes; node++) {
      blockCounts[node] = 0;
      rootBlocks[node] = 0;
      edgeCounts[node] = 0;
      edgeStarts[node] = 0;
    }
    for (int edge = 0; edge < count; edge++) {
      kinds[edge] = 0;
      leaves[edge] = 0;
      parents[edge] = 0;
      children[edge] = 0;
    }
    count = 0;
  }

  int blockCount(int node) { return Byte.toUnsignedInt(blockCounts[node]); }
  int edgeCount(int node) { return Byte.toUnsignedInt(edgeCounts[node]); }
  int kind(int node, int edge) { return kinds[position(node, edge)]; }
  int leaf(int node, int edge) { return Short.toUnsignedInt(leaves[position(node, edge)]); }
  int parent(int node, int edge) { return Byte.toUnsignedInt(parents[position(node, edge)]); }
  int child(int node, int edge) { return Byte.toUnsignedInt(children[position(node, edge)]); }

  StatusCode copy(
      int rootBlock, SqlQuery source, SqlQuery destination, SqlCommand result) {
    int node = node(rootBlock);
    int blocks = node < 0 ? 0 : blockCount(node);
    if (blocks < 1 || rootBlock + blocks > source.blockCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    destination.reset();
    result.reset();
    StatusCode status = copyBlocks(node, rootBlock, blocks, source, destination);
    if (status.isOk()) status = copyEdges(node, destination);
    if (status.isOk() && destination.edgeCount() > 0) {
      status = destination.validateNestedGraph();
    }
    return status.isOk() ? result.copyBlockFrom(destination.block(0)) : status;
  }

  private StatusCode copyBlocks(
      int node, int root, int blocks, SqlQuery source, SqlQuery destination) {
    for (int offset = 0; offset < blocks; offset++) {
      if (offset == 1 && edgeCount(node) > 0) destination.beginNestedGraph(0);
      SqlCommand copy = destination.nextBlock();
      if (copy == null) return StatusCode.QUERY_TOO_COMPLEX;
      StatusCode status = copy.copyBlockFrom(source.block(root + offset));
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode copyEdges(int node, SqlQuery destination) {
    for (int index = 0; index < edgeCount(node); index++) {
      int edge = destination.addSubqueryEdge(parent(node, index), kind(node, index));
      if (edge < 0) return StatusCode.QUERY_TOO_COMPLEX;
      destination.setSubqueryEdgeLeaf(edge, leaf(node, index));
      destination.setSubqueryEdgeChild(edge, child(node, index));
    }
    return StatusCode.OK;
  }

  private int node(int rootBlock) {
    for (int node = 0; node < blockCounts.length; node++) {
      if (blockCounts[node] != 0
          && Byte.toUnsignedInt(rootBlocks[node]) == rootBlock) return node;
    }
    return -1;
  }

  private int position(int node, int edge) {
    return Byte.toUnsignedInt(edgeStarts[node]) + edge;
  }
}
