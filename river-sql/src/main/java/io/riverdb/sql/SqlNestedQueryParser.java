package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses a bounded graph of canonical predicate-subquery leaves. */
final class SqlNestedQueryParser {
  private final SqlParser statements;
  private final SqlNestedSubquerySource source = new SqlNestedSubquerySource();
  private final int[] offsets = new int[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private final int[] kinds = new int[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private final int[] edges = new int[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];

  SqlNestedQueryParser(SqlParser parser) { statements = parser; }

  boolean hasPredicateSubquery(CharSequence sql, int start, int end) {
    return source.contains(sql, start, end);
  }

  StatusCode parse(
      CharSequence sql, int start, int end, SqlQuery query, SqlCommand result) {
    StatusCode status = parseAppend(sql, start, end, query);
    return status.isOk() ? query.compileNestedGraph(result) : status;
  }

  StatusCode parseAppend(CharSequence sql, int start, int end, SqlQuery query) {
    query.beginNestedGraph(query.blockCount());
    StatusCode status = parseBlock(sql, start, end, query, -1);
    return status.isOk() ? query.validateNestedGraph() : status;
  }

  private StatusCode parseBlock(
      CharSequence sql, int start, int end, SqlQuery query, int parentEdge) {
    int block = query.blockCount();
    SqlCommand command = query.nextBlock();
    if (command == null) return StatusCode.QUERY_TOO_COMPLEX;
    if (parentEdge >= 0) query.setSubqueryEdgeChild(parentEdge, block);
    StatusCode status = source.scan(sql, start, end, query, block, offsets);
    if (parentEdge >= 0 && status == StatusCode.INVALID_EXTERNAL_INPUT) {
      return statements.parseQueryBlock(source, command);
    }
    if (!status.isOk()) return status;
    int first = source.firstEdge();
    int count = source.count();
    for (int index = 0; index < count; index++) {
      kinds[index] = source.kindAt(index);
      edges[index] = first + index;
    }
    status = statements.parseSubqueryBlock(source, offsets, kinds, edges, count, command);
    for (int index = 0; status.isOk() && index < count; index++) {
      int leaf = statements.subqueryLeaf(index);
      if (leaf < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      query.setSubqueryEdgeLeaf(first + index, leaf);
    }
    for (int index = 0; status.isOk() && index < count; index++) {
      int edge = first + index;
      status = parseBlock(sql, source.childStart(edge), source.childEnd(edge), query, edge);
    }
    return status;
  }
}
