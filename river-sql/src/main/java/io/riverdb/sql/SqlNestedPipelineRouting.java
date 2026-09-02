package io.riverdb.sql;

/** Selects the general block pipeline for nested shapes a scalar frame cannot execute. */
final class SqlNestedPipelineRouting {
  private SqlNestedPipelineRouting() {}

  static boolean required(SqlSubqueryGraph graph, SqlCommand[] blocks, int blockCount) {
    if (graph.maximumDepth() > 2) return true;
    for (int edge = 0; edge < graph.count(); edge++) {
      int child = graph.child(edge);
      if (child >= 0 && child < blockCount
          && blocks[child].type() == SqlCommandType.JOIN_SCAN) return true;
    }
    return false;
  }
}
