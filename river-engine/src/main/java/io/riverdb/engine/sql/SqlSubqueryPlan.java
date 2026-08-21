package io.riverdb.engine.sql;

import io.riverdb.base.text.PackedText;
import io.riverdb.sql.SqlQuery;

/** Bounded logical, physical, and runtime plan evidence for graph edges. */
final class SqlSubqueryPlan {
  private static final int STEPS_PER_EDGE = 3;
  private static final long EXISTS = PackedText.pack("exists");
  private static final long SCALAR = PackedText.pack("scalar");
  private static final long MEMBER = PackedText.pack("member");
  private static final long FILTER = PackedText.pack("filter");
  private static final long INDEX = PackedText.pack("index");
  private static final long PRIMARY = PackedText.pack("primary");
  private static final long TABLE = PackedText.pack("table");

  private final BoundSqlQuery query;
  private final BoundSqlStatement bound;
  private final SqlSubqueryAccess access;
  private final long[] executions = new long[SqlQuery.MAXIMUM_EDGES];
  private final long[] visited = new long[SqlQuery.MAXIMUM_EDGES];
  private final long[] accepted = new long[SqlQuery.MAXIMUM_EDGES];

  SqlSubqueryPlan(BoundSqlStatement statement, SqlSubqueryAccess accessPlan) {
    bound = statement;
    query = statement.executableQuery;
    access = accessPlan;
  }

  void reset() {
    for (int edge = 0; edge < query.edgeCount(); edge++) {
      executions[edge] = 0;
      visited[edge] = 0;
      accepted[edge] = 0;
    }
  }

  void execute(int edge) { executions[edge]++; }
  void visit(int edge) { visited[edge]++; }
  void accept(int edge) { accepted[edge]++; }

  int count() { return query.edgeCount() * STEPS_PER_EDGE; }

  long operator(int step) {
    int edge = step / STEPS_PER_EDGE;
    int phase = step % STEPS_PER_EDGE;
    if (phase == 1) return FILTER;
    if (phase == 2) {
      int column = access.column(query.edgeChild(edge));
      return column > 0 ? INDEX : column == 0 ? PRIMARY : TABLE;
    }
    return switch (query.edgeKind(edge)) {
      case SqlQuery.SUBQUERY_EXISTS -> EXISTS;
      case SqlQuery.SUBQUERY_SCALAR -> SCALAR;
      default -> MEMBER;
    };
  }

  long detail(int step) {
    int edge = step / STEPS_PER_EDGE;
    int phase = step % STEPS_PER_EDGE;
    if (phase == 0) return edge + 1L;
    if (phase == 1) {
      return bound.nestedBoolean(query.edgeChild(edge)).leafCount();
    }
    return access.column(query.edgeChild(edge));
  }

  long rows(int step) {
    int edge = step / STEPS_PER_EDGE;
    return switch (step % STEPS_PER_EDGE) {
      case 0 -> executions[edge];
      case 1 -> accepted[edge];
      default -> visited[edge];
    };
  }
}
