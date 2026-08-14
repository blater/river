package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.storage.heap.HeapRowResult;

/** Owns grouped aggregate and distinct advancement over bound row sources. */
final class SqlGroupedExecution {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlPhysicalPlan plan;
  private final SqlActiveScanState scan;
  private final SqlSortExecution sorts;
  private final SqlExpressionEvaluator expressions;
  private final SqlBoundPredicateEvaluator predicates;
  private final RelationalScanResult row = new RelationalScanResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final long[] values = new long[2];
  private final State state = new State();
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();
  private boolean groupNull;
  private boolean aggregateInputNull;

  SqlGroupedExecution(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlPhysicalPlan physicalPlan,
      SqlActiveScanState activeScan,
      SqlSortExecution sortExecution,
      SqlExpressionEvaluator evaluator,
      SqlBoundPredicateEvaluator predicateEvaluator) {
    session = relationalSession;
    bound = statement;
    query = statement.executableQuery;
    plan = physicalPlan;
    scan = activeScan;
    sorts = sortExecution;
    expressions = evaluator;
    predicates = predicateEvaluator;
  }

  StatusCode nextAggregate(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status = nextAggregateCandidate(cursor, result);
      if (!status.isOk()) {
        result.reset();
        return status;
      }
      BoundSqlQuery.Block command = query.root();
      if (!command.hasGroupHaving()
          || !result.isNull(1)
              && expressions.matchesComparison(
                  result.valueAt(1),
                  aggregateDescriptor(),
                  command.groupHavingComparison(),
                  command.groupHavingValue(),
                  command.groupHavingTypeDescriptor())) {
        cursor.rowReturned();
        return StatusCode.OK;
      }
    }
  }

  StatusCode nextDistinct(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status = nextValue();
      if (!status.isOk()) {
        return status;
      }
      long value = values[0];
      boolean nullValue = groupNull;
      if (scan.hasDistinctValue()
          && scan.distinctValueNull() == nullValue
          && (nullValue || scan.distinctValue() == value)) {
        continue;
      }
      scan.setDistinctValue(value, nullValue);
      bound.projectedTypeDescriptors[0] = bound.table.typeDescriptor(plan.groupColumn());
      result.set(
          value,
          values,
          nullValue ? 1 : 0,
          bound.projectedTypeDescriptors,
          1);
      cursor.rowReturned();
      return StatusCode.OK;
    }
  }

  private StatusCode nextAggregateCandidate(
      SqlScanCursor cursor,
      SqlScanRowResult result) {
    if (scan.groupInputExhausted() && !scan.hasGroupLookahead()) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = beginCandidate(cursor);
    if (!status.isOk()) {
      return status;
    }
    while (true) {
      status = nextValue();
      if (status == StatusCode.CONFLICT) {
        scan.exhaustGroupInput();
        break;
      }
      if (!status.isOk()) {
        return status;
      }
      long value = values[0];
      if (groupNull != state.groupNull
          || !state.groupNull && value != state.groupValue) {
        scan.setGroupLookahead(value, groupNull, values[1], aggregateInputNull);
        break;
      }
      status = accumulate(values[1], aggregateInputNull);
      if (!status.isOk()) {
        return status;
      }
    }
    return publish(result);
  }

  private StatusCode beginCandidate(SqlScanCursor cursor) {
    long inputValue;
    boolean inputNull;
    if (scan.hasGroupLookahead()) {
      state.groupValue = scan.takeGroupLookahead();
      state.groupNull = scan.groupLookaheadNull();
      inputValue = scan.groupLookaheadAggregateValue();
      inputNull = scan.groupLookaheadAggregateNull();
    } else {
      StatusCode status = nextValue();
      if (status == StatusCode.CONFLICT) {
        scan.exhaustGroupInput();
        return StatusCode.CONFLICT;
      }
      if (!status.isOk()) {
        return status;
      }
      state.groupValue = values[0];
      state.groupNull = groupNull;
      inputValue = values[1];
      inputNull = aggregateInputNull;
    }
    state.type = plan.commandType();
    state.aggregate = initialAggregate(inputValue, inputNull);
    state.aggregateNull = !isCount(state.type) && inputNull;
    state.high = state.aggregate < 0 ? -1 : 0;
    state.count = state.aggregateNull ? 0 : 1;
    return StatusCode.OK;
  }

  private long initialAggregate(long inputValue, boolean inputNull) {
    if (state.type == SqlCommandType.GROUP_COUNT) {
      return 1;
    }
    if (state.type == SqlCommandType.GROUP_COUNT_VALUE) {
      return inputNull ? 0 : 1;
    }
    return inputValue;
  }

  private StatusCode accumulate(long inputValue, boolean inputNull) {
    if (isCount(state.type)) {
      return state.type == SqlCommandType.GROUP_COUNT || !inputNull
          ? incrementCount() : StatusCode.OK;
    }
    if (inputNull) {
      return StatusCode.OK;
    }
    if (state.aggregateNull) {
      state.aggregate = inputValue;
      state.aggregateNull = false;
      state.high = inputValue < 0 ? -1 : 0;
      state.count = 1;
      return StatusCode.OK;
    }
    if (state.type == SqlCommandType.GROUP_SUM
        || state.type == SqlCommandType.GROUP_AVG) {
      long previous = state.aggregate;
      state.aggregate += inputValue;
      state.high += (inputValue < 0 ? -1 : 0)
          + (Long.compareUnsigned(state.aggregate, previous) < 0 ? 1 : 0);
      state.count++;
      if (state.type == SqlCommandType.GROUP_SUM
          && (state.high != (state.aggregate < 0 ? -1 : 0)
              || SqlTypeDescriptor.typeId(aggregateDescriptor())
                      == SqlTypeDescriptor.TYPE_ID_DECIMAL
                  && !ExactDecimal.fits(
                      state.aggregate,
                      SqlTypeDescriptor.parameterOne(aggregateDescriptor())))) {
        return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      }
    } else if (state.type == SqlCommandType.GROUP_MIN
        && inputValue < state.aggregate) {
      state.aggregate = inputValue;
    } else if (state.type == SqlCommandType.GROUP_MAX
        && inputValue > state.aggregate) {
      state.aggregate = inputValue;
    }
    return StatusCode.OK;
  }

  private StatusCode incrementCount() {
    if (state.aggregate == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    state.aggregate++;
    return StatusCode.OK;
  }

  private StatusCode publish(SqlScanRowResult result) {
    if (state.type == SqlCommandType.GROUP_AVG && !state.aggregateNull) {
      StatusCode status = finishAverage();
      if (!status.isOk()) {
        return status;
      }
    }
    values[0] = state.groupValue;
    values[1] = state.aggregate;
    long nullMask = state.groupNull ? 1 : 0;
    if (state.aggregateNull) {
      nullMask |= 1L << 1;
    }
    bound.projectedTypeDescriptors[0] = bound.table.typeDescriptor(plan.groupColumn());
    bound.projectedTypeDescriptors[1] = aggregateDescriptor();
    result.set(
        state.groupValue,
        values,
        nullMask,
        bound.projectedTypeDescriptors,
        2);
    return StatusCode.OK;
  }

  private StatusCode nextValue() {
    if (plan.sorts()) {
      StatusCode status = sorts.nextGroupValue(values);
      if (status.isOk()) {
        long nullMask = sorts.outputNullMask();
        groupNull = (nullMask & 1) != 0;
        aggregateInputNull = plan.groupAggregateColumn() >= 0
            && (nullMask & 1L << 1) != 0;
      }
      return status;
    }
    return nextSourceValue();
  }

  private StatusCode nextSourceValue() {
    while (true) {
      StatusCode status = nextSource();
      long primaryKey = plan.valueIndex() ? indexed.key() : row.key();
      HeapRowResult source = plan.valueIndex() ? indexed.row() : row.row();
      if (status.isOk()) {
        status = validateRow(source);
      }
      if (!status.isOk()) {
        return status;
      }
      if (!predicates.matches(primaryKey, source)) {
        continue;
      }
      captureValue(primaryKey, source);
      return StatusCode.OK;
    }
  }

  private StatusCode nextSource() {
    return plan.valueIndex()
        ? session.nextValueScan(bound.table, scan.relational(), row, indexed)
        : session.nextScan(scan.relational(), row);
  }

  private void captureValue(long primaryKey, HeapRowResult source) {
    int column = plan.groupColumn();
    values[0] = column == 0
        ? primaryKey : source.getLong((column - 1) * Long.BYTES);
    groupNull = expressions.isNull(source, bound.table, column);
    int aggregateColumn = plan.groupAggregateColumn();
    aggregateInputNull = aggregateColumn >= 0
        && expressions.isNull(source, bound.table, aggregateColumn);
    values[1] = aggregateColumn < 0
        ? 0 : expressions.readColumn(primaryKey, source, aggregateColumn);
  }

  private StatusCode validateRow(HeapRowResult source) {
    return source.length() >= bound.table.fixedRowBytes()
            && source.length() <= bound.table.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static boolean isCount(SqlCommandType type) {
    return type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE;
  }

  private int aggregateDescriptor() {
    int inputDescriptor = plan.groupAggregateColumn() < 0
        ? SqlTypeDescriptor.BIGINT
        : bound.table.typeDescriptor(plan.groupAggregateColumn());
    return SqlProjectionBinder.aggregateResultDescriptor(
        plan.commandType(), inputDescriptor);
  }

  private StatusCode finishAverage() {
    int inputDescriptor = bound.table.typeDescriptor(plan.groupAggregateColumn());
    int inputScale = SqlTypeDescriptor.typeId(inputDescriptor)
            == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(inputDescriptor) : 0;
    if (!ExactDecimal.average(
        state.high,
        state.aggregate,
        state.count,
        inputScale,
        aggregateDescriptor(),
        decimal,
        wide)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    state.aggregate = decimal.value;
    state.high = decimal.value < 0 ? -1 : 0;
    return StatusCode.OK;
  }

  private static final class State {
    private SqlCommandType type;
    private boolean aggregateNull;
    private boolean groupNull;
    private long aggregate;
    private long count;
    private long high;
    private long groupValue;
  }
}
