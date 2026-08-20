package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Lazy bounded EXPLAIN state for one cardinality-stage pipeline. */
final class SqlBlockStagePlan {
  private static final int STEPS_PER_BLOCK = 8;
  private static final int MAXIMUM_STEPS =
      SqlQuery.MAXIMUM_QUERY_BLOCKS * STEPS_PER_BLOCK + 2;
  private static final long AGGREGATE = PackedText.pack("agg");
  private static final long BLOCK = PackedText.pack("block");
  private static final long DISTINCT = PackedText.pack("dedupe");
  private static final long FILTER = PackedText.pack("filter");
  private static final long GROUP = PackedText.pack("group");
  private static final long HAVING = PackedText.pack("having");
  private static final long INDEX = PackedText.pack("index");
  private static final long JOIN = PackedText.pack("join");
  private static final long LEFT = PackedText.pack("left");
  private static final long LOOKUP = PackedText.pack("lookup");
  private static final long PRIMARY = PackedText.pack("primary");
  private static final long SORT = PackedText.pack("sort");
  private static final long TABLE = PackedText.pack("table");

  private final long[] operators = new long[MAXIMUM_STEPS];
  private final long[] details = new long[MAXIMUM_STEPS];
  private final long[] rows = new long[MAXIMUM_STEPS];
  private final int[] rowSteps = new int[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private int count;

  StatusCode describe(SqlBoundBlockPlans plans) {
    count = 0;
    for (int block = 0; block < plans.count(); block++) {
      rowSteps[block] = count;
      StatusCode status = append(BLOCK, block + 1);
      if (status.isOk() && plans.command(block).rowLimit() != Long.MAX_VALUE) {
        status = append(io.riverdb.base.text.PackedText.pack("limit"),
            plans.command(block).rowLimit());
      }
      if (status.isOk()) status = logical(plans.command(block));
      if (!status.isOk()) return status;
    }
    int deepest = plans.count() - 1;
    boolean join = plans.command(deepest).type()
        == io.riverdb.sql.SqlCommandType.JOIN_SCAN;
    int access = join ? plans.joinOuterAccessColumn(deepest) : -1;
    StatusCode status = append(
        access > 0 ? INDEX : access == 0 ? PRIMARY : TABLE, access);
    if (status.isOk() && join) {
      status = append(
          plans.joinRightIndexed(deepest) ? LOOKUP : TABLE,
          plans.joinRightColumn(deepest));
    }
    return status;
  }

  void setRows(int block, long actualRows) {
    if (block >= 0 && block < rowSteps.length) rows[rowSteps[block]] = actualRows;
  }

  int count() { return count; }
  long operator(int step) { return operators[step]; }
  long detail(int step) { return details[step]; }
  long rows(int step) { return rows[step]; }

  private StatusCode logical(SqlCommand command) {
    boolean distinct = command.type() == io.riverdb.sql.SqlCommandType.DISTINCT_SCAN;
    StatusCode status = outputOrder(command, distinct);
    if (status.isOk()) status = aggregate(command, distinct);
    if (status.isOk()) status = inputOrder(command, distinct);
    if (status.isOk()
        && command.type() == io.riverdb.sql.SqlCommandType.JOIN_SCAN) {
      status = append(
          command.isLeftJoin() ? LEFT : JOIN,
          command.onPredicates().leafCount());
    }
    if (status.isOk() && command.wherePredicates().leafCount() > 0) {
      status = append(FILTER, command.wherePredicates().leafCount());
    }
    return status;
  }

  private StatusCode outputOrder(SqlCommand command, boolean distinct) {
    return command.isOrdered() && !distinct
        ? append(SORT, command.isDescendingOrder() ? -1 : 1) : StatusCode.OK;
  }

  private StatusCode aggregate(SqlCommand command, boolean distinct) {
    int predicates = command.booleanHavingPredicates().leafCount();
    StatusCode status = predicates > 0
        ? append(HAVING, predicates) : StatusCode.OK;
    if (!status.isOk()) return status;
    if (SqlBinder.isScalarAggregate(command.type())) {
      return append(AGGREGATE, command.aggregateInvocationCount());
    }
    if (SqlBinder.isGroupAggregate(command.type())) {
      return append(GROUP, command.aggregateInvocationCount());
    }
    return distinct ? append(DISTINCT, command.columnCount()) : StatusCode.OK;
  }

  private StatusCode inputOrder(SqlCommand command, boolean distinct) {
    if (!SqlBinder.isGroupAggregate(command.type()) && !distinct) return StatusCode.OK;
    long detail = distinct && command.isOrdered()
        ? command.isDescendingOrder() ? -1 : 1 : 0;
    return append(SORT, detail);
  }

  private StatusCode append(long operator, long detail) {
    if (count >= operators.length) return StatusCode.RESOURCE_EXHAUSTED;
    operators[count] = operator;
    details[count] = detail;
    rows[count] = -1;
    count++;
    return StatusCode.OK;
  }
}
