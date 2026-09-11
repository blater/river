package io.riverdb.sql;

/** Selects the block pipeline for nested topology and cardinality-preserving shapes. */
final class SqlQueryPipelineRouting {
  private SqlQueryPipelineRouting() { }

  static boolean requiresCardinalityPipeline(
      SqlSubqueryGraph graph, SqlCommand[] blocks, int sourceBlockCount) {
    if (graph.count() > 0 && sourceBlockCount > 1) return true;
    for (int index = 0; index < sourceBlockCount; index++) {
      if (index > 0
          && (blocks[index].isOrdered()
              || blocks[index].rowLimit() != Long.MAX_VALUE)) return true;
      SqlCommandType type = blocks[index].type();
      if (type == SqlCommandType.JOIN_SCAN
          || type == SqlCommandType.DISTINCT_SCAN
          || type == SqlCommandType.COUNT
          || type == SqlCommandType.COUNT_VALUE
          || type == SqlCommandType.COUNT_DISTINCT
          || type == SqlCommandType.SUM
          || type == SqlCommandType.AVG
          || type == SqlCommandType.MIN
          || type == SqlCommandType.MAX
          || type == SqlCommandType.GROUP_COUNT
          || type == SqlCommandType.GROUP_COUNT_VALUE
          || type == SqlCommandType.GROUP_COUNT_DISTINCT
          || type == SqlCommandType.GROUP_SUM
          || type == SqlCommandType.GROUP_AVG
          || type == SqlCommandType.GROUP_MIN
          || type == SqlCommandType.GROUP_MAX) return true;
    }
    return false;
  }

  static boolean requiresNestedPipeline(
      SqlSubqueryGraph graph, SqlCommand[] blocks, int blockCount) {
    if (graph.maximumDepth() > 2) return true;
    for (int edge = 0; edge < graph.count(); edge++) {
      int child = graph.child(edge);
      if (child >= 0 && child < blockCount
          && blocks[child].type() == SqlCommandType.JOIN_SCAN) return true;
    }
    return false;
  }
}
