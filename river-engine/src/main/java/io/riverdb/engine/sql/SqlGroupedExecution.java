package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;

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
  private final SqlRowProjectionEvaluator projections;
  private final RelationalScanResult row = new RelationalScanResult();
  private final ValueIndexLookupResult indexed = new ValueIndexLookupResult();
  private final long[] values = new long[TableSchema.MAXIMUM_COLUMNS];
  private final SqlProjectedRow projected = new SqlProjectedRow();
  private final SqlAggregateAccumulatorSet accumulators =
      new SqlAggregateAccumulatorSet();
  private final SqlAggregateAccumulatorSet lookaheadAccumulators =
      new SqlAggregateAccumulatorSet();
  private final SqlHavingEvaluator having;
  private ByteBuffer text;
  private char[] textCharacters;
  private byte[] currentKeyText;
  private byte[] groupKeyText;
  private byte[] lookaheadKeyText;
  private byte[] distinctKeyText;
  private int currentKeyLength;
  private int groupKeyLength;
  private int lookaheadKeyLength;
  private int distinctKeyLength;
  private final State state = new State();
  private boolean groupNull;
  private boolean havingMatched;
  private long inputNullMask;
  private HeapRowResult currentSource;

  SqlGroupedExecution(
      RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlPhysicalPlan physicalPlan,
      SqlActiveScanState activeScan,
      SqlSortExecution sortExecution,
      SqlExpressionEvaluator evaluator,
      SqlBoundPredicateEvaluator predicateEvaluator,
      SqlRowProjectionEvaluator projectionEvaluator,
      SqlTemporalContext temporal) {
    session = relationalSession;
    bound = statement;
    query = statement.executableQuery;
    plan = physicalPlan;
    scan = activeScan;
    sorts = sortExecution;
    expressions = evaluator;
    predicates = predicateEvaluator;
    projections = projectionEvaluator;
    having = new SqlHavingEvaluator(statement, evaluator, temporal);
  }

  StatusCode prepareHaving() { return having.prepare(bound.command); }

  StatusCode nextAggregate(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status = nextAggregateCandidate(cursor, result);
      if (!status.isOk()) {
        result.reset();
        return status;
      }
      BoundSqlQuery.Block command = query.root();
      status = evaluateHaving(command, result);
      accumulators.clear(bound.aggregates);
      if (!status.isOk()) {
        result.reset();
        return status;
      }
      if (havingMatched) {
        cursor.rowReturned();
        return StatusCode.OK;
      }
    }
  }

  void resetText() {
    having.reset();
    accumulators.clear(bound.aggregates);
    lookaheadAccumulators.clear(bound.aggregates);
    erase(currentKeyText);
    erase(groupKeyText);
    erase(lookaheadKeyText);
    erase(distinctKeyText);
    currentKeyLength = 0;
    groupKeyLength = 0;
    lookaheadKeyLength = 0;
    distinctKeyLength = 0;
    if (text != null) {
      for (int index = 0; index < text.capacity(); index++) text.put(index, (byte) 0);
      text.clear();
    }
    if (textCharacters != null) {
      for (int index = 0; index < textCharacters.length; index++) {
        textCharacters[index] = 0;
      }
    }
  }

  private static void erase(byte[] value) {
    if (value == null) return;
    for (int index = 0; index < value.length; index++) value[index] = 0;
  }

  private StatusCode evaluateHaving(
      BoundSqlQuery.Block command, SqlScanRowResult result) {
    StatusCode status = having.evaluate(
        bound.command,
        accumulators,
        state.groupValue,
        state.groupNull,
        textKey() ? groupKeyText : null,
        groupKeyLength);
    havingMatched = status.isOk() && having.matched();
    return status;
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
          && (nullValue || sameDistinctKey(value))) {
        finishValue();
        continue;
      }
      scan.setDistinctValue(value, nullValue);
      rememberDistinctKey();
      finishValue();
      bound.projectedTypeDescriptors[0] = groupDescriptor();
      result.set(
          value,
          values,
          nullValue ? 1 : 0,
          bound.projectedTypeDescriptors,
          1);
      StatusCode textStatus = publishKeyText(result, 0, distinctKeyText, distinctKeyLength);
      if (!textStatus.isOk()) return textStatus;
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
          || !state.groupNull && !sameGroupKey(value)) {
        lookaheadAccumulators.reset(bound.aggregates);
        status = lookaheadAccumulators.accumulate(
            bound.aggregates,
            bound.projectionPrograms,
            projected,
            currentSource,
            bound.table);
        if (status.isOk()) {
          scan.setGroupLookahead(
              values, bound.projectionPrograms.count(), inputNullMask);
          rememberLookaheadKey();
        }
        finishValue();
        if (!status.isOk()) return status;
        break;
      }
      status = accumulators.accumulate(
          bound.aggregates,
          bound.projectionPrograms,
          projected,
          currentSource,
          bound.table);
      finishValue();
      if (!status.isOk()) {
        return status;
      }
    }
    status = accumulators.finish(bound.aggregates);
    return status.isOk() ? publish(result) : status;
  }

  private StatusCode beginCandidate(SqlScanCursor cursor) {
    if (scan.hasGroupLookahead()) {
      scan.takeGroupLookahead(values, bound.projectionPrograms.count());
      inputNullMask = scan.groupLookaheadNullMask();
      restoreProjected();
      state.groupValue = values[0];
      state.groupNull = (inputNullMask & 1) != 0;
      rememberGroupKey(lookaheadKeyText, lookaheadKeyLength);
      accumulators.copyFrom(lookaheadAccumulators, bound.aggregates);
      return StatusCode.OK;
    } else {
      StatusCode status = nextValue();
      if (status == StatusCode.CONFLICT) {
        scan.exhaustGroupInput();
        return StatusCode.CONFLICT;
      }
      if (!status.isOk()) {
        return status;
      }
    }
    state.groupValue = values[0];
    state.groupNull = (inputNullMask & 1) != 0;
    rememberGroupKey(currentKeyText, currentKeyLength);
    accumulators.reset(bound.aggregates);
    StatusCode status = accumulators.accumulate(
        bound.aggregates,
        bound.projectionPrograms,
        projected,
        currentSource,
        bound.table);
    finishValue();
    return status;
  }

  private StatusCode publish(SqlScanRowResult result) {
    int invocation = bound.command.aggregateOutputInvocation(0);
    values[0] = state.groupValue;
    values[1] = accumulators.value(invocation);
    long nullMask = state.groupNull ? 1 : 0;
    if (accumulators.nullValue(invocation)) {
      nullMask |= 1L << 1;
    }
    bound.projectedTypeDescriptors[0] = groupDescriptor();
    bound.projectedTypeDescriptors[1] = bound.aggregates.resultDescriptor(invocation);
    result.set(
        state.groupValue,
        values,
        nullMask,
        bound.projectedTypeDescriptors,
        2);
    StatusCode keyStatus = publishKeyText(
        result, 0, groupKeyText, groupKeyLength);
    if (!keyStatus.isOk()) return keyStatus;
    int length = accumulators.textLength(invocation);
    if (SqlTypeDescriptor.typeId(bound.aggregates.resultDescriptor(invocation))
        != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || accumulators.nullValue(invocation)) return StatusCode.OK;
    ensureTextBuffers();
    text.clear();
    text.put(accumulators.text(), accumulators.textOffset(invocation), length);
    int characters = Utf8Text.decode(text, 0, length, textCharacters, 0);
    return characters < 0
        ? StatusCode.CORRUPTION
        : result.setTextAt(1, textCharacters, 0, characters);
  }

  private StatusCode nextValue() {
    if (plan.sorts()) {
      projected.reset(bound.projectionPrograms.count());
      StatusCode status = sorts.nextGroupValue(values, projected);
      if (status.isOk()) {
        inputNullMask = sorts.outputNullMask();
        groupNull = (inputNullMask & 1) != 0;
        restoreProjectedValues();
        currentSource = sorts.groupSource();
        status = captureCurrentKey();
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
      status = predicates.evaluate(primaryKey, source);
      if (!status.isOk()) {
        return status;
      }
      if (!predicates.matched()) {
        predicates.releaseEvaluatedRow();
        continue;
      }
      source = predicates.evaluatedRow(source);
      return captureValue(primaryKey, source);
    }
  }

  private void finishValue() {
    if (!plan.sorts()) predicates.releaseEvaluatedRow();
    currentSource = null;
  }

  private StatusCode nextSource() {
    return plan.valueIndex()
        ? session.nextValueScan(bound.table, scan.relational(), row, indexed)
        : session.nextScan(scan.relational(), row);
  }

  private StatusCode captureValue(long primaryKey, HeapRowResult source) {
    StatusCode status = projections.project(primaryKey, source, projected);
    if (!status.isOk()) return status;
    System.arraycopy(
        projected.values(), 0, values, 0, bound.projectionPrograms.count());
    inputNullMask = projected.nullMask();
    groupNull = (inputNullMask & 1) != 0;
    currentSource = source;
    return captureCurrentKey();
  }

  private StatusCode captureCurrentKey() {
    if (SqlTypeDescriptor.typeId(groupDescriptor())
        != SqlTypeDescriptor.TYPE_ID_VARCHAR || (inputNullMask & 1) != 0) {
      currentKeyLength = 0;
      return StatusCode.OK;
    }
    ensureKeyBuffers();
    int column = bound.projectionPrograms.rawColumn(0);
    if (column >= 0) {
      long handle = values[0];
      int offset = (int) (handle >>> 32);
      int length = (int) handle;
      if (currentSource == null
          || offset < bound.table.fixedRowBytes()
          || length < 0
          || length > currentKeyText.length
          || offset > currentSource.length() - length) {
        return StatusCode.CORRUPTION;
      }
      for (int index = 0; index < length; index++) {
        currentKeyText[index] = currentSource.getByte(offset + index);
      }
      currentKeyLength = length;
      return StatusCode.OK;
    }
    currentKeyLength = Utf8Text.encode(
        projected.text(0), 0, projected.textLength(0),
        Utf8Text.MAXIMUM_SCALARS, currentKeyText, 0);
    return currentKeyLength < 0 ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  private boolean sameGroupKey(long value) {
    if (!textKey()) return value == state.groupValue;
    return sameText(currentKeyText, currentKeyLength, groupKeyText, groupKeyLength);
  }

  private boolean sameDistinctKey(long value) {
    if (!textKey()) return value == scan.distinctValue();
    return sameText(currentKeyText, currentKeyLength, distinctKeyText, distinctKeyLength);
  }

  private void rememberGroupKey(byte[] source, int length) {
    if (!textKey() || source == null) return;
    ensureKeyBuffers();
    System.arraycopy(source, 0, groupKeyText, 0, length);
    groupKeyLength = length;
  }

  private void rememberLookaheadKey() {
    if (!textKey()) return;
    System.arraycopy(currentKeyText, 0, lookaheadKeyText, 0, currentKeyLength);
    lookaheadKeyLength = currentKeyLength;
  }

  private void rememberDistinctKey() {
    if (!textKey()) return;
    System.arraycopy(currentKeyText, 0, distinctKeyText, 0, currentKeyLength);
    distinctKeyLength = currentKeyLength;
  }

  private StatusCode publishKeyText(
      SqlScanRowResult result, int index, byte[] source, int length) {
    if (!textKey() || source == null || result.isNull(index)) return StatusCode.OK;
    ensureTextBuffers();
    text.clear();
    text.put(source, 0, length);
    int characters = Utf8Text.decode(text, 0, length, textCharacters, 0);
    return characters < 0 ? StatusCode.CORRUPTION
        : result.setTextAt(index, textCharacters, 0, characters);
  }

  private void ensureKeyBuffers() {
    if (currentKeyText != null) return;
    currentKeyText = new byte[Utf8Text.MAXIMUM_BYTES];
    groupKeyText = new byte[Utf8Text.MAXIMUM_BYTES];
    lookaheadKeyText = new byte[Utf8Text.MAXIMUM_BYTES];
    distinctKeyText = new byte[Utf8Text.MAXIMUM_BYTES];
  }

  private boolean textKey() {
    return SqlTypeDescriptor.typeId(groupDescriptor())
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private void ensureTextBuffers() {
    if (text != null) return;
    text = ByteBuffer.allocateDirect(Utf8Text.MAXIMUM_BYTES);
    textCharacters = new char[Utf8Text.MAXIMUM_SCALARS * 2];
  }

  private static boolean sameText(
      byte[] left, int leftLength, byte[] right, int rightLength) {
    if (leftLength != rightLength) return false;
    for (int index = 0; index < leftLength; index++) {
      if (left[index] != right[index]) return false;
    }
    return true;
  }

  private void restoreProjected() {
    projected.reset(bound.projectionPrograms.count());
    restoreProjectedValues();
  }

  private void restoreProjectedValues() {
    for (int lane = 0; lane < bound.projectionPrograms.count(); lane++) {
      if ((inputNullMask & 1L << lane) != 0) projected.setNull(lane);
      else projected.setValue(lane, values[lane]);
    }
  }

  private StatusCode validateRow(HeapRowResult source) {
    return source.length() >= bound.table.fixedRowBytes()
            && source.length() <= bound.table.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private int groupDescriptor() {
    return plan.groupColumn() == SqlBoundProjectionPrograms.COMPUTED_PROJECTION
        ? bound.projectionPrograms.resultDescriptor(0)
        : bound.table.typeDescriptor(plan.groupColumn());
  }

  private static final class State {
    private boolean groupNull;
    private long groupValue;
  }
}
