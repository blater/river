package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Compact statement-owned physical predicate snapshot for nested execution. */
final class SqlNestedPredicatePlan {
  private final Name[] columns = names();
  private final Name[] tables = names();
  private final Name[] valueColumns = names();
  private final Name[] valueTables = names();
  private final SqlComparison[] comparisons =
      new SqlComparison[SqlCommand.MAXIMUM_PREDICATES];
  private final int[] types = new int[SqlCommand.MAXIMUM_PREDICATES];
  private final int[] lowerTypes = new int[SqlCommand.MAXIMUM_PREDICATES];
  private final int[] upperTypes = new int[SqlCommand.MAXIMUM_PREDICATES];
  private final long[] values = new long[SqlCommand.MAXIMUM_PREDICATES];
  private final long[] lowers = new long[SqlCommand.MAXIMUM_PREDICATES];
  private final long[] uppers = new long[SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] nullTests = new boolean[SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] negatedNullTests =
      new boolean[SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] columnValues = new boolean[SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] valueNulls = new boolean[SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] lowerNulls = new boolean[SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] upperNulls = new boolean[SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] betweenTests = new boolean[SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] truthTests = new boolean[SqlCommand.MAXIMUM_PREDICATES];
  private final int[] memberCounts = new int[SqlCommand.MAXIMUM_PREDICATES];
  private final int[] memberOffsets = new int[SqlCommand.MAXIMUM_PREDICATES];
  private final boolean[] memberHasNull = new boolean[SqlCommand.MAXIMUM_PREDICATES];
  private final int[] resolvedColumns = new int[SqlCommand.MAXIMUM_PREDICATES];
  private final int[] resolvedValueColumns = new int[SqlCommand.MAXIMUM_PREDICATES];
  private final int[] resolvedValueScopes = new int[SqlCommand.MAXIMUM_PREDICATES];
  private final SqlNestedPredicateText text = new SqlNestedPredicateText();
  private long[] memberValues;
  private int[] memberDescriptors;
  private boolean[] memberNulls;
  private int memberHighWater;
  private int count;

  StatusCode capture(SqlCommand source) {
    resetCaptured();
    count = source.wherePredicates().leafCount();
    clearSlots();
    StatusCode status = captureLeaves(source);
    if (!status.isOk()) return status;
    status = text.capture(source, this);
    if (!status.isOk()) resetCaptured();
    return status;
  }

