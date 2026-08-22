package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlQuery;

/** Flat bounded cache for statement-constant predicate-subquery results. */
final class SqlSubqueryResultCache {
  static final int MAXIMUM_RESULTS = 1_024;
  private final BoundSqlQuery query;
  private final SqlExpressionEvaluator expressions;
  private final byte[] states = new byte[SqlQuery.MAXIMUM_EDGES];
  private final int[] truths = new int[SqlQuery.MAXIMUM_EDGES];
  private final int[] heads = new int[SqlQuery.MAXIMUM_EDGES];
  private final int[] tails = new int[SqlQuery.MAXIMUM_EDGES];
  private final int[] counts = new int[SqlQuery.MAXIMUM_EDGES];
  private final long[] scalarValues = new long[SqlQuery.MAXIMUM_EDGES];
  private final int[] scalarDescriptors = new int[SqlQuery.MAXIMUM_EDGES];
  private final short[] scalarTextLengths = new short[SqlQuery.MAXIMUM_EDGES];
  private final boolean[] scalarNulls = new boolean[SqlQuery.MAXIMUM_EDGES];
  private final boolean[] cacheable = new boolean[SqlQuery.MAXIMUM_EDGES];
  private final char[][] scalarText = new char[SqlQuery.MAXIMUM_EDGES][];
  private final SqlPredicateOperand operand = new SqlPredicateOperand();
  private long[] values;
  private int[] descriptors;
  private short[] textLengths;
  private boolean[] nulls;
  private int[] next;
  private char[] membershipText;
  private int used;

  SqlSubqueryResultCache(BoundSqlQuery boundQuery, SqlExpressionEvaluator evaluator) {
    query = boundQuery;
    expressions = evaluator;
  }

  void prepare() {
    clear();
    for (int edge = 0; edge < query.edgeCount(); edge++) {
      cacheable[edge] = !correlated(edge);
      if (!cacheable[edge]) continue;
      int child = query.edgeChild(edge);
      int type = query.block(child).projectionType();
      int kind = query.edgeKind(edge);
      if (kind == SqlQuery.SUBQUERY_MEMBERSHIP) {
        prepareMembership(text(type));
      } else if (kind == SqlQuery.SUBQUERY_SCALAR && text(type)) {
        if (scalarText[edge] == null) scalarText[edge] = new char[510];
        operand.prepareText();
      }
    }
  }

  boolean enabled(int edge) { return cacheable[edge]; }
  boolean available(int edge) { return states[edge] != 0; }
  void start(int edge) {
    heads[edge] = -1;
    tails[edge] = -1;
  }

  boolean append(int edge, SqlPredicateOperand source) {
    if (query.edgeKind(edge) == SqlQuery.SUBQUERY_SCALAR) {
      captureScalar(edge, source);
      return true;
    }
    if (used >= MAXIMUM_RESULTS) return false;
    int slot = used++;
    next[slot] = -1;
    if (tails[edge] < 0) heads[edge] = slot;
    else next[tails[edge]] = slot;
    tails[edge] = slot;
    values[slot] = source.value();
    descriptors[slot] = source.descriptor();
    nulls[slot] = source.nullValue();
    if (!source.nullValue() && text(source.descriptor())) {
      int offset = slot * 510;
      int length = source.textLength();
      for (int index = 0; index < length; index++) {
        membershipText[offset + index] = source.textCharacter(index);
      }
      textLengths[slot] = (short) length;
    }
    return true;
  }

  void completeValues(int edge, int count) {
    counts[edge] = count;
    states[edge] = 1;
  }

  void completeTruth(int edge, int truth) {
    truths[edge] = truth;
    states[edge] = 1;
  }

  int truth(int edge, SqlPredicateOperand left) {
    int kind = query.edgeKind(edge);
    if (kind == SqlQuery.SUBQUERY_EXISTS) return truths[edge];
    int value = kind == SqlQuery.SUBQUERY_SCALAR
        ? scalar(edge, left) : membership(edge, left);
    return query.edgeNegated(edge) ? negate(value) : value;
  }

  private int scalar(int edge, SqlPredicateOperand left) {
    int count = counts[edge];
    if (count == 0 || left == null || left.nullValue()) {
      return SqlBooleanPredicateEvaluator.UNKNOWN;
    }
    SqlPredicateOperand right = loadScalar(edge);
    if (right.nullValue()) return SqlBooleanPredicateEvaluator.UNKNOWN;
    return compare(left, right, query.edgeComparison(edge))
        ? SqlBooleanPredicateEvaluator.TRUE : SqlBooleanPredicateEvaluator.FALSE;
  }

  private int membership(int edge, SqlPredicateOperand left) {
    int count = counts[edge];
    if (count == 0) return SqlBooleanPredicateEvaluator.FALSE;
    if (left == null || left.nullValue()) return SqlBooleanPredicateEvaluator.UNKNOWN;
    boolean unknown = false;
    int slot = heads[edge];
    for (int index = 0; index < count; index++, slot = next[slot]) {
      SqlPredicateOperand candidate = load(slot);
      if (candidate.nullValue()) unknown = true;
      else if (compare(left, candidate, SqlComparison.EQUAL)) {
        return SqlBooleanPredicateEvaluator.TRUE;
      }
    }
    return unknown
        ? SqlBooleanPredicateEvaluator.UNKNOWN : SqlBooleanPredicateEvaluator.FALSE;
  }

