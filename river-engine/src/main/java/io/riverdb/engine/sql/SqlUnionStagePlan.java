package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.sql.SqlQuery;

/** Bounded EXPLAIN topology for one UNION expression. */
final class SqlUnionStagePlan {
  private static final int MAXIMUM_STEPS = SqlQuery.MAXIMUM_QUERY_BLOCKS * 2 + 1;
  private static final long LEAF = PackedText.pack("leaf");
  private static final long LIMIT = PackedText.pack("limit");
  private static final long SORT = PackedText.pack("sort");
  private static final long UNION = PackedText.pack("union");
  private static final long UNION_ALL = PackedText.pack("unionall");

  private final long[] operators = new long[MAXIMUM_STEPS];
  private final long[] details = new long[MAXIMUM_STEPS];
  private final long[] rows = new long[MAXIMUM_STEPS];
  private int count;

  StatusCode prepare(SqlQuery query, long setRows, long outputRows) {
    count = 0;
    for (int node = 0; node < query.setNodeCount(); node++) {
      int kind = query.setNodeKind(node);
      long operator = kind == SqlQuery.SET_LEAF ? LEAF
          : kind == SqlQuery.SET_UNION_ALL ? UNION_ALL : UNION;
      long detail = kind == SqlQuery.SET_LEAF ? query.setLeafBlock(node) + 1 : 0;
      StatusCode status = append(operator, detail,
          node == query.setRootNode() ? setRows : -1);
      if (!status.isOk()) return status;
    }
    if (query.setOrderExpressionCount() > 0) {
      StatusCode status = append(SORT, query.setOrderExpressionCount(), setRows);
      if (!status.isOk()) return status;
    }
    return query.setRowLimit() == Long.MAX_VALUE
        ? StatusCode.OK : append(LIMIT, query.setRowLimit(), outputRows);
  }

  int count() { return count; }
  long operator(int step) { return operators[step]; }
  long detail(int step) { return details[step]; }
  long rows(int step) { return rows[step]; }

  private StatusCode append(long operator, long detail, long actualRows) {
    if (count >= operators.length) return StatusCode.RESOURCE_EXHAUSTED;
    operators[count] = operator;
    details[count] = detail;
    rows[count] = actualRows;
    count++;
    return StatusCode.OK;
  }
}
