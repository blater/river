package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlCommandType;

/** Reusable bounded physical-plan state shared by execution and EXPLAIN. */
final class SqlPhysicalPlan {
  static final int MAXIMUM_STEPS = SqlShapeLimits.MAX_PLAN_STEPS;

  private final SqlPhysicalPlanSteps steps;
  private final SqlPhysicalResultShape result;
  private int accessColumn = -1;
  private long rowLimit = Long.MAX_VALUE;
  private SqlCommandType commandType;
  private int aggregateColumn = -1;
  private int filterCount;
  private int nestedDepth;
  private int havingCount;
  private boolean descending;
  private boolean aggregate;
  private boolean groupAggregate;
  private boolean distinct;
  private boolean explainResult;
  private boolean explainAnalyzed;
  private int groupColumn = -1;
  private int groupAggregateColumn = -1;
  private int orderColumn = -1;
  private boolean sort;
  private long actualRows;
  private SqlBlockStagePlan blockStages;
  private SqlJoinChainPlan joinStages;
  private SqlUnionStagePlan unionStages;
  private SqlSubqueryPlan subqueries;

  SqlPhysicalPlan() { this(new SqlSessionShapeBudget(null)); }
  SqlPhysicalPlan(SqlSessionShapeBudget budget) {
    steps = new SqlPhysicalPlanSteps(budget);
    result = new SqlPhysicalResultShape(budget);
  }

  void reset() {
    steps.reset();
    result.reset();
    accessColumn = -1;
    rowLimit = Long.MAX_VALUE;
    commandType = null;
    aggregateColumn = -1;
    filterCount = 0;
    nestedDepth = 0;
    havingCount = 0;
    descending = false;
    aggregate = false;
    groupAggregate = false;
    distinct = false;
    explainResult = false;
    explainAnalyzed = false;
    groupColumn = -1;
    groupAggregateColumn = -1;
    orderColumn = -1;
    sort = false;
    actualRows = 0;
    blockStages = null;
    joinStages = null;
    unionStages = null;
    subqueries = null;
  }

  void resetSteps() {
    steps.reset();
    blockStages = null;
    joinStages = null;
    unionStages = null;
  }

  void setBlockStages(SqlBlockStagePlan stages) {
    blockStages = stages;
    joinStages = null;
    unionStages = null;
    steps.reset();
  }

  void setJoinStages(SqlJoinChainPlan stages) {
    joinStages = stages;
    blockStages = null;
    unionStages = null;
    steps.reset();
  }

  void setUnionStages(SqlUnionStagePlan stages) {
    unionStages = stages;
    blockStages = null;
    joinStages = null;
    steps.reset();
  }

  void setSubqueries(SqlSubqueryPlan nested) { subqueries = nested; }

  void setAccessColumn(int column) {
    accessColumn = column;
  }

  int accessColumn() {
    return accessColumn;
  }

  void setSort(boolean required) {
    sort = required;
  }

  boolean sorts() {
    return sort;
  }

  void setCommand(BoundSqlQuery.Block command) {
    commandType = command.type();
    rowLimit = command.rowLimit();
    descending = command.isDescendingOrder();
  }

  SqlCommandType commandType() {
    return commandType;
  }

  long rowLimit() {
    return rowLimit;
  }

  void setAggregate(int column) {
    aggregate = true;
    aggregateColumn = column;
  }

  boolean aggregate() {
    return aggregate;
  }

  int aggregateColumn() {
    return aggregateColumn;
  }

  void setFilterCount(int count) {
    filterCount = count;
  }

  int filterCount() {
    return filterCount;
  }

  void setNestedDepth(int depth) {
    nestedDepth = depth;
  }

  int nestedDepth() {
    return nestedDepth;
  }

  void setHavingCount(int predicates) { havingCount = predicates; }
  int havingCount() { return havingCount; }

  boolean descending() {
    return descending;
  }

  void setOrderColumn(int column) {
    orderColumn = column;
  }

  int orderColumn() {
    return orderColumn;
  }

  void setActualRows(long rows) {
    actualRows = rows;
  }

  long actualRows() {
    return actualRows;
  }

  boolean valueIndex() {
    return accessColumn > 0;
  }

  boolean catalogObjectScan() {
    return commandType == SqlCommandType.SHOW_TABLES;
  }

  boolean catalogIndexScan() {
    return commandType == SqlCommandType.SHOW_INDEXES;
  }

