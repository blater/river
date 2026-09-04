package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableSchema;
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
  private final SqlRowProjectionEvaluator projections;
  private final SqlRetainedArrayAllocator allocator = SqlRetainedArrayAllocator.STANDARD;
  private final SqlProjectedRow projected = new SqlProjectedRow();
  private final SqlAggregateAccumulatorSet accumulators;
  private final SqlHavingEvaluator having;
  private final RelationalScanCursor cursor = new RelationalScanCursor();
  private final RelationalScanResult row = new RelationalScanResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private ByteBuffer text;
  private final long[] projectedValues = new long[1];
  private final State state = new State();
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();

  SqlPointAggregateExecution(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlExpressionEvaluator evaluator,
      SqlBoundPredicateEvaluator predicateEvaluator,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    session = relationalSession;
    bound = statement;
    query = statement.executableQuery;
    expressions = evaluator;
    predicates = predicateEvaluator;
    projections = projectionEvaluator;
    accumulators = new SqlAggregateAccumulatorSet(shapeBudget);
    having = new SqlHavingEvaluator(statement, evaluator, temporal, shapeBudget);
  }

  boolean accepts(SqlCommandType type) {
    return type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || type == SqlCommandType.COUNT_DISTINCT
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
    if (status.isOk()) status = accumulators.finish(bound.aggregates);
    if (status.isOk()) {
      status = having.evaluate(
          bound.command,
          accumulators,
          0,
          true,
          null,
          0);
    }
    if (!status.isOk() || !having.matched()) {
      status = accumulators.clear(bound.aggregates);
      eraseText();
      return status;
    }
    status = publish(result);
    StatusCode cleared = accumulators.clear(bound.aggregates);
    if (status.isOk()) status = cleared;
    eraseText();
    return status;
  }

  private StatusCode prepare() {
    BoundSqlQuery.Block command = query.root();
    StatusCode status = having.prepare(bound.command, accumulators, bound.aggregates);
    if (!status.isOk()) return status;
    status = prepareText();
    if (!status.isOk()) return status;
    state.reset(
        command.type(),
        bound.projectedColumns[0],
        bound.predicateCount > 0,
        bound.accessPredicate >= 0,
        accessEquality());
    configureAccess();
    configureRange(command);
    state.computed = bound.projectedColumnCount > 0
        && bound.projectedColumns[0]
            == SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
    state.text = state.value && !state.computed && bound.table.isVarchar(state.column);
    return accumulators.reset(bound.aggregates);
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
      state.lower = bound.accessValue;
      state.upper = 0;
      return;
    }
    state.lower = bound.accessLowerInclusive;
    state.upper = bound.accessUpperExclusive;
  }

  private StatusCode beginScan() {
    if (state.indexed) {
      return state.equality
          ? session.beginExactValueScan(
              bound.table, bound.predicateColumn, state.lower, cursor)
          : session.beginValueScan(
              bound.table,
              bound.predicateColumn,
              state.lower,
              state.upper,
              cursor);
    }
    return state.primaryKeyRange
        ? state.equality
            ? session.beginExactScan(bound.table, state.lower, cursor)
            : session.beginScan(bound.table, state.lower, state.upper, cursor)
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
    if (state.filtered || state.value || state.countValue
        || bound.projectionPrograms.count() > 0) {
      status = validateRow(source);
    }
    if (!status.isOk()) {
      return status;
    }
    status = predicates.evaluate(state.primaryKey, source);
    if (!status.isOk()) return status;
    if (!predicates.matched()) {
      predicates.releaseEvaluatedRow();
      return StatusCode.OK;
    }
    source = predicates.evaluatedRow(source);
    status = projections.project(state.primaryKey, source, projected);
    if (status.isOk()) {
      status = accumulators.accumulate(
          bound.aggregates,
          bound.projectionPrograms,
          projected,
          source,
          bound.table);
    }
    predicates.releaseEvaluatedRow();
    return status;
  }

  private StatusCode accumulateComputed(HeapRowResult source) {
    StatusCode status = projections.project(state.primaryKey, source, projected);
    if (!status.isOk() || (projected.nullMask() & 1) != 0) return status;
    return state.countValue
        ? incrementCount() : accumulatePrimitive(projected.value(0));
  }

  private StatusCode accumulateValue(HeapRowResult source) {
    int column = state.column;
    if (expressions.isNull(source, bound.table, column)) {
      return StatusCode.OK;
    }
    long value = expressions.readColumn(state.primaryKey, source, bound.table, column);
    if (state.text) {
      accumulateText(source, column);
      state.present = true;
      return StatusCode.OK;
    }
    return accumulatePrimitive(value);
  }

  private StatusCode accumulatePrimitive(long value) {
    if (state.sum || state.average) {
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
    long handle = source.getLong(bound.table.valueOffset(column));
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
    return incrementCount();
  }

  private StatusCode incrementCount() {
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

  boolean hasResources() {
    return cursor.isActive();
  }

  void finishStatement() {
    having.reset();
  }

  StatusCode closeResources() {
    having.reset();
    if (!cursor.isActive()) return StatusCode.OK;
    StatusCode status = session.closeScan(cursor);
    if (status.isOk()) cursor.reset();
    return status;
  }

  private StatusCode publish(SqlExecutionResult result) {
    int invocation = bound.command.aggregateOutputInvocation(0);
    projectedValues[0] = accumulators.value(invocation);
    bound.projectedTypeDescriptors[0] = bound.aggregates.resultDescriptor(invocation);
    result.setProjection(
        0,
        projectedValues,
        accumulators.nullValue(invocation) ? 1 : 0,
        bound.projectedTypeDescriptors,
        1,
        0);
    int length = accumulators.textLength(invocation);
    if (SqlTypeDescriptor.typeId(bound.aggregates.resultDescriptor(invocation))
        != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || accumulators.nullValue(invocation)) return StatusCode.OK;
    if (text == null) return StatusCode.INVARIANT_BROKEN;
    text.clear();
    text.put(
        accumulators.text(), accumulators.textOffset(invocation), length);
    return result.setUtf8At(0, text, 0, length);
  }

  private void eraseText() {
    if (text == null) return;
    for (int index = 0; index < text.capacity(); index++) text.put(index, (byte) 0);
    text.clear();
  }

  private StatusCode prepareText() {
    if (text != null || bound.aggregates.count() == 0
        || SqlTypeDescriptor.typeId(bound.aggregates.resultDescriptor(0))
            != SqlTypeDescriptor.TYPE_ID_VARCHAR) return StatusCode.OK;
    try {
      ByteBuffer next = allocator.direct(TableSchema.MAXIMUM_ROW_BYTES);
      text = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private boolean accessEquality() {
    return bound.accessPredicate >= 0
        && bound.accessComparison == io.riverdb.sql.SqlComparison.EQUAL;
  }

  private StatusCode validateRow(HeapRowResult source) {
    return source.length() >= bound.table.fixedRowBytes()
            && source.length() <= bound.table.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private int aggregateDescriptor() {
    int inputDescriptor = bound.projectedColumnCount > 0
        ? bound.projectedTypeDescriptors[0]
        : SqlTypeDescriptor.BIGINT;
    return SqlProjectionBinder.aggregateResultDescriptor(
        query.root().type(), inputDescriptor);
  }

  private StatusCode finishAverage() {
    if (!state.present) {
      return StatusCode.OK;
    }
    int inputDescriptor = bound.projectedTypeDescriptors[0];
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
    private boolean computed;
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
      computed = false;
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
