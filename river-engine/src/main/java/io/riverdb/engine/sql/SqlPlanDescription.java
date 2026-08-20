package io.riverdb.engine.sql;

import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommandType;

/** Publishes the bounded logical and physical steps for one prepared plan. */
final class SqlPlanDescription {
  private static final long AGGREGATE = PackedText.pack("agg");
  private static final long DISTINCT = PackedText.pack("dedupe");
  private static final long FILTER = PackedText.pack("filter");
  private static final long GROUP = PackedText.pack("group");
  private static final long HAVING = PackedText.pack("having");
  private static final long INDEX = PackedText.pack("index");
  private static final long JOIN = PackedText.pack("join");
  private static final long LEFT = PackedText.pack("left");
  private static final long LIMIT = PackedText.pack("limit");
  private static final long LOOKUP = PackedText.pack("lookup");
  private static final long NESTED = PackedText.pack("nested");
  private static final long PRIMARY = PackedText.pack("primary");
  private static final long SORT = PackedText.pack("sort");
  private static final long TABLE = PackedText.pack("table");

  io.riverdb.base.error.StatusCode configureScalarAggregate(
      SqlPhysicalPlan plan,
      BoundSqlStatement bound,
      BoundSqlQuery.Block command) {
    plan.setFilterCount(bound.predicateCount);
    plan.setHavingCount(bound.command.booleanHavingPredicates().leafCount());
    plan.setAggregate(
        command.type() == SqlCommandType.COUNT
            ? -1 : bound.projectedColumns[0]);
    plan.setAccessColumn(
        bound.accessPredicate >= 0
            ? bound.predicateColumn == 0
                || bound.table.hasIndexOn(bound.predicateColumn)
                ? bound.predicateColumn : -1
            : -1);
    return describe(plan);
  }

  io.riverdb.base.error.StatusCode describe(SqlPhysicalPlan plan) {
    plan.resetSteps();
    io.riverdb.base.error.StatusCode status = io.riverdb.base.error.StatusCode.OK;
    if (plan.rowLimit() != Long.MAX_VALUE) {
      status = plan.addStep(LIMIT, plan.rowLimit());
    }
    if (status.isOk()) status = describeLogical(plan);
    return status.isOk() ? describePhysical(plan) : status;
  }

  void configureExplainResult(SqlPhysicalPlan plan, boolean analyzed) {
    plan.setExplainResult(analyzed);
    plan.setResultColumn(0, 0, SqlTypeDescriptor.varchar(64), "operator");
    plan.setResultColumn(1, 1, SqlTypeDescriptor.BIGINT, "detail");
    plan.setResultColumn(2, 2, SqlTypeDescriptor.BIGINT, "rows");
    plan.setResultNullable(2, true);
  }

  private static io.riverdb.base.error.StatusCode describeLogical(SqlPhysicalPlan plan) {
    io.riverdb.base.error.StatusCode status = io.riverdb.base.error.StatusCode.OK;
    if (plan.havingCount() > 0) {
      status = plan.addStep(HAVING, plan.havingCount());
    }
    if (status.isOk() && plan.aggregate()) {
      status = plan.addStep(AGGREGATE, plan.aggregateColumn());
    } else if (status.isOk() && plan.groupAggregate()) {
      status = plan.addStep(GROUP, plan.groupAggregateColumn());
    } else if (status.isOk() && plan.distinct()) {
      status = plan.addStep(DISTINCT, plan.groupColumn());
    } else if (status.isOk() && plan.join()) {
      status = plan.addStep(
          plan.leftJoin() ? LEFT : JOIN, plan.joinPredicateCount());
    }
    return status;
  }

  private static io.riverdb.base.error.StatusCode describePhysical(SqlPhysicalPlan plan) {
    io.riverdb.base.error.StatusCode status = io.riverdb.base.error.StatusCode.OK;
    if (plan.nestedDepth() > 1) {
      status = plan.addStep(NESTED, plan.nestedDepth());
    }
    if (status.isOk() && plan.sorts()) {
      status = plan.addStep(SORT, plan.descending() ? -1 : 1);
    }
    if (status.isOk() && plan.filterCount() > 0) {
      status = plan.addStep(FILTER, plan.filterCount());
    }
    if (status.isOk()) status = plan.addStep(
      plan.accessColumn() > 0
          ? INDEX
          : plan.accessColumn() == 0 ? PRIMARY : TABLE,
      plan.accessColumn());
    if (status.isOk() && plan.join()) {
      status = plan.addStep(
          plan.joinInnerIndexed() ? LOOKUP : TABLE,
          plan.joinInnerColumn());
    }
    return status;
  }
}
