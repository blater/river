package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable primitive and owned UTF-8 state for one aggregate set. */
final class SqlAggregateAccumulatorSet {
  private final SqlSessionShapeBudget budget;
  long[] values = new long[0];
  long[] highs = new long[0];
  long[] counts = new long[0];
  boolean[] nulls = new boolean[0];
  short[] textLengths = new short[0];
  int[] textSlots = new int[0];
  private final SqlAggregateNumericState numeric = new SqlAggregateNumericState();
  byte[] text;
  private int candidateLength;
  int shapeCount;
  int textSlotCount;
  private SqlDistinctValueStore[] distinct = new SqlDistinctValueStore[0];
  private final long[] distinctCount = new long[1];

  SqlAggregateAccumulatorSet(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode reset(SqlBoundAggregateSet aggregates) {
    eraseText();
    int count = Math.min(shapeCount, aggregates.count());
    for (int invocation = 0; invocation < count; invocation++) {
      values[invocation] = 0;
      highs[invocation] = 0;
      counts[invocation] = 0;
      int kind = aggregates.kind(invocation);
      nulls[invocation] = kind != SqlAggregateKind.COUNT
          && kind != SqlAggregateKind.COUNT_VALUE
          && kind != SqlAggregateKind.COUNT_DISTINCT;
      textLengths[invocation] = 0;
      if (kind == SqlAggregateKind.COUNT_DISTINCT) {
        StatusCode status = distinct[invocation].reset();
        if (!status.isOk()) return status;
      }
    }
    return StatusCode.OK;
  }

  StatusCode clear(SqlBoundAggregateSet aggregates) {
    return reset(aggregates);
  }

  void clearAll() {
    eraseText();
    for (int invocation = 0; invocation < values.length; invocation++) {
      values[invocation] = 0;
      highs[invocation] = 0;
      counts[invocation] = 0;
      nulls[invocation] = false;
      textLengths[invocation] = 0;
    }
  }

  StatusCode copyFrom(
      SqlAggregateAccumulatorSet source, SqlBoundAggregateSet aggregates) {
    StatusCode status = reset(aggregates);
    if (!status.isOk()) return status;
    for (int invocation = 0; invocation < aggregates.count(); invocation++) {
      values[invocation] = source.values[invocation];
      highs[invocation] = source.highs[invocation];
      counts[invocation] = source.counts[invocation];
      nulls[invocation] = source.nulls[invocation];
      int length = Short.toUnsignedInt(source.textLengths[invocation]);
      boolean textValue = SqlTypeDescriptor.typeId(
          aggregates.resultDescriptor(invocation))
              == SqlTypeDescriptor.TYPE_ID_VARCHAR
          && !source.nulls[invocation];
      if (textValue) {
        if (length > 0) {
          System.arraycopy(
              source.text,
              source.textOffset(invocation),
              text,
              textOffset(invocation),
              length);
        }
        textLengths[invocation] = (short) length;
      }
      if (aggregates.kind(invocation) == SqlAggregateKind.COUNT_DISTINCT) {
        status = distinct[invocation].copyFrom(source.distinct[invocation]);
        if (!status.isOk()) return status;
      }
    }
    return StatusCode.OK;
  }

  StatusCode accumulate(
      SqlBoundAggregateSet aggregates,
      SqlBoundProjectionPrograms programs,
      SqlProjectedRow row,
      HeapRowResult source,
      TableDefinition definition) {
    for (int invocation = 0; invocation < aggregates.count(); invocation++) {
      int kind = aggregates.kind(invocation);
      int lane = aggregates.operandLane(invocation);
      boolean nullValue = lane < 0 || row.isNull(lane);
      StatusCode status = kind == SqlAggregateKind.COUNT
          ? increment(invocation)
          : kind == SqlAggregateKind.COUNT_DISTINCT
              ? addDistinct(row, invocation, lane)
          : nullValue ? StatusCode.OK
              : accumulateValue(
                  aggregates, programs, row, source, definition,
                  invocation, kind, lane);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode accumulateBlock(
      SqlBoundAggregateSet aggregates,
      SqlBlockRow row) {
    for (int invocation = 0; invocation < aggregates.count(); invocation++) {
      int kind = aggregates.kind(invocation);
      int lane = aggregates.operandLane(invocation);
      boolean nullValue = lane < 0 || row.nullValue(lane);
      StatusCode status = kind == SqlAggregateKind.COUNT
          ? increment(invocation)
          : kind == SqlAggregateKind.COUNT_DISTINCT
              ? addDistinct(row, invocation, lane)
          : nullValue ? StatusCode.OK
              : accumulateBlockValue(aggregates, row, invocation, kind, lane);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode accumulateBlockValue(
      SqlBoundAggregateSet aggregates,
      SqlBlockRow row,
      int invocation,
      int kind,
      int lane) {
    if (kind == SqlAggregateKind.COUNT_VALUE) return increment(invocation);
    if (SqlTypeDescriptor.typeId(aggregates.inputDescriptor(invocation))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return accumulateBlockText(row, invocation, kind, lane);
    }
    long value = row.value(lane);
    int descriptor = aggregates.inputDescriptor(invocation);
    if (SqlNumericTypeRules.isNumeric(descriptor)) {
      return numeric.accumulate(
          highs, values, counts, nulls,
          invocation, kind, row.highValue(lane), value, descriptor);
    }
    if (kind == SqlAggregateKind.SUM || kind == SqlAggregateKind.AVG) {
      long previous = values[invocation];
      values[invocation] += value;
      highs[invocation] += (value < 0 ? -1 : 0)
          + (Long.compareUnsigned(values[invocation], previous) < 0 ? 1 : 0);
      counts[invocation]++;
      nulls[invocation] = false;
      return StatusCode.OK;
    }
    if (nulls[invocation]
        || kind == SqlAggregateKind.MIN && value < values[invocation]
        || kind == SqlAggregateKind.MAX && value > values[invocation]) {
      values[invocation] = value;
    }
    nulls[invocation] = false;
    return StatusCode.OK;
  }

  private StatusCode addDistinct(
      SqlProjectedRow row, int invocation, int lane) {
    if (lane < 0) return StatusCode.FEATURE_NOT_SUPPORTED;
    StatusCode status = distinct[invocation].add(row, lane);
    return status;
  }

  private StatusCode addDistinct(
      SqlBlockRow row, int invocation, int lane) {
    if (lane < 0) return StatusCode.FEATURE_NOT_SUPPORTED;
    StatusCode status = distinct[invocation].add(row, lane);
    return status;
  }

  private StatusCode accumulateBlockText(
      SqlBlockRow row, int invocation, int kind, int lane) {
    int candidate = textSlotCount * TableSchema.MAXIMUM_ROW_BYTES;
    for (int index = 0; index < candidateLength; index++) text[candidate + index] = 0;
    int length = Utf8Text.encode(
        row.text(lane), 0, row.textLength(lane),
        Utf8Text.MAXIMUM_SCALARS, text, candidate);
    if (length < 0) return StatusCode.CORRUPTION;
    candidateLength = length;
    int compared = nulls[invocation] ? 0
        : compare(candidate, length, textOffset(invocation), textLength(invocation));
    if (nulls[invocation]
        || kind == SqlAggregateKind.MIN && compared < 0
        || kind == SqlAggregateKind.MAX && compared > 0) {
      int winner = textOffset(invocation);
      int previous = textLength(invocation);
      System.arraycopy(text, candidate, text, winner, length);
      for (int index = length; index < previous; index++) text[winner + index] = 0;
      textLengths[invocation] = (short) length;
    }
    nulls[invocation] = false;
    return StatusCode.OK;
  }

  private StatusCode accumulateValue(
      SqlBoundAggregateSet aggregates,
      SqlBoundProjectionPrograms programs,
      SqlProjectedRow row,
      HeapRowResult source,
      TableDefinition definition,
      int invocation,
      int kind,
      int lane) {
    if (kind == SqlAggregateKind.COUNT_VALUE) return increment(invocation);
    if (SqlTypeDescriptor.typeId(aggregates.inputDescriptor(invocation))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return accumulateText(
          programs, row, source, definition, invocation, kind, lane);
    }
    long value = row.value(lane);
    int descriptor = aggregates.inputDescriptor(invocation);
    if (SqlNumericTypeRules.isNumeric(descriptor)) {
      return numeric.accumulate(
          highs, values, counts, nulls,
          invocation, kind, row.highValue(lane), value, descriptor);
    }
    if (kind == SqlAggregateKind.SUM || kind == SqlAggregateKind.AVG) {
      long previous = values[invocation];
      values[invocation] += value;
      highs[invocation] += (value < 0 ? -1 : 0)
          + (Long.compareUnsigned(values[invocation], previous) < 0 ? 1 : 0);
      counts[invocation]++;
      nulls[invocation] = false;
      return StatusCode.OK;
    }
    if (nulls[invocation]
        || kind == SqlAggregateKind.MIN && value < values[invocation]
        || kind == SqlAggregateKind.MAX && value > values[invocation]) {
      values[invocation] = value;
    }
    nulls[invocation] = false;
    return StatusCode.OK;
  }

  private StatusCode accumulateText(
      SqlBoundProjectionPrograms programs,
      SqlProjectedRow row,
      HeapRowResult source,
      TableDefinition definition,
      int invocation,
      int kind,
      int lane) {
    int candidateOffset = textSlotCount * TableSchema.MAXIMUM_ROW_BYTES;
    for (int index = 0; index < candidateLength; index++) {
      text[candidateOffset + index] = 0;
    }
    int candidateLength = candidateText(
        programs, row, source, definition, lane);
    if (candidateLength < 0) return StatusCode.CORRUPTION;
    this.candidateLength = candidateLength;
    int compared = nulls[invocation]
        ? 0 : compare(candidateOffset, candidateLength,
            textOffset(invocation),
            Short.toUnsignedInt(textLengths[invocation]));
    if (nulls[invocation]
        || kind == SqlAggregateKind.MIN && compared < 0
        || kind == SqlAggregateKind.MAX && compared > 0) {
      int winnerOffset = textOffset(invocation);
      int previousLength = Short.toUnsignedInt(textLengths[invocation]);
      System.arraycopy(
          text, candidateOffset,
          text, winnerOffset,
          candidateLength);
      for (int index = candidateLength; index < previousLength; index++) {
        text[winnerOffset + index] = 0;
      }
      textLengths[invocation] = (short) candidateLength;
    }
    nulls[invocation] = false;
    return StatusCode.OK;
  }

  private int candidateText(
      SqlBoundProjectionPrograms programs,
      SqlProjectedRow row,
      HeapRowResult source,
      TableDefinition definition,
      int lane) {
    int target = textSlotCount * TableSchema.MAXIMUM_ROW_BYTES;
    int generated = row.textLength(lane);
    int column = programs.rawColumn(lane);
    if (column < 0) {
      return Utf8Text.encode(
          row.text(lane), 0, generated, Utf8Text.MAXIMUM_SCALARS, text, target);
    }
    if (column <= 0 || source == null) return -1;
    long handle = source.getLong(definition.valueOffset(column));
    int offset = (int) (handle >>> 32);
    int length = (int) handle;
    if (offset < 0
        || length < 0
        || length > TableSchema.MAXIMUM_ROW_BYTES
        || definition == null
        || offset < definition.fixedRowBytes()
        || offset > source.length() - length) return -1;
    for (int index = 0; index < length; index++) {
      text[target + index] = source.getByte(offset + index);
    }
    return length;
  }

  StatusCode finish(SqlBoundAggregateSet aggregates) {
    for (int invocation = 0; invocation < aggregates.count(); invocation++) {
      if (aggregates.kind(invocation) != SqlAggregateKind.COUNT_DISTINCT) continue;
      StatusCode status = distinct[invocation].finish(distinctCount);
      if (!status.isOk()) return status;
      values[invocation] = distinctCount[0];
      nulls[invocation] = false;
    }
    return numeric.finish(aggregates, highs, values, counts, nulls);
  }

  long[] values() { return values; }
  long[] highs() { return highs; }
  boolean[] nulls() { return nulls; }
  long value(int invocation) { return values[invocation]; }
  long highValue(int invocation) { return highs[invocation]; }
  boolean nullValue(int invocation) { return nulls[invocation]; }
  int textLength(int invocation) { return Short.toUnsignedInt(textLengths[invocation]); }
  byte[] text() { return text; }
  int textOffset(int invocation) {
    return textSlots[invocation] * TableSchema.MAXIMUM_ROW_BYTES;
  }

  private StatusCode increment(int invocation) {
    if (values[invocation] == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    values[invocation]++;
    nulls[invocation] = false;
    return StatusCode.OK;
  }

  void eraseText() {
    if (text == null) return;
    for (int invocation = 0; invocation < shapeCount; invocation++) {
      if (textSlots[invocation] < 0) continue;
      int offset = textOffset(invocation);
      int length = Short.toUnsignedInt(textLengths[invocation]);
      for (int index = 0; index < length; index++) text[offset + index] = 0;
    }
    int candidate = textSlotCount * TableSchema.MAXIMUM_ROW_BYTES;
    for (int index = 0; index < candidateLength; index++) text[candidate + index] = 0;
    candidateLength = 0;
  }

  StatusCode prepareDistinct(SqlBoundAggregateSet aggregates) {
    try {
      if (distinct.length < aggregates.count()) {
        distinct = new SqlDistinctValueStore[aggregates.count()];
      }
      for (int invocation = 0; invocation < aggregates.count(); invocation++) {
        if (aggregates.kind(invocation) != SqlAggregateKind.COUNT_DISTINCT) continue;
        SqlDistinctValueStore store = distinct[invocation];
        if (store == null) {
          store = new SqlDistinctValueStore(budget);
          distinct[invocation] = store;
        }
        StatusCode status = store.begin(aggregates.inputDescriptor(invocation));
        if (!status.isOk()) return status;
      }
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode closeDistinct() {
    StatusCode status = StatusCode.OK;
    for (SqlDistinctValueStore store : distinct) {
      if (store != null) {
        StatusCode closed = store.close();
        if (status.isOk() && !closed.isOk()) status = closed;
      }
    }
    return status;
  }

  private int compare(int left, int leftLength, int right, int rightLength) {
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(
          Byte.toUnsignedInt(text[left + index]),
          Byte.toUnsignedInt(text[right + index]));
      if (compared != 0) return compared;
    }
    return Integer.compare(leftLength, rightLength);
  }

}
