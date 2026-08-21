package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;

/** Bounded ordered EXPLAIN rows and ANALYZE counters for one JOIN chain. */
final class SqlJoinChainPlan {
  static final int MAXIMUM_STEPS =
      1 + SqlJoinChain.MAXIMUM_JOIN_STAGES * 4 + 3;
  private static final byte ROOT = 1;
  private static final byte CANDIDATES = 2;
  private static final byte ON_TRUE = 3;
  private static final byte EXTENSIONS = 4;
  private static final byte PUBLISHED = 5;
  private static final byte FILTERED = 6;
  private static final byte SORTED = 7;
  private static final byte LIMITED = 8;
  private static final long EXTEND = PackedText.pack("extend");
  private static final long FILTER = PackedText.pack("filter");
  private static final long INDEX = PackedText.pack("index");
  private static final long JOIN = PackedText.pack("join");
  private static final long LEFT = PackedText.pack("left");
  private static final long LOOKUP = PackedText.pack("lookup");
  private static final long ON = PackedText.pack("on");
  private static final long PRIMARY = PackedText.pack("primary");
  private static final long SORT = PackedText.pack("sort");
  private static final long TABLE = PackedText.pack("table");
  private static final long LIMIT = PackedText.pack("limit");
  private long rowLimit = Long.MAX_VALUE;

  private final long[] operators = new long[MAXIMUM_STEPS];
  private final long[] details = new long[MAXIMUM_STEPS];
  private final byte[] metrics = new byte[MAXIMUM_STEPS];
  private final byte[] stages = new byte[MAXIMUM_STEPS];
  private SqlJoinChainSource source;
  private boolean analyzed;
  private int count;

  StatusCode describe(
      BoundSqlStatement bound, SqlJoinChainSource rowSource, boolean withActuals) {
    int rootAccess = bound.accessPredicate >= 0
        && (bound.predicateColumn == 0 || bound.table.hasIndexOn(bound.predicateColumn))
        ? bound.predicateColumn : -1;
    begin(rowSource, withActuals);
    StatusCode status = root(rootAccess);
    SqlJoinChain chain = bound.command.joinChain();
    for (int stage = 0; status.isOk() && stage < chain.stageCount(); stage++) {
      int inner = bound.joinAccessInnerColumn(stage);
      boolean indexed = inner >= 0
          && (inner == 0 || bound.joinRole(stage + 1).hasIndexOn(inner));
      boolean unique = indexed
          && (inner == 0 || bound.joinRole(stage + 1).hasUniqueIndexOn(inner));
      status = stage(
          chain, stage, inner, unique ? 2 : indexed ? 1 : 0);
    }
    return finish(status, bound.command);
  }

  StatusCode describe(
      SqlBoundBlockPlans plans,
      int block,
      SqlJoinChainSource rowSource,
      boolean withActuals) {
    begin(rowSource, withActuals);
    StatusCode status = root(plans.joinRootAccessColumn(block));
    SqlJoinChain chain = plans.command(block).joinChain();
    for (int stage = 0;
        status.isOk() && stage < plans.joinStageCount(block); stage++) {
      status = stage(
          chain,
          stage,
          plans.joinRightColumn(block, stage),
          plans.joinAccessKind(block, stage));
    }
    return finish(status, plans.command(block));
  }

  int count() { return count; }
  long operator(int step) { return operators[step]; }
  long detail(int step) { return details[step]; }

  long rows(int step) {
    if (!analyzed || source == null) return -1;
    int stage = stages[step];
    return switch (metrics[step]) {
      case ROOT -> source.rootCandidates();
      case CANDIDATES -> source.stageCandidates(stage);
      case ON_TRUE -> source.stageOnTrue(stage);
      case EXTENSIONS -> source.stageNullExtensions(stage);
      case PUBLISHED -> source.stagePublished(stage);
      case FILTERED -> source.whereTrue();
      case SORTED -> source.whereTrue();
      case LIMITED -> Math.min(source.whereTrue(), rowLimit);
      default -> -1;
    };
  }

  private StatusCode append(long operator, long detail, int metric, int stage) {
    if (count >= operators.length) return StatusCode.RESOURCE_EXHAUSTED;
    operators[count] = operator;
    details[count] = detail;
    metrics[count] = (byte) metric;
    stages[count] = (byte) stage;
    count++;
    return StatusCode.OK;
  }

  private void begin(SqlJoinChainSource rowSource, boolean withActuals) {
    count = 0;
    source = rowSource;
    analyzed = withActuals;
  }

  private StatusCode root(int access) {
    return append(
        access > 0 ? INDEX : access == 0 ? PRIMARY : TABLE,
        access,
        ROOT,
        -1);
  }

  private StatusCode stage(
      SqlJoinChain chain, int stage, int inner, int access) {
    StatusCode status = append(
        access == 2 ? LOOKUP : access == 1 ? INDEX : TABLE,
        inner,
        CANDIDATES,
        stage);
    if (status.isOk()) {
      status = append(
          ON, chain.onPredicates(stage).leafCount(), ON_TRUE, stage);
    }
    if (status.isOk() && chain.isLeft(stage)) {
      status = append(EXTEND, stage + 1, EXTENSIONS, stage);
    }
    return status.isOk()
        ? append(
            chain.isLeft(stage) ? LEFT : JOIN,
            chain.onPredicates(stage).leafCount(),
            PUBLISHED,
            stage)
        : status;
  }

  private StatusCode finish(StatusCode status, SqlCommand command) {
    rowLimit = command.rowLimit();
    int where = command.wherePredicates().leafCount();
    if (status.isOk() && where > 0) {
      status = append(FILTER, where, FILTERED, -1);
    }
    if (status.isOk() && command.isOrdered()) {
      status = append(
          SORT, command.isDescendingOrder() ? -1 : 1, SORTED, -1);
    }
    return status.isOk() && rowLimit != Long.MAX_VALUE
        ? append(LIMIT, rowLimit, LIMITED, -1) : status;
  }
}
