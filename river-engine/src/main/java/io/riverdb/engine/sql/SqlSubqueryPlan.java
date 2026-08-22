package io.riverdb.engine.sql;

import io.riverdb.base.text.PackedText;
import io.riverdb.sql.SqlQuery;

/** Bounded logical, physical, and runtime plan evidence for graph edges. */
final class SqlSubqueryPlan {
  private static final int STEPS_PER_EDGE = 6;
  private static final long EXISTS = PackedText.pack("exists");
  private static final long SCALAR = PackedText.pack("scalar");
  private static final long MEMBER = PackedText.pack("member");
  private static final long EXECUTE = PackedText.pack("execute");
  private static final long FILTER = PackedText.pack("filter");
  private static final long RESULT = PackedText.pack("result");
  private static final long PARENT = PackedText.pack("parent");
  private static final long INDEX = PackedText.pack("index");
  private static final long PRIMARY = PackedText.pack("primary");
  private static final long TABLE = PackedText.pack("table");

  private final BoundSqlQuery query;
  private final BoundSqlStatement bound;
  private final SqlSubqueryAccess access;
  private final SqlSubqueryResultCache cache;
  private final long[] invocations = new long[SqlQuery.MAXIMUM_EDGES];
  private final long[] executions = new long[SqlQuery.MAXIMUM_EDGES];
  private final long[] candidates = new long[SqlQuery.MAXIMUM_EDGES];
  private final long[] accepted = new long[SqlQuery.MAXIMUM_EDGES];
  private final long[] results = new long[SqlQuery.MAXIMUM_EDGES];
  private final long[] parentAccepted = new long[SqlQuery.MAXIMUM_QUERY_BLOCKS];

  SqlSubqueryPlan(
      BoundSqlStatement statement,
      SqlSubqueryAccess accessPlan,
      SqlSubqueryResultCache resultCache) {
    bound = statement;
    query = statement.executableQuery;
    access = accessPlan;
    cache = resultCache;
  }

  void reset() {
    for (int edge = 0; edge < query.edgeCount(); edge++) {
      invocations[edge] = 0;
      executions[edge] = 0;
      candidates[edge] = 0;
      accepted[edge] = 0;
      results[edge] = 0;
    }
    for (int block = 0; block < query.blockCount(); block++) parentAccepted[block] = 0;
  }

  void invoke(int edge) { invocations[edge]++; }
  void execute(int edge) { executions[edge]++; }
  void candidate(int edge) { candidates[edge]++; }
  void accept(int edge) { accepted[edge]++; }
  void result(int edge) { results[edge]++; }
  void parentAccepted(int block) { parentAccepted[block]++; }

  int count() { return query.edgeCount() * STEPS_PER_EDGE; }

  long operator(int step) {
    int edge = step / STEPS_PER_EDGE;
    int phase = step % STEPS_PER_EDGE;
    if (phase == 1) return EXECUTE;
    if (phase == 2) {
      int column = access.column(query.edgeChild(edge));
      return column > 0 ? INDEX : column == 0 ? PRIMARY : TABLE;
    }
    if (phase == 3) return FILTER;
    if (phase == 4) return RESULT;
    if (phase == 5) return PARENT;
    return switch (query.edgeKind(edge)) {
      case SqlQuery.SUBQUERY_EXISTS -> EXISTS;
      case SqlQuery.SUBQUERY_SCALAR -> SCALAR;
      default -> MEMBER;
    };
  }

  long detail(int step) {
    int edge = step / STEPS_PER_EDGE;
    int phase = step % STEPS_PER_EDGE;
    if (phase == 0) return edgeDetail(edge);
    if (phase == 1 || phase == 4) return edge + 1L;
    if (phase == 3) {
      return bound.nestedBoolean(query.edgeChild(edge)).leafCount();
    }
    if (phase == 5) return query.edgeParent(edge);
    return access.column(query.edgeChild(edge));
  }

  long rows(int step) {
    int edge = step / STEPS_PER_EDGE;
    return switch (step % STEPS_PER_EDGE) {
      case 0 -> invocations[edge];
      case 1 -> executions[edge];
      case 2 -> candidates[edge];
      case 3 -> accepted[edge];
      case 4 -> results[edge];
      default -> parentAccepted[query.edgeParent(edge)];
    };
  }

  private long edgeDetail(int edge) {
    int child = query.edgeChild(edge);
    long detail = query.edgeParent(edge);
    detail |= (long) child << 6;
    detail |= (long) query.blockDepth(child) << 12;
    return cache.correlated(edge) ? detail | 1L << 18 : detail;
  }
}
