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

  void configureScalarAggregate(
      SqlPhysicalPlan plan,
      BoundSqlStatement bound,
      BoundSqlQuery.Block command) {
    plan.setFilterCount(bound.predicateCount);
    plan.setHavingCount(bound.command.havingPredicateCount());
    plan.setAggregate(
        command.type() == SqlCommandType.COUNT
            ? -1 : bound.projectedColumns[0]);
    plan.setAccessColumn(
        bound.accessPredicate >= 0
            ? bound.predicateColumn == 0
                || bound.table.hasIndexOn(bound.predicateColumn)
                ? bound.predicateColumn : -1
            : -1);
    describe(plan);
  }

  void describe(SqlPhysicalPlan plan) {
    plan.resetSteps();
    if (plan.rowLimit() != Long.MAX_VALUE) {
      plan.addStep(LIMIT, plan.rowLimit());
    }
    describeLogical(plan);
    describePhysical(plan);
  }

  void configureExplainResult(SqlPhysicalPlan plan, boolean analyzed) {
    plan.setExplainResult(analyzed);
    plan.setResultColumn(0, 0, SqlTypeDescriptor.varchar(64), "operator");
    plan.setResultColumn(1, 1, SqlTypeDescriptor.BIGINT, "detail");
    plan.setResultColumn(2, 2, SqlTypeDescriptor.BIGINT, "rows");
    plan.setResultNullable(2, true);
  }

  private static void describeLogical(SqlPhysicalPlan plan) {
    if (plan.havingCount() > 0) {
      plan.addStep(HAVING, plan.havingCount());
    }
    if (plan.aggregate()) {
      plan.addStep(AGGREGATE, plan.aggregateColumn());
    } else if (plan.groupAggregate()) {
      plan.addStep(GROUP, plan.groupAggregateColumn());
    } else if (plan.distinct()) {
      plan.addStep(DISTINCT, plan.groupColumn());
    } else if (plan.join()) {
      plan.addStep(plan.leftJoin() ? LEFT : JOIN, plan.joinOuterColumn());
    }
  }

  private static void describePhysical(SqlPhysicalPlan plan) {
    if (plan.nestedDepth() > 1) {
      plan.addStep(NESTED, plan.nestedDepth());
    }
    if (plan.sorts()) {
      plan.addStep(SORT, plan.descending() ? -1 : 1);
    }
    if (plan.filterCount() > 0) {
      plan.addStep(FILTER, plan.filterCount());
    }
    plan.addStep(
        plan.accessColumn() > 0
            ? INDEX
            : plan.accessColumn() == 0 ? PRIMARY : TABLE,
        plan.accessColumn());
    if (plan.join()) {
      plan.addStep(
          plan.joinInnerIndexed() ? LOOKUP : TABLE,
          plan.joinInnerColumn());
    }
  }
}
