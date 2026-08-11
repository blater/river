package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.CatalogObjectCursor;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommandType;

/** Caller-owned capability for one ordered SQL table scan. */
public final class SqlScanCursor {
  static final int MAXIMUM_PLAN_STEPS = 8;

  private final RelationalScanCursor relational = new RelationalScanCursor();
  private final CatalogObjectCursor catalogObjects = new CatalogObjectCursor();
  private final RelationalScanCursor joinInnerRelational = new RelationalScanCursor();
  private final long[] joinOuterProjectedValues =
      new long[TableSchema.MAXIMUM_COLUMNS];
  private final long[] planOperators = new long[MAXIMUM_PLAN_STEPS];
  private final long[] planDetails = new long[MAXIMUM_PLAN_STEPS];
  private SqlSession owner;
  private boolean aggregate;
  private boolean catalogObjectScan;
  private boolean groupAggregate;
  private boolean distinct;
  private boolean join;
  private boolean sorted;
  private boolean explain;
  private boolean explainAnalyzed;
  private boolean joinInnerScanActive;
  private boolean joinInnerIndexed;
  private boolean joinInnerUnique;
  private boolean leftJoin;
  private boolean joinMatched;
  private boolean groupLookahead;
  private boolean groupLookaheadNull;
  private boolean groupInputExhausted;
  private boolean distinctValueAvailable;
  private boolean distinctValueNull;
  private boolean aggregateTransactionActive;
  private boolean aggregateNull;
  private boolean aggregateVarchar;
  private long aggregateValue;
  private long aggregateCommitSequence;
  private long explainCommitSequence;
  private long explainActualRows;
  private long groupLookaheadValue;
  private long groupLookaheadAggregateValue;
  private boolean groupLookaheadAggregateNull;
  private long distinctValue;
  private int groupColumn = -1;
  private int groupAggregateColumn = -1;
  private SqlCommandType groupAggregateType;
  private int joinOuterColumn = -1;
  private int joinInnerColumn = -1;
  private long joinOuterKey;
  private long joinMatchValue;
  private long joinOuterNullMask;
  private int sortedRowCount;
  private int sortedRowIndex;
  private int planStepCount;
  private int planStepIndex;
  private boolean implicitTransaction;
  private boolean valueIndex;
  private long maximumRows = Long.MAX_VALUE;
  private final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  private int projectedColumnCount;
  private boolean active;
  private long rowsReturned;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    aggregate = false;
    catalogObjectScan = false;
    groupAggregate = false;
    distinct = false;
    join = false;
    sorted = false;
    explain = false;
    explainAnalyzed = false;
    joinInnerScanActive = false;
    joinInnerIndexed = false;
    joinInnerUnique = false;
    leftJoin = false;
    joinMatched = false;
    groupLookahead = false;
    groupLookaheadNull = false;
    groupInputExhausted = false;
    distinctValueAvailable = false;
    distinctValueNull = false;
    aggregateTransactionActive = false;
    aggregateNull = false;
    aggregateVarchar = false;
    aggregateValue = 0;
    aggregateCommitSequence = 0;
    explainCommitSequence = 0;
    explainActualRows = 0;
    groupLookaheadValue = 0;
    groupLookaheadAggregateValue = 0;
    groupLookaheadAggregateNull = false;
    distinctValue = 0;
    groupColumn = -1;
    groupAggregateColumn = -1;
    groupAggregateType = null;
    joinOuterColumn = -1;
    joinInnerColumn = -1;
    joinOuterKey = 0;
    joinMatchValue = 0;
    joinOuterNullMask = 0;
    sortedRowCount = 0;
    sortedRowIndex = 0;
    planStepCount = 0;
    planStepIndex = 0;
    implicitTransaction = false;
    valueIndex = false;
    maximumRows = Long.MAX_VALUE;
    projectedColumnCount = 0;
    rowsReturned = 0;
    StatusCode status = relational.reset();
    StatusCode inner = joinInnerRelational.reset();
    StatusCode catalog = catalogObjects.reset();
    return !status.isOk() ? status : !inner.isOk() ? inner : catalog;
  }

  CatalogObjectCursor catalogObjects() {
    return catalogObjects;
  }

  RelationalScanCursor relational() {
    return relational;
  }

  RelationalScanCursor joinInnerRelational() {
    return joinInnerRelational;
  }

  StatusCode claim(
      SqlSession session,
      boolean implicit,
      boolean indexedValue,
      int[] projections,
      int projectionCount,
      long rowLimit) {
    if (active
        || projections == null
        || projectionCount <= 0
        || projectionCount > projectedColumns.length
        || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    valueIndex = indexedValue;
    maximumRows = rowLimit;
    projectedColumnCount = projectionCount;
    for (int index = 0; index < projectionCount; index++) {
      projectedColumns[index] = projections[index];
    }
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimCatalogObjects(SqlSession session, boolean implicit) {
    if (active || session == null) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    catalogObjectScan = true;
    projectedColumnCount = 2;
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimSorted(
      SqlSession session,
      boolean implicit,
      int[] projections,
      int projectionCount,
      int rowCount,
      long rowLimit) {
    if (active
        || session == null
        || projections == null
        || projectionCount <= 0
        || projectionCount > projectedColumns.length
        || rowCount < 0
        || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    sorted = true;
    sortedRowCount = rowCount;
    sortedRowIndex = 0;
    maximumRows = rowLimit;
    projectedColumnCount = projectionCount;
    for (int index = 0; index < projectionCount; index++) {
      projectedColumns[index] = projections[index];
    }
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimAggregate(
      SqlSession session,
      long value,
      boolean nullValue,
      boolean varchar,
      boolean transactionActive,
      long commitSequence) {
    if (active || session == null || commitSequence < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    aggregate = true;
    aggregateValue = value;
    aggregateNull = nullValue;
    aggregateVarchar = varchar;
    aggregateTransactionActive = transactionActive;
    aggregateCommitSequence = commitSequence;
    projectedColumnCount = 1;
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimGroupAggregate(
      SqlSession session,
      boolean implicit,
      SqlCommandType aggregateType,
      int column,
      int aggregateColumn,
      boolean indexedValue,
      int sortedInputRows,
      long rowLimit) {
    if (active
        || session == null
        || !isGroupAggregate(aggregateType)
        || column < 0
        || aggregateColumn < -1
        || aggregateType != SqlCommandType.GROUP_COUNT && aggregateColumn < 0
        || sortedInputRows < -1
        || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    valueIndex = indexedValue;
    groupAggregate = true;
    groupAggregateType = aggregateType;
    groupColumn = column;
    groupAggregateColumn = aggregateColumn;
    sorted = sortedInputRows >= 0;
    sortedRowCount = sorted ? sortedInputRows : 0;
    sortedRowIndex = 0;
    maximumRows = rowLimit;
    projectedColumns[0] = column;
    projectedColumnCount = 2;
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimDistinct(
      SqlSession session,
      boolean implicit,
      int column,
      boolean indexedValue,
      int sortedInputRows,
      long rowLimit) {
    if (active
        || session == null
        || column < 0
        || sortedInputRows < -1
        || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    valueIndex = indexedValue;
    distinct = true;
    groupColumn = column;
    sorted = sortedInputRows >= 0;
    sortedRowCount = sorted ? sortedInputRows : 0;
    sortedRowIndex = 0;
    maximumRows = rowLimit;
    projectedColumns[0] = column;
    projectedColumnCount = 1;
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimJoin(
      SqlSession session,
      boolean implicit,
      int outerColumn,
      int innerColumn,
      boolean indexedOuter,
      boolean indexedInner,
      boolean uniqueInner,
      boolean preserveUnmatchedOuter,
      int[] projections,
      int projectionCount,
      long rowLimit) {
    if (active
        || session == null
        || outerColumn < 0
        || innerColumn < 0
        || projections == null
        || projectionCount <= 0
        || projectionCount > projectedColumns.length
        || rowLimit < 0) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    implicitTransaction = implicit;
    join = true;
    valueIndex = indexedOuter;
    joinOuterColumn = outerColumn;
    joinInnerColumn = innerColumn;
    joinInnerIndexed = indexedInner;
    joinInnerUnique = uniqueInner;
    leftJoin = preserveUnmatchedOuter;
    maximumRows = rowLimit;
    projectedColumnCount = projectionCount;
    for (int index = 0; index < projectionCount; index++) {
      projectedColumns[index] = projections[index];
    }
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  StatusCode claimExplain(
      SqlSession session,
      boolean analyzed,
      boolean transactionActive,
      long commitSequence,
      long actualRows,
      long[] operators,
      long[] details,
      int stepCount) {
    if (active
        || session == null
        || commitSequence < 0
        || actualRows < 0
        || operators == null
        || details == null
        || stepCount <= 0
        || stepCount > planOperators.length) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    explain = true;
    explainAnalyzed = analyzed;
    aggregateTransactionActive = transactionActive;
    explainCommitSequence = commitSequence;
    explainActualRows = actualRows;
    planStepCount = stepCount;
    planStepIndex = 0;
    projectedColumnCount = 3;
    for (int index = 0; index < stepCount; index++) {
      planOperators[index] = operators[index];
      planDetails[index] = details[index];
    }
    active = true;
    rowsReturned = 0;
    return StatusCode.OK;
  }

  boolean isOwnedBy(SqlSession session) {
    return active && owner == session;
  }

  boolean implicitTransaction() {
    return implicitTransaction;
  }

  boolean valueIndex() {
    return valueIndex;
  }

  boolean aggregate() {
    return aggregate;
  }

  boolean catalogObjectScan() {
    return catalogObjectScan;
  }

  boolean groupAggregate() {
    return groupAggregate;
  }

  boolean distinct() {
    return distinct;
  }

  boolean join() {
    return join;
  }

  boolean sorted() {
    return sorted;
  }

  boolean explain() {
    return explain;
  }

  boolean explainAnalyzed() {
    return explainAnalyzed;
  }

  int currentPlanStep() {
    return planStepIndex < planStepCount ? planStepIndex : -1;
  }

  long planOperator(int index) {
    return planOperators[index];
  }

  long planDetail(int index) {
    return planDetails[index];
  }

  void advancePlanStep() {
    planStepIndex++;
  }

  long explainActualRows() {
    return explainActualRows;
  }

  long explainCommitSequence() {
    return explainCommitSequence;
  }

  int currentSortedRow() {
    return sortedRowIndex < sortedRowCount ? sortedRowIndex : -1;
  }

  void advanceSortedRow() {
    sortedRowIndex++;
  }

  int joinOuterColumn() {
    return joinOuterColumn;
  }

  int joinInnerColumn() {
    return joinInnerColumn;
  }

  boolean joinInnerUnique() {
    return joinInnerUnique;
  }

  boolean joinInnerIndexed() {
    return joinInnerIndexed;
  }

  boolean leftJoin() {
    return leftJoin;
  }

  boolean joinMatched() {
    return joinMatched;
  }

  void matchJoin() {
    joinMatched = true;
  }

  boolean joinInnerScanActive() {
    return joinInnerScanActive;
  }

  void beginJoinInnerScan(long outerKey, long matchValue) {
    rememberJoinOuter(outerKey);
    joinMatchValue = matchValue;
    joinMatched = false;
    joinInnerScanActive = true;
  }

  void rememberJoinOuter(long outerKey) {
    joinOuterKey = outerKey;
  }

  void completeJoinInnerScan() {
    joinInnerScanActive = false;
  }

  long joinOuterKey() {
    return joinOuterKey;
  }

  long joinMatchValue() {
    return joinMatchValue;
  }

  void setJoinOuterProjectedValue(int index, long value, boolean isNull) {
    joinOuterProjectedValues[index] = value;
    if (isNull) {
      joinOuterNullMask |= 1L << index;
    } else {
      joinOuterNullMask &= ~(1L << index);
    }
  }

  long joinOuterProjectedValue(int index) {
    return joinOuterProjectedValues[index];
  }

  boolean joinOuterProjectedNull(int index) {
    return (joinOuterNullMask & 1L << index) != 0;
  }

  int groupColumn() {
    return groupColumn;
  }

  int groupAggregateColumn() {
    return groupAggregateColumn;
  }

  SqlCommandType groupAggregateType() {
    return groupAggregateType;
  }

  boolean groupInputExhausted() {
    return groupInputExhausted;
  }

  void exhaustGroupInput() {
    groupInputExhausted = true;
  }

  boolean hasGroupLookahead() {
    return groupLookahead;
  }

  long takeGroupLookahead() {
    groupLookahead = false;
    return groupLookaheadValue;
  }

  boolean groupLookaheadNull() {
    return groupLookaheadNull;
  }

  long groupLookaheadAggregateValue() {
    return groupLookaheadAggregateValue;
  }

  boolean groupLookaheadAggregateNull() {
    return groupLookaheadAggregateNull;
  }

  void setGroupLookahead(
      long value,
      boolean nullValue,
      long aggregateValue,
      boolean aggregateNull) {
    groupLookaheadValue = value;
    groupLookaheadNull = nullValue;
    groupLookaheadAggregateValue = aggregateValue;
    groupLookaheadAggregateNull = aggregateNull;
    groupLookahead = true;
  }

  boolean hasDistinctValue() {
    return distinctValueAvailable;
  }

  long distinctValue() {
    return distinctValue;
  }

  boolean distinctValueNull() {
    return distinctValueNull;
  }

  void setDistinctValue(long value, boolean nullValue) {
    distinctValue = value;
    distinctValueNull = nullValue;
    distinctValueAvailable = true;
  }

  long aggregateValue() {
    return aggregateValue;
  }

  boolean aggregateNull() {
    return aggregateNull;
  }

  boolean aggregateVarchar() {
    return aggregateVarchar;
  }

  boolean aggregateTransactionActive() {
    return aggregateTransactionActive;
  }

  long aggregateCommitSequence() {
    return aggregateCommitSequence;
  }

  boolean limitReached() {
    return rowsReturned >= maximumRows;
  }

  public int projectedColumn(int index) {
    return index >= 0 && index < projectedColumnCount ? projectedColumns[index] : -1;
  }

  public int projectedColumnCount() {
    return projectedColumnCount;
  }

  void complete() {
    active = false;
  }

  void rowReturned() {
    rowsReturned++;
  }

  public long rowsReturned() {
    return rowsReturned;
  }

  public boolean isActive() {
    return active;
  }

  private static boolean isGroupAggregate(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }
}
