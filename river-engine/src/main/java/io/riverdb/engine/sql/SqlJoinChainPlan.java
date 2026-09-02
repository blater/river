package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;

/** Bounded ordered EXPLAIN rows and ANALYZE counters for one JOIN chain. */
final class SqlJoinChainPlan {
  static final int MAXIMUM_STEPS =
      1 + SqlJoinChain.MAXIMUM_JOIN_STAGES * 4 + 3
          + SqlJoinChain.MAXIMUM_JOIN_ROLES * 2;
  private static final byte ROOT = 1;
  private static final byte CANDIDATES = 2;
  private static final byte ON_TRUE = 3;
  private static final byte EXTENSIONS = 4;
  private static final byte PUBLISHED = 5;
  private static final byte FILTERED = 6;
  private static final byte SORTED = 7;
  private static final byte LIMITED = 8;
  private static final byte ESTIMATED = 9;
  private static final long ESTIMATE = PackedText.pack("est");
  private static final long EXACT = PackedText.pack("exact");
  private static final long EXTEND = PackedText.pack("extend");
  private static final long FILTER = PackedText.pack("filter");
  private static final long INDEX = PackedText.pack("index");
  private static final long LOOKUP = PackedText.pack("lookup");
  private static final long ON = PackedText.pack("on");
  private static final long PRIMARY = PackedText.pack("primary");
  private static final long SORT = PackedText.pack("sort");
  private static final long TABLE = PackedText.pack("table");
  private static final long SAMPLED = PackedText.pack("sample");
  private static final long LIMIT = PackedText.pack("limit");
  private long rowLimit = Long.MAX_VALUE;

  private final long[] operators = new long[MAXIMUM_STEPS];
  private final long[] details = new long[MAXIMUM_STEPS];
  private final byte[] metrics = new byte[MAXIMUM_STEPS];
  private final byte[] stages = new byte[MAXIMUM_STEPS];
  private final long[] estimates = new long[MAXIMUM_STEPS];
  private final SqlJoinStrategyPlan strategyPlan = new SqlJoinStrategyPlan();
  private SqlJoinChainSource source;
  private SqlUniversalJoinSource universal;
  private boolean analyzed;
  private int count;

  StatusCode describe(
      SqlCommand command,
      SqlBoundJoinContext context,
      SqlJoinChainSource rowSource,
      boolean withActuals) {
    int rootAccess = context.strategy(0) == SqlJoinStrategy.MERGE
        ? context.strategyOuterColumn(0)
        : context.accessPredicate >= 0
            && (context.predicateColumn == 0
                || context.table(0).hasIndexOn(context.predicateColumn))
            ? context.predicateColumn : -1;
    begin(rowSource, withActuals);
    StatusCode status = root(rootAccess);
    if (status.isOk()) status = statistics(context, 0);
    SqlJoinChain chain = command.joinChain();
    for (int stage = 0; status.isOk() && stage < chain.stageCount(); stage++) {
      int strategy = context.strategy(stage);
      int inner = strategyPlan.directInner(context, stage);
      status = stage(
          chain, stage, inner, strategyPlan.directAccess(context, stage, inner),
          strategy);
      if (status.isOk()) status = statistics(context, stage + 1);
      if (status.isOk()) status = estimate(context, stage);
    }
    return finish(status, command);
  }

  StatusCode describeUniversal(
      SqlCommand command,
      SqlBoundJoinContext context,
      SqlUniversalJoinSource rowSource,
      boolean withActuals) {
    begin(rowSource, withActuals);
    StatusCode status = root(rowSource.accessColumn(0));
    if (status.isOk()) status = statistics(context, 0);
    SqlJoinChain chain = command.joinChain();
    for (int stage = 0; status.isOk() && stage < chain.stageCount(); stage++) {
      int role = stage + 1;
      int strategy = rowSource.strategy(stage);
      int inner = strategy != SqlJoinStrategy.NESTED_LOOP
          ? strategyPlan.directInner(context, stage) : context.accessInnerColumn(stage);
      int access = strategy != SqlJoinStrategy.NESTED_LOOP
          ? strategyPlan.directAccess(context, stage, inner)
          : !rowSource.indexedRole(role) ? 0
              : rowSource.exactRole(role) && rowSource.uniqueRole(role) ? 2 : 1;
      status = stage(
          chain, stage, inner, access, strategy);
      if (status.isOk()) status = statistics(context, role);
      if (status.isOk()) status = estimate(context, stage);
    }
    return finish(status, command);
  }

  StatusCode describe(
      SqlBoundBlockPlans plans,
      int block,
      SqlJoinChainSource rowSource,
      boolean withActuals) {
    begin(rowSource, withActuals);
    StatusCode status = root(plans.joinRootAccessColumn(block));
    if (status.isOk()) status = statistics(plans, block, 0);
    SqlJoinChain chain = plans.command(block).joinChain();
    for (int stage = 0;
        status.isOk() && stage < plans.joinStageCount(block); stage++) {
      status = stage(
          chain,
          stage,
          plans.joinRightColumn(block, stage),
          plans.joinAccessKind(block, stage),
          plans.joinStrategy(block, stage));
      if (status.isOk()) status = statistics(plans, block, stage + 1);
      if (status.isOk()) status = estimate(plans, block, stage);
    }
    return finish(status, plans.command(block));
  }

