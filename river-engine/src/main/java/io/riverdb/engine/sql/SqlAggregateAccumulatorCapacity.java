package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlAggregateKind;

/** Exact-shape reusable storage admission for aggregate accumulator lanes. */
final class SqlAggregateAccumulatorCapacity {
  private SqlAggregateAccumulatorCapacity() { }

  static StatusCode reserve(
      SqlAggregateAccumulatorSet state, SqlBoundAggregateSet aggregates) {
    int required = aggregates.count();
    int capacity = BoundedArrayGrowth.capacity(
        state.values.length, required, SqlShapeLimits.MAX_AGGREGATES, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    int slots = textSlots(aggregates);
    int textBytes = slots == 0 ? 0 : (slots + 1) * TableSchema.MAXIMUM_ROW_BYTES;
    int currentText = state.text == null ? 0 : state.text.length;
    int grownText = textBytes == 0 ? currentText : BoundedArrayGrowth.capacity(
        currentText, textBytes,
        SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES, TableSchema.MAXIMUM_ROW_BYTES);
    if (grownText < 0) return StatusCode.RESOURCE_EXHAUSTED;
    state.eraseText();
    try {
      if (grownText != currentText) state.text = new byte[grownText];
      if (capacity != state.values.length) grow(state, capacity);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    state.textSlotCount = 0;
    state.shapeCount = required;
    for (int invocation = 0; invocation < required; invocation++) {
      state.textSlots[invocation] = textAggregate(aggregates, invocation)
          ? state.textSlotCount++ : -1;
    }
    return state.prepareDistinct(aggregates);
  }

  static StatusCode reservePair(
      SqlAggregateAccumulatorSet first,
      SqlAggregateAccumulatorSet second,
      SqlBoundAggregateSet aggregates) {
    StatusCode status = reserve(first, aggregates);
    return status.isOk() ? reserve(second, aggregates) : status;
  }

  private static void grow(SqlAggregateAccumulatorSet state, int capacity) {
    long[] values = new long[capacity];
    long[] highs = new long[capacity];
    long[] counts = new long[capacity];
    boolean[] nulls = new boolean[capacity];
    short[] lengths = new short[capacity];
    int[] slots = new int[capacity];
    state.values = values;
    state.highs = highs;
    state.counts = counts;
    state.nulls = nulls;
    state.textLengths = lengths;
    state.textSlots = slots;
  }

  private static int textSlots(SqlBoundAggregateSet aggregates) {
    int result = 0;
    for (int invocation = 0; invocation < aggregates.count(); invocation++) {
      if (textAggregate(aggregates, invocation)) result++;
    }
    return result;
  }

  private static boolean textAggregate(
      SqlBoundAggregateSet aggregates, int invocation) {
    int kind = aggregates.kind(invocation);
    return (kind == SqlAggregateKind.MIN || kind == SqlAggregateKind.MAX)
        && SqlTypeDescriptor.typeId(aggregates.resultDescriptor(invocation))
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
}
