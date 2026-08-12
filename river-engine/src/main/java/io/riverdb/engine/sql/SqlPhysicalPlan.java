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
  private long rowLimit = Long.MAX_VALUE;
  private SqlCommandType commandType;
  private int aggregateColumn = -1;
  private int filterCount;
  private int nestedDepth;
  private boolean descending;
  private boolean aggregate;
  private boolean groupAggregate;
  private boolean distinct;
  private boolean join;
  private boolean leftJoin;
  private boolean joinInnerIndexed;
  private boolean joinInnerUnique;
  private boolean explainResult;
  private boolean explainAnalyzed;
  private int groupColumn = -1;
  private int groupAggregateColumn = -1;
  private int orderColumn = -1;
  private int joinOuterColumn = -1;
  private int joinInnerColumn = -1;
  private boolean sort;
  private long actualRows;

  SqlPhysicalPlan() {
    for (int index = 0; index < resultNames.length; index++) {
      resultNames[index] = new ResultName();
    }
  }

  void reset() {
    stepCount = 0;
    accessColumn = -1;
    resultColumnCount = 0;
    rowLimit = Long.MAX_VALUE;
    commandType = null;
    aggregateColumn = -1;
    filterCount = 0;
    nestedDepth = 0;
    descending = false;
    aggregate = false;
    groupAggregate = false;
    distinct = false;
    join = false;
    leftJoin = false;
    joinInnerIndexed = false;
    joinInnerUnique = false;
    explainResult = false;
    explainAnalyzed = false;
    groupColumn = -1;
    groupAggregateColumn = -1;
    orderColumn = -1;
    joinOuterColumn = -1;
    joinInnerColumn = -1;
    sort = false;
    actualRows = 0;
  }

  void resetSteps() {
    stepCount = 0;
  }

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

  void setJoin(
      int outerColumn,
      int innerColumn,
      boolean preserveOuter,
      boolean indexedInner,
      boolean uniqueInner) {
    join = true;
    joinOuterColumn = outerColumn;
    joinInnerColumn = innerColumn;
    leftJoin = preserveOuter;
    joinInnerIndexed = indexedInner;
    joinInnerUnique = uniqueInner;
  }

  boolean join() {
    return join;
  }

  int joinOuterColumn() {
    return joinOuterColumn;
  }

  int joinInnerColumn() {
    return joinInnerColumn;
  }

  boolean leftJoin() {
    return leftJoin;
  }

  boolean joinInnerIndexed() {
    return joinInnerIndexed;
  }

  boolean joinInnerUnique() {
    return joinInnerUnique;
  }

  void setExplainResult(boolean analyzed) {
    explainResult = true;
    explainAnalyzed = analyzed;
    resultColumnCount = 0;
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

  void addStep(long operator, long detail) {
    if (stepCount < operators.length) {
      operators[stepCount] = operator;
      details[stepCount] = detail;
      stepCount++;
    }
  }

  int stepCount() {
    return stepCount;
  }

  long operator(int index) {
    return operators[index];
  }

  long detail(int index) {
    return details[index];
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
