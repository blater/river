package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlQuery;

/** Executes UNION topology without allocating while visiting rows. */
final class SqlUnionNodeExecution {
  private final SqlUnionLeafExecution leaves;
  private final SqlUnionDistinctExecution distinct;
  private SqlUnionLeafSource source;

  SqlUnionNodeExecution(SqlSessionShapeBudget budget) {
    leaves = new SqlUnionLeafExecution(budget);
    distinct = new SqlUnionDistinctExecution(
        this, SqlShapeLimits.MAX_RESULT_COLUMNS, SqlQuery.MAXIMUM_QUERY_BLOCKS, budget);
  }

  StatusCode prepare(SqlUnionLeafSource leafSource, SqlBlockSchema schema) {
    source = leafSource;
    distinct.prepare(schema);
    return leaves.prepare(source, schema);
  }

  StatusCode append(
      SqlQuery query, int node, SqlBlockRowStore output, int depth) {
    int kind = query.setNodeKind(node);
    if (kind == SqlQuery.SET_LEAF) {
      int block = query.setLeafBlock(node);
      return leaves.append(block, query.block(block), output);
    }
    if (kind == SqlQuery.SET_UNION_DISTINCT) {
      return distinct.append(query, node, output, depth);
    }
    if (kind != SqlQuery.SET_UNION_ALL) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = append(query, query.setLeftNode(node), output, depth);
    return status.isOk()
        ? append(query, query.setRightNode(node), output, depth) : status;
  }

  StatusCode close() {
    StatusCode status = leaves.close();
    StatusCode distinctStatus = distinct.close();
    source = null;
    return status.isOk() ? distinctStatus : status;
  }
}
