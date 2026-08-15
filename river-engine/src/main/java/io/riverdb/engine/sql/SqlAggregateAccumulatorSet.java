package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlAggregateKind;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable primitive and owned UTF-8 state for one aggregate set. */
final class SqlAggregateAccumulatorSet {
  private static final int MAXIMUM_INVOCATIONS = 8;
  private final long[] values = new long[MAXIMUM_INVOCATIONS];
  private final long[] highs = new long[MAXIMUM_INVOCATIONS];
  private final long[] counts = new long[MAXIMUM_INVOCATIONS];
  private final boolean[] nulls = new boolean[MAXIMUM_INVOCATIONS];
  private final short[] textLengths = new short[MAXIMUM_INVOCATIONS];
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();
  private byte[] text;
  private int candidateLength;

  void reset(SqlBoundAggregateSet aggregates) {
    eraseText();
    for (int invocation = 0; invocation < aggregates.count(); invocation++) {
      values[invocation] = 0;
      highs[invocation] = 0;
      counts[invocation] = 0;
      int kind = aggregates.kind(invocation);
      nulls[invocation] = kind != SqlAggregateKind.COUNT
          && kind != SqlAggregateKind.COUNT_VALUE;
      textLengths[invocation] = 0;
    }
  }

  void clear(SqlBoundAggregateSet aggregates) {
    reset(aggregates);
  }

  void clearAll() {
    eraseText();
    for (int invocation = 0; invocation < MAXIMUM_INVOCATIONS; invocation++) {
      values[invocation] = 0;
      highs[invocation] = 0;
      counts[invocation] = 0;
      nulls[invocation] = false;
      textLengths[invocation] = 0;
    }
  }

  void copyFrom(
      SqlAggregateAccumulatorSet source, SqlBoundAggregateSet aggregates) {
    reset(aggregates);
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
        ensureText();
        if (length > 0) {
          System.arraycopy(
              source.text,
              invocation * Utf8Text.MAXIMUM_BYTES,
              text,
              invocation * Utf8Text.MAXIMUM_BYTES,
              length);
        }
        textLengths[invocation] = (short) length;
      }
    }
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
      boolean nullValue = lane < 0 || (row.nullMask() & 1L << lane) != 0;
      StatusCode status = kind == SqlAggregateKind.COUNT
          ? increment(invocation)
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

  private StatusCode accumulateBlockText(
      SqlBlockRow row, int invocation, int kind, int lane) {
    ensureText();
    int candidate = MAXIMUM_INVOCATIONS * Utf8Text.MAXIMUM_BYTES;
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
    ensureText();
    int candidateOffset = MAXIMUM_INVOCATIONS * Utf8Text.MAXIMUM_BYTES;
    for (int index = 0; index < candidateLength; index++) {
      text[candidateOffset + index] = 0;
    }
    int candidateLength = candidateText(
        programs, row, source, definition, lane);
    if (candidateLength < 0) return StatusCode.CORRUPTION;
    this.candidateLength = candidateLength;
    int compared = nulls[invocation]
        ? 0 : compare(candidateOffset, candidateLength,
            invocation * Utf8Text.MAXIMUM_BYTES,
            Short.toUnsignedInt(textLengths[invocation]));
    if (nulls[invocation]
        || kind == SqlAggregateKind.MIN && compared < 0
        || kind == SqlAggregateKind.MAX && compared > 0) {
      int winnerOffset = invocation * Utf8Text.MAXIMUM_BYTES;
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
    int target = MAXIMUM_INVOCATIONS * Utf8Text.MAXIMUM_BYTES;
    int generated = row.textLength(lane);
    int column = programs.rawColumn(lane);
    if (column < 0) {
      return Utf8Text.encode(
          row.text(lane), 0, generated, Utf8Text.MAXIMUM_SCALARS, text, target);
    }
    if (column <= 0 || source == null) return -1;
    long handle = source.getLong((column - 1) * Long.BYTES);
    int offset = (int) (handle >>> 32);
    int length = (int) handle;
    if (offset < 0
        || length < 0
        || length > Utf8Text.MAXIMUM_BYTES
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
      int kind = aggregates.kind(invocation);
      if (nulls[invocation]) continue;
      if (kind == SqlAggregateKind.SUM
          && highs[invocation] != (values[invocation] < 0 ? -1 : 0)) {
        return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      }
      if (kind == SqlAggregateKind.SUM
          && SqlTypeDescriptor.typeId(aggregates.resultDescriptor(invocation))
              == SqlTypeDescriptor.TYPE_ID_DECIMAL
          && !ExactDecimal.fits(
              values[invocation],
              SqlTypeDescriptor.parameterOne(
                  aggregates.resultDescriptor(invocation)))) {
        return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      }
      if (kind == SqlAggregateKind.AVG
          && !ExactDecimal.average(
              highs[invocation],
              values[invocation],
              counts[invocation],
              inputScale(aggregates.inputDescriptor(invocation)),
              aggregates.resultDescriptor(invocation),
              decimal,
              wide)) {
        return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      }
      if (kind == SqlAggregateKind.AVG) values[invocation] = decimal.value;
    }
    return StatusCode.OK;
  }

  long[] values() { return values; }
  boolean[] nulls() { return nulls; }
  long value(int invocation) { return values[invocation]; }
  boolean nullValue(int invocation) { return nulls[invocation]; }
  int textLength(int invocation) { return Short.toUnsignedInt(textLengths[invocation]); }
  byte[] text() { return text; }
  int textOffset(int invocation) { return invocation * Utf8Text.MAXIMUM_BYTES; }

  private StatusCode increment(int invocation) {
    if (values[invocation] == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    values[invocation]++;
    nulls[invocation] = false;
    return StatusCode.OK;
  }

  private void ensureText() {
    if (text == null) {
      text = new byte[(MAXIMUM_INVOCATIONS + 1) * Utf8Text.MAXIMUM_BYTES];
    }
  }

  private void eraseText() {
    if (text == null) return;
    for (int invocation = 0; invocation < MAXIMUM_INVOCATIONS; invocation++) {
      int offset = invocation * Utf8Text.MAXIMUM_BYTES;
      int length = Short.toUnsignedInt(textLengths[invocation]);
      for (int index = 0; index < length; index++) text[offset + index] = 0;
    }
    int candidate = MAXIMUM_INVOCATIONS * Utf8Text.MAXIMUM_BYTES;
    for (int index = 0; index < candidateLength; index++) text[candidate + index] = 0;
    candidateLength = 0;
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

  private static int inputScale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }
}