  boolean catalogColumnScan() {
    return commandType == SqlCommandType.SHOW_COLUMNS;
  }

  void setGroupAggregate(int column, int aggregateColumn) {
    groupAggregate = true;
    groupColumn = column;
    groupAggregateColumn = aggregateColumn;
  }

  boolean groupAggregate() {
    return groupAggregate;
  }

  int groupColumn() {
    return groupColumn;
  }

  int groupAggregateColumn() {
    return groupAggregateColumn;
  }

  void setDistinct(int column) {
    distinct = true;
    groupColumn = column;
  }

  boolean distinct() {
    return distinct;
  }

  void setExplainResult(boolean analyzed) {
    explainResult = true;
    explainAnalyzed = analyzed;
    result.begin(3);
  }

  StatusCode beginResult(int columns) {
    result.begin(columns);
    return result.status();
  }

  boolean explainResult() {
    return explainResult;
  }

  boolean explainAnalyzed() {
    return explainAnalyzed;
  }

  void setResultShape(
      int[] projections, int[] types, int count, BoundSqlQuery.Block command) {
    result.begin(count);
    for (int index = 0; index < count; index++) {
      CharSequence name = command.columnOutputName(index);
      result.set(index, projections[index], types[index], name);
    }
  }

  void setResultColumn(
      int index, int projection, int type, CharSequence name) {
    result.set(index, projection, type, name);
  }

  void setBlockResult(SqlBlockSchema schema, int visibleColumns) {
    result.begin(visibleColumns);
    for (int column = 0; column < visibleColumns; column++) {
      setResultColumn(column, column, schema.descriptor(column), schema.name(column));
      setResultNullable(column, schema.nullable(column));
    }
  }

  int resultColumnCount() {
    return result.count();
  }

  int resultProjection(int index) {
    return result.projection(index);
  }

  int resultType(int index) {
    return result.descriptor(index);
  }

  CharSequence resultName(int index) {
    return result.name(index);
  }

  int resultNameLength(int index) {
    return result.nameLength(index);
  }

  void setResultNullable(int index, boolean nullable) {
    result.setNullable(index, nullable);
  }

  boolean resultNullable(int index) {
    return result.isNullable(index);
  }

  long resultNullableMask() {
    return result.nullableWord(0);
  }

  long resultNullableWord(int word) { return result.nullableWord(word); }
  int resultNullableWordCount() { return result.nullableWordCount(); }

  StatusCode reserve(SqlScanRowResult row) {
    StatusCode status = result.status();
    return status.isOk() ? row.prepare(result.descriptors(), result.count()) : status;
  }

  int[] copyResultDescriptors(int[] destination, int columns) {
    return result.copyDescriptors(destination, columns);
  }

  StatusCode claimCapability(
      SqlScanCursor cursor,
      SqlQueryExecution execution,
      long generation) {
    StatusCode status = result.status();
    return status.isOk() ? cursor.claim(
        execution,
        generation,
        result.projections(),
        result.count(),
        rowLimit) : status;
  }

  StatusCode addStep(long operator, long detail) {
    return steps.add(operator, detail);
  }

  int stepCount() {
    return baseStepCount() + (subqueries == null ? 0 : subqueries.count());
  }

  long operator(int index) {
    int base = baseStepCount();
    if (index >= base) return subqueries.operator(index - base);
    return unionStages != null ? unionStages.operator(index)
        : blockStages != null ? blockStages.operator(index)
        : joinStages != null ? joinStages.operator(index) : steps.operator(index);
  }

  long detail(int index) {
    int base = baseStepCount();
    if (index >= base) return subqueries.detail(index - base);
    return unionStages != null ? unionStages.detail(index)
        : blockStages != null ? blockStages.detail(index)
        : joinStages != null ? joinStages.detail(index) : steps.detail(index);
  }

  long stepRows(int index) {
    int base = baseStepCount();
    if (index >= base) return subqueries.rows(index - base);
    return unionStages != null ? unionStages.rows(index)
        : blockStages != null ? blockStages.rows(index)
        : joinStages != null ? joinStages.rows(index)
            : index == 0 ? actualRows : -1;
  }

  private int baseStepCount() {
    return unionStages != null ? unionStages.count()
        : blockStages != null ? blockStages.count()
        : joinStages != null ? joinStages.count() : steps.count();
  }
}
