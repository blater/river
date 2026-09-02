package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlQuery;

/** Reconciles every leaf into one bounded nullable UNION output schema. */
final class SqlUnionSchema {
  private final int[] descriptors = new int[SqlShapeLimits.MAX_RESULT_COLUMNS];
  private final boolean[] nullable = new boolean[SqlShapeLimits.MAX_RESULT_COLUMNS];
  private final boolean[] visited = new boolean[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlBlockSchema output;
  private final SqlBlockSchema leaf;

  SqlUnionSchema(SqlSessionShapeBudget budget) {
    output = new SqlBlockSchema(budget);
    leaf = new SqlBlockSchema(budget);
  }

  StatusCode prepare(SqlQuery query, SqlUnionLeafSource source) {
    reset();
    int firstBlock = firstLeaf(query);
    if (firstBlock < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = source.describe(firstBlock, leaf);
    if (!status.isOk()) return status;
    int columns = leaf.count();
    if (columns <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    output.set(columns);
    for (int column = 0; column < columns; column++) {
      descriptors[column] = leaf.descriptor(column);
      nullable[column] = leaf.nullable(column);
      output.setColumn(
          column, leaf.name(column), descriptors[column], nullable[column]);
    }
    visited[firstBlock] = true;
    for (int node = 0; node < query.setNodeCount(); node++) {
      if (query.setNodeKind(node) != SqlQuery.SET_LEAF) continue;
      int block = query.setLeafBlock(node);
      if (block < 0 || block >= visited.length || visited[block]) continue;
      visited[block] = true;
      status = source.describe(block, leaf);
      if (status.isOk()) status = merge(leaf, columns);
      if (!status.isOk()) return status;
    }
    for (int column = 0; column < columns; column++) {
      output.setColumn(
          column, output.name(column), descriptors[column], nullable[column]);
    }
    return output.status();
  }

  SqlBlockSchema output() { return output; }

  private StatusCode merge(SqlBlockSchema source, int columns) {
    if (source == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!source.status().isOk()) return source.status();
    if (source.count() != columns) return StatusCode.DATATYPE_MISMATCH;
    for (int column = 0; column < columns; column++) {
      int common = SqlUnionTypeResolver.common(descriptors[column], source.descriptor(column));
      if (common == 0) return StatusCode.DATATYPE_MISMATCH;
      descriptors[column] = common;
      nullable[column] |= source.nullable(column);
    }
    return StatusCode.OK;
  }

  private void reset() {
    output.reset();
    for (int column = 0; column < descriptors.length; column++) {
      descriptors[column] = 0;
      nullable[column] = false;
    }
    for (int block = 0; block < visited.length; block++) visited[block] = false;
  }

  private static int firstLeaf(SqlQuery query) {
    int node = query.setRootNode();
    for (int depth = 0; depth < query.setNodeCount(); depth++) {
      if (query.setNodeKind(node) == SqlQuery.SET_LEAF) return query.setLeafBlock(node);
      node = query.setLeftNode(node);
    }
    return -1;
  }
}
