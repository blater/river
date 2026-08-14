package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.storage.heap.HeapRowResult;

/** Opens and advances the physical join operator for one SQL session. */
final class SqlJoinExecution {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlPhysicalPlan plan;
  private final SqlActiveScanState scan;
  private final SqlExpressionEvaluator expressions;
  private final SqlBoundPredicateEvaluator predicates;
  private final long[] projectedValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final HeapRowResult fetched = new HeapRowResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final ValueIndexLookupResult outerIndexed = new ValueIndexLookupResult();
  private final RelationalScanResult row = new RelationalScanResult();

  SqlJoinExecution(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlPhysicalPlan physicalPlan,
      SqlActiveScanState activeScan,
      SqlExpressionEvaluator evaluator,
      SqlBoundPredicateEvaluator predicateEvaluator) {
    session = relationalSession;
    bound = statement;
    plan = physicalPlan;
    scan = activeScan;
    expressions = evaluator;
    predicates = predicateEvaluator;
  }

  StatusCode begin() {
    BoundSqlQuery.Block command = bound.executableQuery.root();
    plan.setFilterCount(bound.predicateCount);
    boolean predicate = bound.accessPredicate >= 0;
    boolean equality = predicate
        && command.isEqualityPredicate(bound.accessPredicate);
    boolean indexedOuter = predicate
        && bound.predicateColumn > 0
        && bound.table.hasIndexOn(bound.predicateColumn);
    boolean primaryRange = predicate && bound.predicateColumn == 0;
    plan.setAccessColumn(
        indexedOuter ? bound.predicateColumn : primaryRange ? 0 : -1);
    if (predicate && equality && (indexedOuter || primaryRange)
        && bound.accessValue == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long lower = !predicate ? 0
        : equality ? bound.accessValue
            : bound.accessLowerInclusive;
    long upper = !predicate ? 0
        : equality ? lower + 1
            : bound.accessUpperExclusive;
    StatusCode status = openOuter(indexedOuter, primaryRange, lower, upper);
    if (status.isOk()) {
      configureResult(command);
    }
    return status;
  }

  StatusCode next(SqlScanCursor cursor, SqlScanRowResult result) {
    if (scan.joinInnerScanActive()) {
      StatusCode status = nextInner(cursor, result);
      if (status != StatusCode.CONFLICT) {
        return status;
      }
    }
    return nextOuter(cursor, result);
  }

  private StatusCode openOuter(
      boolean indexedOuter, boolean primaryRange, long lower, long upper) {
    if (indexedOuter) {
      return session.beginValueScan(
          bound.table,
          bound.predicateColumn,
          lower,
          upper,
          scan.relational());
    }
    return primaryRange
        ? session.beginScan(bound.table, lower, upper, scan.relational())
        : session.beginScan(bound.table, scan.relational());
  }

  private void configureResult(BoundSqlQuery.Block command) {
    int innerColumn = bound.joinInnerColumn;
    int outerDescriptor = bound.table.typeDescriptor(bound.joinOuterColumn);
    int innerDescriptor = bound.joinTable.typeDescriptor(innerColumn);
    boolean indexCompatible = outerDescriptor == innerDescriptor
        || SqlTypeDescriptor.typeId(outerDescriptor)
            != SqlTypeDescriptor.TYPE_ID_DECIMAL;
    plan.setJoin(
        bound.joinOuterColumn,
        innerColumn,
        command.isLeftJoin(),
        indexCompatible
            && (innerColumn == 0 || bound.joinTable.hasIndexOn(innerColumn)),
        indexCompatible
            && (innerColumn == 0 || bound.joinTable.hasUniqueIndexOn(innerColumn)));
    for (int index = 0; index < bound.projectedColumnCount; index++) {
      int projection = bound.projectedColumns[index];
      plan.setResultColumn(
          index,
          projection,
          projection >= 0
              ? bound.table.typeDescriptor(projection)
              : bound.joinTable.typeDescriptor(-projection - 1),
          command.columnOutputName(index));
    }
  }

  private StatusCode nextInner(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status;
      HeapRowResult innerRow;
      long innerKey;
      if (plan.joinInnerIndexed()) {
        status = session.nextNonUniqueValueLookup(
            bound.joinTable, scan.joinInnerRelational(), indexed);
        innerRow = indexed.row();
        innerKey = indexed.key();
      } else {
        status = session.nextScan(scan.joinInnerRelational(), row);
        innerRow = row.row();
        innerKey = row.key();
      }
      if (status == StatusCode.CONFLICT) {
        return completeInner(cursor, result);
      }
      if (!status.isOk()) {
        return status;
      }
      status = validate(innerRow, bound.joinTable.fixedRowBytes(),
          bound.joinTable.maximumRowBytes());
      if (!status.isOk()) {
        return status;
      }
      if (!matchesJoinValue(innerKey, innerRow)) {
        continue;
      }
      scan.matchJoin();
      if (predicates.matchesJoin(innerKey, innerRow, false)) {
        return setRowFromRememberedOuter(cursor, result, innerKey, innerRow);
      }
    }
  }

