package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.sql.SqlGroupExpressions;
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
  private final SqlGroupedBuffers buffers = new SqlGroupedBuffers();
  private final SqlLegacyGroupKeys groupKeys = new SqlLegacyGroupKeys();
  private long[] values = buffers.values;
  private long[] highs = buffers.highs;
  private final SqlProjectedRow projected = new SqlProjectedRow();
  private final SqlAggregateAccumulatorSet accumulators;
  private final SqlAggregateAccumulatorSet lookaheadAccumulators;
  private final SqlHavingEvaluator having;
  private ByteBuffer text;
  private char[] textCharacters;
  private boolean groupNull;
  private boolean havingMatched;
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
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    session = relationalSession;
    bound = statement;
    query = statement.executableQuery;
    plan = physicalPlan;
    scan = activeScan;
    sorts = sortExecution;
    expressions = evaluator;
    predicates = predicateEvaluator;
    projections = projectionEvaluator;
    accumulators = new SqlAggregateAccumulatorSet(shapeBudget);
    lookaheadAccumulators = new SqlAggregateAccumulatorSet(shapeBudget);
    having = new SqlHavingEvaluator(statement, evaluator, temporal, shapeBudget);
  }

  StatusCode prepareHaving() {
    int required = Math.max(
        bound.projectedColumnCount, bound.projectionPrograms.count());
    StatusCode status = buffers.prepare(required, requiresTextBuffers());
    if (!status.isOk()) return status;
    values = buffers.values;
    highs = buffers.highs;
    text = buffers.text;
    textCharacters = buffers.characters;
    status = projected.reserve(required);
    if (!status.isOk()) return status;
    for (int projection = 0; projection < bound.projectionPrograms.count(); projection++) {
      if (SqlTypeDescriptor.typeId(bound.projectionPrograms.resultDescriptor(projection))
          == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        status = projected.prepareText(projection);
        if (!status.isOk()) return status;
      }
    }
    status = scan.groupLookahead().reserve(required);
    if (status.isOk() && (SqlBinder.isGroupAggregate(query.root().type())
        || query.root().type() == io.riverdb.sql.SqlCommandType.DISTINCT_SCAN)) {
      int keys = SqlBinder.isGroupAggregate(query.root().type())
          ? bound.command.groupExpressionCount() : bound.projectedColumnCount;
      status = groupKeys.prepare(bound, keys);
    }
    return status.isOk() ? having.prepare(
        bound.command, accumulators, lookaheadAccumulators, bound.aggregates) : status;
  }

  StatusCode nextAggregate(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status = nextAggregateCandidate(cursor, result);
      if (!status.isOk()) {
        result.reset();
        return status;
      }
      BoundSqlQuery.Block command = query.root();
      status = evaluateHaving(command, result);
      StatusCode cleared = accumulators.clear(bound.aggregates);
      if (status.isOk()) status = cleared;
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

  StatusCode resetText() {
    having.reset();
    StatusCode status = accumulators.clear(bound.aggregates);
    StatusCode lookahead = lookaheadAccumulators.clear(bound.aggregates);
    if (status.isOk()) status = lookahead;
    if (text != null) {
      for (int index = 0; index < text.capacity(); index++) text.put(index, (byte) 0);
      text.clear();
    }
    if (textCharacters != null) {
      for (int index = 0; index < textCharacters.length; index++) {
        textCharacters[index] = 0;
      }
    }
    return status;
  }

  StatusCode closeResources() {
    StatusCode status = resetText();
    StatusCode closed = accumulators.closeDistinct();
    if (status.isOk()) status = closed;
    StatusCode lookahead = lookaheadAccumulators.closeDistinct();
    return status.isOk() ? lookahead : status;
  }

  private StatusCode evaluateHaving(
      BoundSqlQuery.Block command, SqlScanRowResult result) {
    StatusCode status = having.evaluate(
        bound.command,
        accumulators,
        groupKeys.group(),
        groupKeys.count());
    havingMatched = status.isOk() && having.matched();
    return status;
  }

  StatusCode nextDistinct(SqlScanCursor cursor, SqlScanRowResult result) {
    while (true) {
      StatusCode status = nextValue();
      if (!status.isOk()) {
        return status;
      }
      if (scan.hasDistinctValue() && groupKeys.sameCurrentGroup()) {
        finishValue();
        continue;
      }
      StatusCode remembered = groupKeys.beginGroup();
      if (!remembered.isOk()) return remembered;
      scan.markDistinctValue();
      finishValue();
      StatusCode published = publishDistinct(result);
      if (!published.isOk()) return published;
      cursor.rowReturned();
      return StatusCode.OK;
    }
  }

  private StatusCode nextAggregateCandidate(
      SqlScanCursor cursor,
      SqlScanRowResult result) {
    if (scan.groupLookahead().inputExhausted() && !scan.groupLookahead().available()) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = beginCandidate(cursor);
    if (!status.isOk()) {
      return status;
    }
    while (true) {
      status = nextValue();
      if (status == StatusCode.CONFLICT) {
        scan.groupLookahead().exhaustInput();
        break;
      }
      if (!status.isOk()) {
        return status;
      }
      if (!groupKeys.sameCurrentGroup()) {
        status = lookaheadAccumulators.reset(bound.aggregates);
        if (!status.isOk()) return status;
        status = lookaheadAccumulators.accumulate(
            bound.aggregates,
            bound.projectionPrograms,
            projected,
            currentSource,
            bound.table);
        if (status.isOk()) {
          status = scan.groupLookahead().set(
              highs, values, bound.projectionPrograms.count(), projected);
          if (!status.isOk()) return status;
          status = groupKeys.rememberLookahead();
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
    if (scan.groupLookahead().available()) {
      StatusCode restored = scan.groupLookahead().take(
          values, bound.projectionPrograms.count(), projected);
      if (!restored.isOk()) return restored;
      StatusCode keys = groupKeys.restoreLookahead();
      if (!keys.isOk()) return keys;
      StatusCode copied = accumulators.copyFrom(lookaheadAccumulators, bound.aggregates);
      if (!copied.isOk()) return copied;
      return StatusCode.OK;
    } else {
      StatusCode status = nextValue();
      if (status == StatusCode.CONFLICT) {
        scan.groupLookahead().exhaustInput();
        return StatusCode.CONFLICT;
      }
      if (!status.isOk()) {
        return status;
      }
    }
    StatusCode keys = groupKeys.beginGroup();
    if (!keys.isOk()) return keys;
    StatusCode status = accumulators.reset(bound.aggregates);
    if (!status.isOk()) return status;
    status = accumulators.accumulate(
        bound.aggregates,
        bound.projectionPrograms,
        projected,
        currentSource,
        bound.table);
    finishValue();
    return status;
  }

  private StatusCode publish(SqlScanRowResult result) {
    int groups = bound.command.columnCount() - bound.command.aggregateOutputCount();
    int count = bound.projectedColumnCount;
    projected.reset(count);
    if (!projected.status().isOk()) return projected.status();
    for (int output = 0; output < groups; output++) {
      int key = SqlGroupExpressions.groupKey(bound.command, output);
      if (key < 0 || key >= groupKeys.count()) return StatusCode.INVARIANT_BROKEN;
      values[output] = groupKeys.group().value(key);
      highs[output] = groupKeys.group().highValue(key);
      if (groupKeys.group().nullValue(key)) projected.setNull(output);
      else projected.setDecimal128(output, highs[output], values[output]);
    }
    for (int output = 0; output < bound.command.aggregateOutputCount(); output++) {
      int invocation = bound.command.aggregateOutputInvocation(output);
      int lane = groups + output;
      values[lane] = accumulators.value(invocation);
      highs[lane] = accumulators.highValue(invocation);
      if (accumulators.nullValue(invocation)) projected.setNull(lane);
      else projected.setDecimal128(lane, highs[lane], values[lane]);
    }
    StatusCode status = result.setWords(
        count == 0 ? 0 : values[0], highs, values, projected,
        bound.projectedTypeDescriptors, count);
    for (int output = 0; status.isOk() && output < groups; output++) {
      int key = SqlGroupExpressions.groupKey(bound.command, output);
      if (SqlTypeDescriptor.typeId(bound.projectedTypeDescriptors[output])
          == SqlTypeDescriptor.TYPE_ID_VARCHAR && !result.isNull(output)) {
        status = result.setTextAt(
            output, groupKeys.group().text(key), 0,
            groupKeys.group().textLength(key));
      }
    }
    for (int output = 0;
        status.isOk() && output < bound.command.aggregateOutputCount(); output++) {
      status = publishAggregateText(result, groups + output,
          bound.command.aggregateOutputInvocation(output));
    }
    return status;
  }

  private StatusCode publishAggregateText(
      SqlScanRowResult result, int output, int invocation) {
    int descriptor = bound.aggregates.resultDescriptor(invocation);
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || accumulators.nullValue(invocation)) return StatusCode.OK;
    if (text == null || textCharacters == null) return StatusCode.INVARIANT_BROKEN;
    int length = accumulators.textLength(invocation);
    text.clear();
    text.put(accumulators.text(), accumulators.textOffset(invocation), length);
    int characters = Utf8Text.decode(text, 0, length, textCharacters, 0);
    return characters < 0 ? StatusCode.CORRUPTION
        : result.setTextAt(output, textCharacters, 0, characters);
  }

  private StatusCode publishDistinct(SqlScanRowResult result) {
    int count = bound.projectedColumnCount;
    projected.reset(count);
    if (!projected.status().isOk()) return projected.status();
    SqlBlockRow keys = groupKeys.group();
    for (int output = 0; output < count; output++) {
      values[output] = keys.value(output);
      highs[output] = keys.highValue(output);
      if (keys.nullValue(output)) projected.setNull(output);
      else projected.setDecimal128(output, highs[output], values[output]);
    }
    StatusCode status = result.setWords(
        count == 0 ? 0 : values[0], highs, values, projected,
        bound.projectedTypeDescriptors, count);
    for (int output = 0; status.isOk() && output < count; output++) {
      if (SqlTypeDescriptor.typeId(bound.projectedTypeDescriptors[output])
          == SqlTypeDescriptor.TYPE_ID_VARCHAR && !keys.nullValue(output)) {
        status = result.setTextAt(
            output, keys.text(output), 0, keys.textLength(output));
      }
    }
    return status;
  }

  private StatusCode nextValue() {
    if (plan.sorts()) {
      projected.reset(bound.projectionPrograms.count());
      StatusCode status = sorts.nextGroupValue(values, projected);
      if (status.isOk()) {
        groupNull = projected.isNull(0);
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
    System.arraycopy(
        projected.highs(), 0, highs, 0, bound.projectionPrograms.count());
    groupNull = projected.isNull(0);
    currentSource = source;
    return captureCurrentKey();
  }

  private StatusCode captureCurrentKey() {
    return groupKeys.capture(currentSource, projected);
  }

  private boolean requiresTextBuffers() {
    for (int invocation = 0; invocation < bound.aggregates.count(); invocation++) {
      if (SqlTypeDescriptor.typeId(bound.aggregates.resultDescriptor(invocation))
          == SqlTypeDescriptor.TYPE_ID_VARCHAR) return true;
    }
    return false;
  }

  private StatusCode validateRow(HeapRowResult source) {
    return source.length() >= bound.table.fixedRowBytes()
            && source.length() <= bound.table.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

}
