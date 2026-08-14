package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Owns point aggregate scan, accumulation, cleanup, and result publication. */
final class SqlPointAggregateExecution {
  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final BoundSqlQuery query;
  private final SqlExpressionEvaluator expressions;
  private final SqlBoundPredicateEvaluator predicates;
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult row = new RelationalScanResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final ByteBuffer text = ByteBuffer.allocateDirect(Utf8Text.MAXIMUM_BYTES);
  private final long[] projectedValues = new long[1];
  private final State state = new State();
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();

  SqlPointAggregateExecution(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlBoundPredicateEvaluator predicateEvaluator) {
    session = relationalSession;
    bound = statement;
    query = statement.executableQuery;
    expressions = evaluator;
    predicates = predicateEvaluator;
  }

  boolean accepts(SqlCommandType type) {
    return type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || type == SqlCommandType.SUM
        || type == SqlCommandType.AVG
        || type == SqlCommandType.MIN
        || type == SqlCommandType.MAX;
  }

  StatusCode execute(SqlExecutionResult result) {
    StatusCode status = prepare();
    if (!status.isOk()) {
      return status;
    }
    status = beginScan();
    boolean active = status.isOk();
    while (status.isOk()) {
      status = nextInput();
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = accumulateInput();
      }
    }
    status = close(status, active);
    if (status.isOk() && state.sumOverflow()) {
      status = StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    if (status.isOk() && state.average) {
      status = finishAverage();
    }
    int descriptor = aggregateDescriptor();
    if (status.isOk()
        && state.sum
        && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        && !ExactDecimal.fits(
            state.aggregate, SqlTypeDescriptor.parameterOne(descriptor))) {
      status = StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    return status.isOk() ? publish(result) : status;
  }

  private StatusCode prepare() {
    BoundSqlQuery.Block command = query.root();
    state.reset(
        command.type(),
        bound.projectedColumns[0],
        bound.predicateCount > 0,
        bound.accessPredicate >= 0,
        accessEquality(command));
    configureAccess();
    if ((state.indexed || state.primaryKeyRange)
        && state.equality
        && accessValue(command) == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    configureRange(command);
    state.text = state.value && bound.table.isVarchar(state.column);
    return StatusCode.OK;
  }

  private void configureAccess() {
    if (!state.bounded) {
      state.indexed = false;
      state.primaryKeyRange = false;
      return;
    }
    state.primaryKeyRange = bound.predicateColumn == 0;
    state.indexed = bound.predicateColumn > 0
        && bound.table.hasIndexOn(bound.predicateColumn)
        && !bound.table.isVarchar(bound.predicateColumn);
  }

  private void configureRange(BoundSqlQuery.Block command) {
    if (!state.bounded) {
      state.lower = 0;
      state.upper = 0;
      return;
    }
    if (state.equality) {
      state.lower = accessValue(command);
      state.upper = state.lower + 1;
      return;
    }
    state.lower = command.predicateLowerInclusive(bound.accessPredicate);
    state.upper = command.predicateUpperExclusive(bound.accessPredicate);
  }

  private StatusCode beginScan() {
    if (state.indexed) {
      return session.beginValueScan(
          bound.table,
          bound.predicateColumn,
          state.lower,
          state.upper,
          cursor);
    }
    return state.primaryKeyRange
        ? session.beginScan(bound.table, state.lower, state.upper, cursor)
        : session.beginScan(bound.table, cursor);
  }

  private StatusCode nextInput() {
    StatusCode status;
    if (state.indexed) {
      status = session.nextValueScan(bound.table, cursor, row, indexed);
      state.source = indexed.row();
      state.primaryKey = indexed.key();
    } else {
      status = session.nextScan(cursor, row);
      state.source = row.row();
      state.primaryKey = row.key();
    }
    return status;
  }

  private StatusCode accumulateInput() {
    HeapRowResult source = state.source;
    StatusCode status = StatusCode.OK;
    if (state.filtered || state.value || state.countValue) {
      status = validateRow(source);
    }
    if (!status.isOk()
        || state.filtered && !predicates.matches(state.primaryKey, source)) {
      return status;
    }
    return state.value ? accumulateValue(source) : accumulateCount(source);
  }

  private StatusCode accumulateValue(HeapRowResult source) {
    int column = state.column;
    if (expressions.isNull(source, bound.table, column)) {
      return StatusCode.OK;
    }
    long value = expressions.readColumn(state.primaryKey, source, column);
    if (state.text) {
      accumulateText(source, column);
    } else if (state.sum || state.average) {
      long previous = state.aggregate;
      state.aggregate += value;
      state.high += (value < 0 ? -1 : 0)
          + (Long.compareUnsigned(state.aggregate, previous) < 0 ? 1 : 0);
      state.count++;
    } else if (!state.present
        || state.minimum && value < state.aggregate
        || !state.minimum && value > state.aggregate) {
      state.aggregate = value;
    }
    state.present = true;
    return StatusCode.OK;
  }

  private void accumulateText(HeapRowResult source, int column) {
    long handle = source.getLong((column - 1) * Long.BYTES);
    int offset = (int) (handle >>> 32);
    int length = (int) handle;
    int comparison = state.present
        ? expressions.compareText(source, offset, length, text, state.textLength)
        : 0;
    if (!state.present
        || state.minimum && comparison < 0
        || !state.minimum && comparison > 0) {
      text.clear();
      for (int index = 0; index < length; index++) {
        text.put(source.getByte(offset + index));
      }
      state.textLength = length;
    }
  }

  private StatusCode accumulateCount(HeapRowResult source) {
    if (state.countValue
        && expressions.isNull(source, bound.table, state.column)) {
      return StatusCode.OK;
    }
    if (state.aggregate == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    state.aggregate++;
    state.present = true;
    return StatusCode.OK;
  }

  private StatusCode close(StatusCode bodyStatus, boolean active) {
    if (!active) {
      return bodyStatus;
    }
    StatusCode close = session.closeScan(cursor);
    if (close.isOk()) {
      cursor.reset();
    }
    return bodyStatus.isOk() ? close : bodyStatus;
  }

  private StatusCode publish(SqlExecutionResult result) {
    projectedValues[0] = state.aggregate;
    bound.projectedTypeDescriptors[0] = aggregateDescriptor();
    result.setProjection(
        0,
        projectedValues,
        state.value && !state.present ? 1 : 0,
        bound.projectedTypeDescriptors,
        1,
        0);
    return state.text && state.present
        ? result.setUtf8At(0, text, 0, state.textLength)
        : StatusCode.OK;
  }

  private boolean accessEquality(BoundSqlQuery.Block command) {
    return bound.accessPredicate >= 0
        && command.isEqualityPredicate(bound.accessPredicate);
  }

  private long accessValue(BoundSqlQuery.Block command) {
    return bound.accessValue;
  }

  private StatusCode validateRow(HeapRowResult source) {
    return source.length() >= bound.table.fixedRowBytes()
            && source.length() <= bound.table.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private int aggregateDescriptor() {
    int inputDescriptor = bound.projectedColumnCount > 0
        ? bound.table.typeDescriptor(bound.projectedColumns[0])
        : SqlTypeDescriptor.BIGINT;
    return SqlProjectionBinder.aggregateResultDescriptor(
        query.root().type(), inputDescriptor);
  }

  private StatusCode finishAverage() {
    if (!state.present) {
      return StatusCode.OK;
    }
    int inputDescriptor = bound.table.typeDescriptor(state.column);
    int inputScale = SqlTypeDescriptor.typeId(inputDescriptor)
            == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(inputDescriptor) : 0;
    return ExactDecimal.average(
        state.high,
        state.aggregate,
        state.count,
        inputScale,
        aggregateDescriptor(),
        decimal,
        wide)
        ? setAverage(decimal.value) : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
  }

  private StatusCode setAverage(long value) {
    state.aggregate = value;
    state.high = value < 0 ? -1 : 0;
    return StatusCode.OK;
  }

  private static final class State {
    private HeapRowResult source;
    private boolean bounded;
    private boolean average;
    private boolean countValue;
    private boolean equality;
    private boolean filtered;
    private boolean indexed;
    private boolean minimum;
    private boolean present;
    private boolean primaryKeyRange;
    private boolean sum;
    private boolean text;
    private boolean value;
    private int column;
    private int textLength;
    private long aggregate;
    private long count;
    private long high;
    private long lower;
    private long primaryKey;
    private long upper;

    private void reset(
        SqlCommandType type,
        int projectedColumn,
        boolean hasFilters,
        boolean hasBounds,
        boolean accessIsEquality) {
      source = null;
      average = type == SqlCommandType.AVG;
      bounded = hasBounds;
      countValue = type == SqlCommandType.COUNT_VALUE;
      equality = accessIsEquality;
      filtered = hasFilters;
      indexed = false;
      minimum = type == SqlCommandType.MIN;
      present = false;
      primaryKeyRange = false;
      sum = type == SqlCommandType.SUM;
      text = false;
      value = sum || average || minimum || type == SqlCommandType.MAX;
      column = value || countValue ? projectedColumn : -1;
      textLength = 0;
      aggregate = 0;
      count = 0;
      high = 0;
      lower = 0;
      primaryKey = 0;
      upper = 0;
    }

    private boolean sumOverflow() {
      return sum && present && high != (aggregate < 0 ? -1 : 0);
    }
  }
}