  private StatusCode completeInner(
      SqlScanCursor cursor, SqlScanRowResult result) {
    boolean unmatched = plan.leftJoin() && !scan.joinMatched();
    StatusCode status = session.closeScan(scan.joinInnerRelational());
    if (status.isOk()) {
      scan.completeJoinInnerScan();
      status = scan.joinInnerRelational().reset();
    }
    if (!status.isOk()) {
      return status;
    }
    return unmatched && predicates.matchesNullExtendedJoin()
        ? setUnmatchedRow(cursor, result) : StatusCode.CONFLICT;
  }

  private boolean matchesJoinValue(long innerKey, HeapRowResult innerRow) {
    if (plan.joinInnerIndexed()) {
      return true;
    }
    int innerColumn = plan.joinInnerColumn();
    return !expressions.isNull(innerRow, bound.joinTable, innerColumn)
        && expressions.compareExact(
            expressions.readColumn(innerKey, innerRow, innerColumn),
            bound.joinTable.typeDescriptor(innerColumn),
            scan.joinMatchValue(),
            bound.table.typeDescriptor(plan.joinOuterColumn())) == 0;
  }

  private StatusCode nextOuter(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status = nextMatchingOuter();
      if (!status.isOk()) {
        return status;
      }
      long outerKey = plan.valueIndex() ? outerIndexed.key() : row.key();
      HeapRowResult outerRow = plan.valueIndex() ? outerIndexed.row() : row.row();
      status = joinOuter(cursor, result, outerKey, outerRow);
      if (status != StatusCode.CONFLICT) {
        return status;
      }
    }
  }

  private StatusCode nextMatchingOuter() {
    while (true) {
      StatusCode status = plan.valueIndex()
          ? session.nextValueScan(bound.table, scan.relational(), row, outerIndexed)
          : session.nextScan(scan.relational(), row);
      if (!status.isOk()) {
        return status;
      }
      long outerKey = plan.valueIndex() ? outerIndexed.key() : row.key();
      HeapRowResult outerRow = plan.valueIndex() ? outerIndexed.row() : row.row();
      status = validate(
          outerRow, bound.table.fixedRowBytes(), bound.table.maximumRowBytes());
      if (!status.isOk() || predicates.matchesJoin(outerKey, outerRow, true)) {
        return status;
      }
    }
  }

  private StatusCode joinOuter(
      SqlScanCursor cursor,
      SqlScanRowResult result,
      long outerKey,
      HeapRowResult outerRow) {
    if (plan.leftJoin() || !plan.joinInnerUnique()) {
      rememberOuter(cursor, outerKey, outerRow);
    }
    if (expressions.isNull(outerRow, bound.table, plan.joinOuterColumn())) {
      return unmatchedRow(cursor, result);
    }
    long joinValue = expressions.readColumn(
        outerKey, outerRow, plan.joinOuterColumn());
    return plan.joinInnerUnique()
        ? joinUnique(cursor, result, outerKey, outerRow, joinValue)
        : joinNonUnique(cursor, result, outerKey, joinValue);
  }

  private StatusCode joinUnique(
      SqlScanCursor cursor,
      SqlScanRowResult result,
      long outerKey,
      HeapRowResult outerRow,
      long joinValue) {
    StatusCode status = fetchUnique(joinValue);
    if (status == StatusCode.CONFLICT
        || status == StatusCode.INVALID_EXTERNAL_INPUT) {
      return unmatchedRow(cursor, result);
    }
    if (!status.isOk()) {
      return status;
    }
    long innerKey = plan.joinInnerColumn() == 0 ? joinValue : indexed.key();
    HeapRowResult innerRow = plan.joinInnerColumn() == 0 ? fetched : indexed.row();
    status = validate(innerRow, bound.joinTable.fixedRowBytes(),
        bound.joinTable.maximumRowBytes());
    if (!status.isOk()) {
      return status;
    }
    return predicates.matchesJoin(innerKey, innerRow, false)
        ? setRow(cursor, result, outerKey, outerRow, innerKey, innerRow)
        : StatusCode.CONFLICT;
  }

  private StatusCode joinNonUnique(
      SqlScanCursor cursor,
      SqlScanRowResult result,
      long outerKey,
      long joinValue) {
    StatusCode status = beginInner(outerKey, joinValue);
    if (status == StatusCode.CONFLICT
        || status == StatusCode.INVALID_EXTERNAL_INPUT) {
      return unmatchedRow(cursor, result);
    }
    return status.isOk() ? nextInner(cursor, result) : status;
  }

  private StatusCode unmatchedRow(
      SqlScanCursor cursor, SqlScanRowResult result) {
    return plan.leftJoin() && predicates.matchesNullExtendedJoin()
        ? setUnmatchedRow(cursor, result) : StatusCode.CONFLICT;
  }

  private void rememberOuter(
      SqlScanCursor cursor, long outerKey, HeapRowResult outerRow) {
    scan.rememberJoinOuter(outerKey);
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int projection = cursor.projectedColumn(index);
      if (projection >= 0) {
        scan.setJoinOuterProjectedValue(
            index,
            expressions.readColumn(outerKey, outerRow, projection),
            expressions.isNull(outerRow, bound.table, projection));
      }
    }
  }

  private StatusCode beginInner(long outerKey, long joinValue) {
    StatusCode status = plan.joinInnerIndexed()
        ? joinValue == Long.MAX_VALUE
            ? StatusCode.INVALID_EXTERNAL_INPUT
            : session.beginNonUniqueValueLookup(
                bound.joinTable,
                plan.joinInnerColumn(),
                joinValue,
                scan.joinInnerRelational())
        : session.beginScan(bound.joinTable, scan.joinInnerRelational());
    if (status.isOk()) {
      scan.beginJoinInnerScan(outerKey, joinValue);
    }
    return status;
  }

  private StatusCode fetchUnique(long joinValue) {
    return plan.joinInnerColumn() == 0
        ? session.fetch(bound.joinTable, joinValue, fetched)
        : session.fetchByUniqueValue(
            bound.joinTable, plan.joinInnerColumn(), joinValue, indexed);
  }

  private StatusCode setRowFromRememberedOuter(
      SqlScanCursor cursor,
      SqlScanRowResult result,
      long innerKey,
      HeapRowResult innerRow) {
    long nullMask = 0;
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int projection = cursor.projectedColumn(index);
      if (projection >= 0) {
        projectedValues[index] = scan.joinOuterProjectedValue(index);
        if (scan.joinOuterProjectedNull(index)) {
          nullMask |= 1L << index;
        }
      } else {
        nullMask = projectInner(index, projection, innerKey, innerRow, nullMask);
      }
    }
    return setResult(cursor, result, scan.joinOuterKey(), nullMask);
  }

  private StatusCode setRow(
      SqlScanCursor cursor,
      SqlScanRowResult result,
      long outerKey,
      HeapRowResult outerRow,
      long innerKey,
      HeapRowResult innerRow) {
    long nullMask = 0;
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      int projection = cursor.projectedColumn(index);
      if (projection >= 0) {
        projectedValues[index] = expressions.readColumn(outerKey, outerRow, projection);
        if (expressions.isNull(outerRow, bound.table, projection)) {
          nullMask |= 1L << index;
        }
      } else {
        nullMask = projectInner(index, projection, innerKey, innerRow, nullMask);
      }
    }
    return setResult(cursor, result, outerKey, nullMask);
  }

  private long projectInner(
      int index,
      int projection,
      long innerKey,
      HeapRowResult innerRow,
      long nullMask) {
    int column = -projection - 1;
    projectedValues[index] = expressions.readColumn(innerKey, innerRow, column);
    return expressions.isNull(innerRow, bound.joinTable, column)
        ? nullMask | 1L << index : nullMask;
  }

  private StatusCode setResult(
      SqlScanCursor cursor,
      SqlScanRowResult result,
      long outerKey,
      long nullMask) {
    result.set(
        outerKey,
        projectedValues,
        nullMask,
        projectionTypes(cursor),
        cursor.projectedColumnCount());
    cursor.rowReturned();
    return StatusCode.OK;
  }

  private StatusCode setUnmatchedRow(
      SqlScanCursor cursor, SqlScanRowResult result) {
    long nullMask = 0;
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      if (cursor.projectedColumn(index) >= 0) {
        projectedValues[index] = scan.joinOuterProjectedValue(index);
        if (scan.joinOuterProjectedNull(index)) {
          nullMask |= 1L << index;
        }
      } else {
        projectedValues[index] = 0;
        nullMask |= 1L << index;
      }
    }
    result.set(
        scan.joinOuterKey(),
        projectedValues,
        nullMask,
        projectionTypes(cursor),
        cursor.projectedColumnCount());
    cursor.rowReturned();
    return StatusCode.OK;
  }

  private int[] projectionTypes(SqlScanCursor cursor) {
    for (int index = 0; index < cursor.projectedColumnCount(); index++) {
      bound.projectedTypeDescriptors[index] = plan.resultType(index);
    }
    return bound.projectedTypeDescriptors;
  }

  private static StatusCode validate(
      HeapRowResult source, int fixedBytes, int maximumBytes) {
    return source.length() >= fixedBytes && source.length() <= maximumBytes
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