  void abandon(int edge) {
    int slot = heads[edge];
    while (slot >= 0) {
      int following = next[slot];
      clearSlot(slot);
      slot = following;
    }
    heads[edge] = -1;
    tails[edge] = -1;
    counts[edge] = 0;
    states[edge] = 0;
    cacheable[edge] = false;
  }

  void clear() {
    clearRange(0, used);
    operand.clear();
    used = 0;
    for (int edge = 0; edge < states.length; edge++) {
      int scalarLength = Short.toUnsignedInt(scalarTextLengths[edge]);
      if (scalarText[edge] != null) {
        for (int index = 0; index < scalarLength; index++) scalarText[edge][index] = 0;
      }
      scalarValues[edge] = 0;
      scalarDescriptors[edge] = 0;
      scalarTextLengths[edge] = 0;
      scalarNulls[edge] = false;
      states[edge] = 0;
      truths[edge] = SqlBooleanPredicateEvaluator.FALSE;
      heads[edge] = -1;
      tails[edge] = -1;
      counts[edge] = 0;
    }
  }

  private SqlPredicateOperand load(int slot) {
    if (nulls[slot]) operand.setNull(descriptors[slot]);
    else if (text(descriptors[slot])) {
      operand.setTextCharacters(
          membershipText,
          slot * 510,
          Short.toUnsignedInt(textLengths[slot]),
          descriptors[slot]);
    } else operand.setValue(values[slot], descriptors[slot], false);
    return operand;
  }

  private void clearRange(int start, int end) {
    if (values == null) return;
    for (int slot = start; slot < end; slot++) clearSlot(slot);
    operand.clear();
  }

  private void clearSlot(int slot) {
    values[slot] = 0;
    descriptors[slot] = 0;
    nulls[slot] = false;
    int length = Short.toUnsignedInt(textLengths[slot]);
    if (membershipText != null) {
      int offset = slot * 510;
      for (int index = 0; index < length; index++) membershipText[offset + index] = 0;
    }
    textLengths[slot] = 0;
    next[slot] = -1;
  }

  private void prepareMembership(boolean textValues) {
    if (values == null) {
      values = new long[MAXIMUM_RESULTS];
      descriptors = new int[MAXIMUM_RESULTS];
      textLengths = new short[MAXIMUM_RESULTS];
      nulls = new boolean[MAXIMUM_RESULTS];
      next = new int[MAXIMUM_RESULTS];
    }
    if (textValues && membershipText == null) {
      membershipText = new char[MAXIMUM_RESULTS * 510];
    }
    if (textValues) operand.prepareText();
  }

  private void captureScalar(int edge, SqlPredicateOperand source) {
    int prior = Short.toUnsignedInt(scalarTextLengths[edge]);
    if (scalarText[edge] != null) {
      for (int index = 0; index < prior; index++) scalarText[edge][index] = 0;
    }
    scalarTextLengths[edge] = 0;
    scalarValues[edge] = source.value();
    scalarDescriptors[edge] = source.descriptor();
    scalarNulls[edge] = source.nullValue();
    if (!source.nullValue() && text(source.descriptor())) {
      int length = source.textLength();
      for (int index = 0; index < length; index++) {
        scalarText[edge][index] = source.textCharacter(index);
      }
      scalarTextLengths[edge] = (short) length;
    }
  }

  private SqlPredicateOperand loadScalar(int edge) {
    if (scalarNulls[edge]) operand.setNull(scalarDescriptors[edge]);
    else if (text(scalarDescriptors[edge])) {
      operand.setTextCharacters(
          scalarText[edge],
          0,
          Short.toUnsignedInt(scalarTextLengths[edge]),
          scalarDescriptors[edge]);
    } else operand.setValue(scalarValues[edge], scalarDescriptors[edge], false);
    return operand;
  }


  boolean correlated(int edge) {
    int child = query.edgeChild(edge);
    for (int block = child; block < query.blockCount(); block++) {
      if (!descendant(block, child)) continue;
      int scope = query.block(block).correlationScope();
      if (scope >= 0 && !descendant(scope, child)) return true;
    }
    return false;
  }

  private boolean descendant(int block, int ancestor) {
    int current = block;
    while (current >= 0 && current != ancestor) current = query.blockParent(current);
    return current == ancestor;
  }

  private boolean compare(
      SqlPredicateOperand left, SqlPredicateOperand right, SqlComparison comparison) {
    int compared = text(left.descriptor())
        ? SqlBooleanTextComparator.compare(left, right)
        : expressions.compareExact(
            left.value(), left.descriptor(), right.value(), right.descriptor());
    return switch (comparison) {
      case EQUAL -> compared == 0;
      case NOT_EQUAL -> compared != 0;
      case LESS_THAN -> compared < 0;
      case LESS_OR_EQUAL -> compared <= 0;
      case GREATER_THAN -> compared > 0;
      case GREATER_OR_EQUAL -> compared >= 0;
      case HALF_OPEN_RANGE, IN, NOT_IN -> false;
    };
  }

  private static boolean text(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private static int negate(int value) {
    return value == SqlBooleanPredicateEvaluator.UNKNOWN ? value
        : value == SqlBooleanPredicateEvaluator.TRUE
            ? SqlBooleanPredicateEvaluator.FALSE : SqlBooleanPredicateEvaluator.TRUE;
  }
}