  private StatusCode captureLeaves(SqlCommand source) {
    SqlBooleanPredicateProgram predicates = source.wherePredicates();
    int nextMember = 0;
    for (int leaf = 0; leaf < count; leaf++) {
      if (!captureColumn(source, predicates, leaf,
          SqlBooleanPredicateProgram.PROGRAM_LEFT, tables[leaf], columns[leaf])) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
      int test = predicates.leafTest(leaf);
      nullTests[leaf] = test == SqlBooleanPredicateProgram.TEST_NULL;
      negatedNullTests[leaf] = predicates.leafNegated(leaf);
      if (test == SqlBooleanPredicateProgram.TEST_COMPARISON) {
        StatusCode status = captureComparison(source, predicates, leaf);
        if (!status.isOk()) return status;
      } else if (test == SqlBooleanPredicateProgram.TEST_BETWEEN) {
        StatusCode status = captureBetween(predicates, leaf);
        if (!status.isOk()) return status;
      } else if (test == SqlBooleanPredicateProgram.TEST_MEMBERSHIP) {
        nextMember = captureMembership(predicates, leaf, nextMember);
        if (nextMember < 0) return StatusCode.RESOURCE_EXHAUSTED;
      } else if (test == SqlBooleanPredicateProgram.TEST_TRUTH) {
        truthTests[leaf] = true;
        comparisons[leaf] = predicates.comparison(leaf);
      } else if (!nullTests[leaf]) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode captureComparison(
      SqlCommand source, SqlBooleanPredicateProgram predicates, int leaf) {
    comparisons[leaf] = predicates.comparison(leaf);
    int right = SqlBooleanPredicateProgram.PROGRAM_RIGHT;
    columnValues[leaf] = captureColumn(
        source, predicates, leaf, right, valueTables[leaf], valueColumns[leaf]);
    if (columnValues[leaf]) {
      return comparisons[leaf] == SqlComparison.EQUAL
          ? StatusCode.OK : StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (predicates.programNodeCount(leaf, right) != 1) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    values[leaf] = predicates.programOperand(leaf, right, 0);
    types[leaf] = predicates.programDescriptor(leaf, right, 0);
    valueNulls[leaf] =
        predicates.programOperator(leaf, right, 0) == SqlScalarExpression.NULL;
    return StatusCode.OK;
  }

  private StatusCode captureBetween(
      SqlBooleanPredicateProgram source, int leaf) {
    if (source.leafNegated(leaf)) return StatusCode.FEATURE_NOT_SUPPORTED;
    betweenTests[leaf] = true;
    comparisons[leaf] = SqlComparison.HALF_OPEN_RANGE;
    StatusCode status = captureLiteralProgram(
        source, leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER, true);
    return status.isOk()
        ? captureLiteralProgram(
            source, leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER, false)
        : status;
  }

  private StatusCode captureLiteralProgram(
      SqlBooleanPredicateProgram source, int leaf, int program, boolean lower) {
    if (source.programNodeCount(leaf, program) != 1) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    int operator = source.programOperator(leaf, program, 0);
    if (operator != SqlScalarExpression.LITERAL && operator != SqlScalarExpression.NULL) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    long value = source.programOperand(leaf, program, 0);
    int descriptor = source.programDescriptor(leaf, program, 0);
    if (lower) {
      lowers[leaf] = value;
      lowerTypes[leaf] = descriptor;
      lowerNulls[leaf] = operator == SqlScalarExpression.NULL;
    } else {
      uppers[leaf] = value;
      upperTypes[leaf] = descriptor;
      upperNulls[leaf] = operator == SqlScalarExpression.NULL;
    }
    return StatusCode.OK;
  }

  private int captureMembership(
      SqlBooleanPredicateProgram source, int leaf, int nextMember) {
    comparisons[leaf] = source.leafNegated(leaf)
        ? SqlComparison.NOT_IN : SqlComparison.IN;
    memberOffsets[leaf] = nextMember;
    int sourceCount = source.leafMemberCount(leaf);
    if (!ensureMembers(nextMember + sourceCount)) return -1;
    for (int member = 0; member < sourceCount; member++) {
      memberValues[nextMember] = source.memberValue(leaf, member);
      memberDescriptors[nextMember] = source.memberDescriptor(leaf, member);
      memberNulls[nextMember] = source.memberNull(leaf, member);
      memberHasNull[leaf] |= memberNulls[nextMember];
      nextMember++;
      memberHighWater = nextMember;
      memberCounts[leaf]++;
    }
    return nextMember;
  }

  private boolean ensureMembers(int required) {
    if (required == 0 || memberValues != null && required <= memberValues.length) {
      return true;
    }
    if (required > SqlBooleanPredicateProgram.MAXIMUM_MEMBERS) return false;
    int capacity = memberValues == null ? 16 : memberValues.length;
    while (capacity < required) {
      capacity = Math.min(SqlBooleanPredicateProgram.MAXIMUM_MEMBERS, capacity * 2);
    }
    long[] nextValues = new long[capacity];
    int[] nextDescriptors = new int[capacity];
    boolean[] nextNulls = new boolean[capacity];
    if (memberHighWater > 0) {
      System.arraycopy(memberValues, 0, nextValues, 0, memberHighWater);
      System.arraycopy(memberDescriptors, 0, nextDescriptors, 0, memberHighWater);
      System.arraycopy(memberNulls, 0, nextNulls, 0, memberHighWater);
    }
    memberValues = nextValues;
    memberDescriptors = nextDescriptors;
    memberNulls = nextNulls;
    return true;
  }

  private void clearSlots() {
    for (int index = 0; index < SqlCommand.MAXIMUM_PREDICATES; index++) {
      columns[index].clear();
      tables[index].clear();
      valueColumns[index].clear();
      valueTables[index].clear();
      comparisons[index] = null;
      types[index] = 0;
      lowerTypes[index] = 0;
      upperTypes[index] = 0;
      values[index] = 0;
      lowers[index] = 0;
      uppers[index] = 0;
      nullTests[index] = false;
      negatedNullTests[index] = false;
      columnValues[index] = false;
      valueNulls[index] = false;
      lowerNulls[index] = false;
      upperNulls[index] = false;
      betweenTests[index] = false;
      truthTests[index] = false;
      memberCounts[index] = 0;
      memberOffsets[index] = 0;
      memberHasNull[index] = false;
    }
  }

  void resetCaptured() {
    text.reset();
    for (int index = 0; index < memberHighWater; index++) {
      memberValues[index] = 0;
      memberDescriptors[index] = 0;
      memberNulls[index] = false;
    }
    memberHighWater = 0;
    count = 0;
    resetBinding();
  }

  void resetBinding() {
    for (int index = 0; index < resolvedColumns.length; index++) {
      resolvedColumns[index] = -1;
      resolvedValueColumns[index] = -1;
      resolvedValueScopes[index] = -1;
    }
  }

  void setResolved(int index, int column, int valueColumn, int valueScope) {
    resolvedColumns[index] = column;
    resolvedValueColumns[index] = valueColumn;
    resolvedValueScopes[index] = valueScope;
  }

  int count() { return count; }
  CharSequence columnName(int index) { return columns[index]; }
  CharSequence tableName(int index) { return tables[index]; }
  CharSequence valueColumnName(int index) { return valueColumns[index]; }
  CharSequence valueTableName(int index) { return valueTables[index]; }
  SqlComparison comparison(int index) { return comparisons[index]; }
  int typeDescriptor(int index) { return types[index]; }
  int lowerDescriptor(int index) { return lowerTypes[index]; }
  int upperDescriptor(int index) { return upperTypes[index]; }
  long value(int index) { return values[index]; }
  long lowerInclusive(int index) { return lowers[index]; }
  long upperExclusive(int index) { return uppers[index]; }
  boolean isNullTest(int index) { return nullTests[index]; }
  boolean isNullTestNegated(int index) { return negatedNullTests[index]; }
  boolean isColumnValue(int index) { return columnValues[index]; }
  boolean isValueNull(int index) { return valueNulls[index]; }
  boolean isLowerNull(int index) { return lowerNulls[index]; }
  boolean isUpperNull(int index) { return upperNulls[index]; }
  boolean isBetween(int index) { return betweenTests[index]; }
  boolean isTruth(int index) { return truthTests[index]; }
  boolean isEquality(int index) { return comparisons[index] == SqlComparison.EQUAL; }
  boolean isRange(int index) { return comparisons[index] == SqlComparison.HALF_OPEN_RANGE; }
  boolean isMembership(int index) {
    return comparisons[index] == SqlComparison.IN
        || comparisons[index] == SqlComparison.NOT_IN;
  }
  int memberCount(int index) { return memberCounts[index]; }
  long memberValue(int index, int member) {
    return memberValues[memberOffsets[index] + member];
  }
  int memberDescriptor(int index, int member) {
    return memberDescriptors[memberOffsets[index] + member];
  }
  boolean memberNull(int index, int member) {
    return memberNulls[memberOffsets[index] + member];
  }
  boolean hasNullMember(int index) { return memberHasNull[index]; }
  int resolvedColumn(int index) { return resolvedColumns[index]; }
  int resolvedValueColumn(int index) { return resolvedValueColumns[index]; }
  int resolvedValueScope(int index) { return resolvedValueScopes[index]; }

  int textByteLength(long handle) { return text.byteLength(handle); }
  byte textByteAt(long handle, int index) { return text.byteAt(handle, index); }

  private static boolean captureColumn(
      SqlCommand source,
      SqlBooleanPredicateProgram predicates,
      int leaf,
      int program,
      Name table,
      Name column) {
    if (predicates.programNodeCount(leaf, program) != 1
        || predicates.programOperator(leaf, program, 0) != SqlScalarExpression.COLUMN) {
      return false;
    }
    int symbol = (int) predicates.programOperand(leaf, program, 0);
    CharSequence sourceTable = source.predicateSymbolTable(symbol);
    CharSequence sourceColumn = source.predicateSymbolName(symbol);
    if (sourceTable == null || sourceColumn == null) return false;
    table.copyFrom(sourceTable);
    column.copyFrom(sourceColumn);
    return true;
  }

  private static Name[] names() {
    Name[] result = new Name[SqlCommand.MAXIMUM_PREDICATES];
    for (int index = 0; index < result.length; index++) result[index] = new Name();
    return result;
  }

  private static final class Name implements CharSequence {
    private final char[] value = new char[64];
    private int length;

    void clear() { length = 0; }

    void copyFrom(CharSequence source) {
      length = Math.min(source == null ? 0 : source.length(), value.length);
      for (int index = 0; index < length; index++) value[index] = source.charAt(index);
    }

    @Override public int length() { return length; }
    @Override public char charAt(int index) {
      if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
      return value[index];
    }
    @Override public CharSequence subSequence(int start, int end) {
      if (start < 0 || end < start || end > length) {
        throw new IndexOutOfBoundsException(start);
      }
      return new String(value, start, end - start);
    }
    @Override public String toString() { return new String(value, 0, length); }
  }
}
