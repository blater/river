package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommandType;

/** Reusable bounded physical-plan state shared by execution and EXPLAIN. */
final class SqlPhysicalPlan {
  static final int MAXIMUM_STEPS = 8;

  private final long[] operators = new long[MAXIMUM_STEPS];
  private final long[] details = new long[MAXIMUM_STEPS];
  private final int[] resultProjections =
      new int[TableSchema.MAXIMUM_COLUMNS];
  private final int[] resultTypes =
      new int[TableSchema.MAXIMUM_COLUMNS];
  private final ResultName[] resultNames =
      new ResultName[TableSchema.MAXIMUM_COLUMNS];
  private int stepCount;
  private int accessColumn = -1;
  private int resultColumnCount;
  private long resultNullableMask;
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
  private SqlSubqueryPlan subqueries;

  SqlPhysicalPlan() {
    for (int index = 0; index < resultNames.length; index++) {
      resultNames[index] = new ResultName();
    }
  }

  void reset() {
    stepCount = 0;
    accessColumn = -1;
    resultColumnCount = 0;
    resultNullableMask = 0;
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
    subqueries = null;
  }

  void resetSteps() {
    stepCount = 0;
    blockStages = null;
    joinStages = null;
  }

  void setBlockStages(SqlBlockStagePlan stages) {
    blockStages = stages;
    joinStages = null;
    stepCount = 0;
  }

  void setJoinStages(SqlJoinChainPlan stages) {
    joinStages = stages;
    blockStages = null;
    stepCount = 0;
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
    resultColumnCount = 0;
    resultNullableMask = 0;
  }

  boolean explainResult() {
    return explainResult;
  }

  boolean explainAnalyzed() {
    return explainAnalyzed;
  }

  void setResultShape(
      int[] projections, int[] types, int count, BoundSqlQuery.Block command) {
    resultColumnCount = count;
    for (int index = 0; index < count; index++) {
      resultProjections[index] = projections[index];
      resultTypes[index] = types[index];
      CharSequence name = command.columnOutputName(index);
      resultNames[index].copyFrom(name == null ? "" : name);
    }
  }

  void setResultColumn(
      int index, int projection, int type, CharSequence name) {
    resultProjections[index] = projection;
    resultTypes[index] = type;
    resultNames[index].copyFrom(name);
    if (resultColumnCount <= index) {
      resultColumnCount = index + 1;
    }
  }

  void setBlockResult(SqlBlockSchema schema) {
    resultColumnCount = 0;
    resultNullableMask = 0;
    for (int column = 0; column < schema.count(); column++) {
      setResultColumn(column, column, schema.descriptor(column), schema.name(column));
      setResultNullable(column, schema.nullable(column));
    }
  }

  int resultColumnCount() {
    return resultColumnCount;
  }

  int resultProjection(int index) {
    return index >= 0 && index < resultColumnCount
        ? resultProjections[index] : -1;
  }

  int resultType(int index) {
    return index >= 0 && index < resultColumnCount
        ? resultTypes[index] : 0;
  }

  CharSequence resultName(int index) {
    return index >= 0 && index < resultColumnCount
        ? resultNames[index].toString() : null;
  }

  int resultNameLength(int index) {
    return index >= 0 && index < resultColumnCount
        ? resultNames[index].length() : 0;
  }

  void setResultNullable(int index, boolean nullable) {
    if (nullable) resultNullableMask |= 1L << index;
    else resultNullableMask &= ~(1L << index);
  }

  boolean resultNullable(int index) {
    return index >= 0 && index < resultColumnCount
        && (resultNullableMask & 1L << index) != 0;
  }

  long resultNullableMask() {
    return resultNullableMask;
  }

  StatusCode claimCapability(
      SqlScanCursor cursor,
      SqlQueryExecution execution,
      long generation) {
    return cursor.claim(
        execution,
        generation,
        resultProjections,
        resultColumnCount,
        rowLimit);
  }

  StatusCode addStep(long operator, long detail) {
    if (stepCount >= operators.length) return StatusCode.RESOURCE_EXHAUSTED;
    operators[stepCount] = operator;
    details[stepCount] = detail;
    stepCount++;
    return StatusCode.OK;
  }

  int stepCount() {
    return baseStepCount() + (subqueries == null ? 0 : subqueries.count());
  }

  long operator(int index) {
    int base = baseStepCount();
    if (index >= base) return subqueries.operator(index - base);
    return blockStages != null ? blockStages.operator(index)
        : joinStages != null ? joinStages.operator(index) : operators[index];
  }

  long detail(int index) {
    int base = baseStepCount();
    if (index >= base) return subqueries.detail(index - base);
    return blockStages != null ? blockStages.detail(index)
        : joinStages != null ? joinStages.detail(index) : details[index];
  }

  long stepRows(int index) {
    int base = baseStepCount();
    if (index >= base) return subqueries.rows(index - base);
    return blockStages != null ? blockStages.rows(index)
        : joinStages != null ? joinStages.rows(index)
            : index == 0 ? actualRows : -1;
  }

  private int baseStepCount() {
    return blockStages != null ? blockStages.count()
        : joinStages != null ? joinStages.count() : stepCount;
  }

  private static final class ResultName implements CharSequence {
    private final char[] value = new char[64];
    private int length;

    void copyFrom(CharSequence source) {
      length = source.length();
      for (int index = 0; index < length; index++) {
        value[index] = source.charAt(index);
      }
    }

    @Override
    public int length() {
      return length;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length) {
        throw new IndexOutOfBoundsException(index);
      }
      return value[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      if (start < 0 || end < start || end > length) {
        throw new IndexOutOfBoundsException(start);
      }
      return new String(value, start, end - start);
    }

    @Override
    public String toString() {
      return new String(value, 0, length);
    }
  }
}