  int count() { return count; }
  long operator(int step) {
    return strategyPlan.operator(
        step, stages[step], operators[step], analyzed, source, universal);
  }
  long detail(int step) { return details[step]; }

  long rows(int step) {
    if (metrics[step] == ESTIMATED) return estimates[step];
    if (!analyzed || source == null && universal == null) return -1;
    int stage = stages[step];
    return switch (metrics[step]) {
      case ROOT -> roots();
      case CANDIDATES -> candidates(stage);
      case ON_TRUE -> onTrue(stage);
      case EXTENSIONS -> nullExtensions(stage);
      case PUBLISHED -> published(stage);
      case FILTERED -> whereTrue();
      case SORTED -> whereTrue();
      case LIMITED -> Math.min(whereTrue(), rowLimit);
      default -> -1;
    };
  }

  private StatusCode append(long operator, long detail, int metric, int stage) {
    if (count >= operators.length) return StatusCode.RESOURCE_EXHAUSTED;
    operators[count] = operator;
    details[count] = detail;
    metrics[count] = (byte) metric;
    stages[count] = (byte) stage;
    estimates[count] = 0;
    strategyPlan.clear(count);
    count++;
    return StatusCode.OK;
  }

  private StatusCode appendEstimate(long operator, long detail, long rows) {
    int step = count;
    StatusCode status = append(operator, detail, ESTIMATED, -1);
    if (status.isOk()) estimates[step] = rows;
    return status;
  }

  private void begin(SqlJoinChainSource rowSource, boolean withActuals) {
    count = 0;
    source = rowSource;
    universal = null;
    analyzed = withActuals;
  }

  private void begin(SqlUniversalJoinSource rowSource, boolean withActuals) {
    count = 0;
    source = null;
    universal = rowSource;
    analyzed = withActuals;
  }

  private long roots() {
    return source != null ? source.rootCandidates() : universal.rootCandidates();
  }

  private long candidates(int stage) {
    return source != null ? source.stageAccessRows(stage) : universal.stageAccessRows(stage);
  }

  private long onTrue(int stage) {
    return source != null ? source.stageOnTrue(stage) : universal.stageOnTrue(stage);
  }

  private long nullExtensions(int stage) {
    return source != null
        ? source.stageNullExtensions(stage) : universal.stageNullExtensions(stage);
  }

  private long published(int stage) {
    return source != null ? source.stagePublished(stage) : universal.stagePublished(stage);
  }

  private long whereTrue() {
    return source != null ? source.whereTrue() : universal.whereTrue();
  }

  private StatusCode root(int access) {
    return append(
        access > 0 ? INDEX : access == 0 ? PRIMARY : TABLE,
        access,
        ROOT,
        -1);
  }

  private StatusCode statistics(SqlBoundJoinContext context, int role) {
    if (!context.estimatesAvailable()) return StatusCode.OK;
    return appendEstimate(
        context.statistics(role).sampled() ? SAMPLED : EXACT,
        context.statistics(role).epoch(),
        context.statistics(role).rowCount());
  }

  private StatusCode estimate(SqlBoundJoinContext context, int stage) {
    return context.estimatesAvailable()
        ? appendEstimate(ESTIMATE, stage + 1, context.estimatedRows(stage))
        : StatusCode.OK;
  }

  private StatusCode statistics(SqlBoundBlockPlans plans, int block, int role) {
    if (!plans.joinEstimatesAvailable(block)) return StatusCode.OK;
    return appendEstimate(
        plans.joinStatisticsSampled(block, role) ? SAMPLED : EXACT,
        plans.joinStatisticsEpoch(block, role),
        plans.joinStatisticsRows(block, role));
  }

  private StatusCode estimate(SqlBoundBlockPlans plans, int block, int stage) {
    return plans.joinEstimatesAvailable(block)
        ? appendEstimate(ESTIMATE, stage + 1, plans.joinEstimatedRows(block, stage))
        : StatusCode.OK;
  }

  private StatusCode stage(
      SqlJoinChain chain, int stage, int inner, int access, int strategy) {
    boolean selectedStrategy = strategy != SqlJoinStrategy.NESTED_LOOP;
    StatusCode status = append(
        strategyPlan.access(strategy, access),
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
    if (!status.isOk()) return status;
    int published = count;
    status = append(
        strategyPlan.published(strategy, chain.isLeft(stage)),
        chain.onPredicates(stage).leafCount(),
        PUBLISHED,
        stage);
    if (status.isOk() && selectedStrategy) {
      strategyPlan.set(published, strategy, chain.isLeft(stage));
    }
    return status;
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
