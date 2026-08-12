package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlQuery;

/** Statement-lifetime query syntax snapshot consumed after binding. */
final class BoundSqlQuery {
  static final int MAXIMUM_BLOCKS = SqlQuery.MAXIMUM_QUERY_BLOCKS;
  static final int MAXIMUM_PREDICATES = SqlCommand.MAXIMUM_PREDICATES;
  private final Block[] blocks = new Block[MAXIMUM_BLOCKS];
  private final int[] scalarPredicates = new int[MAXIMUM_BLOCKS];
  private final int[] existencePredicates = new int[MAXIMUM_BLOCKS];
  private final int[] membershipPredicates = new int[MAXIMUM_BLOCKS];
  private int blockCount;
  private boolean explain;
  private boolean analyze;

  BoundSqlQuery() {
    for (int index = 0; index < blocks.length; index++) {
      blocks[index] = new Block();
      scalarPredicates[index] = -1;
      membershipPredicates[index] = -1;
    }
  }

  void reset() {
    blockCount = 0;
    explain = false;
    analyze = false;
    for (int index = 0; index < blocks.length; index++) {
      scalarPredicates[index] = -1;
      existencePredicates[index] = 0;
      membershipPredicates[index] = -1;
    }
  }

  StatusCode capture(SqlCommand root, SqlQuery query) {
    reset();
    if (root == null || query == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    explain = query.isExplain();
    analyze = query.isAnalyze();
    blockCount = Math.max(1, query.blockCount());
    StatusCode status = blocks[0].capture(root);
    for (int index = 0; status.isOk() && index < query.blockCount(); index++) {
      status = blocks[index].capture(query.block(index));
      scalarPredicates[index] = query.scalarPredicate(index);
      existencePredicates[index] = query.hasExistencePredicate(index)
          ? query.existenceNegated(index) ? -1 : 1 : 0;
      membershipPredicates[index] = query.hasMembershipPredicate(index)
          ? query.membershipPredicate(index) : -1;
      blocks[index].membershipNegated = query.hasMembershipPredicate(index)
          && query.membershipNegated(index);
    }
    // The compiled root can differ from query block zero after view/derived
    // expansion; it is the authoritative executable root.
    return status.isOk() ? blocks[0].capture(root) : status;
  }

  Block root() {
    return blocks[0];
  }

  Block block(int index) {
    return index >= 0 && index < blockCount ? blocks[index] : null;
  }

  int blockCount() {
    return blockCount;
  }

  boolean isExplain() {
    return explain;
  }

  boolean isAnalyze() {
    return analyze;
  }

  boolean hasScalarPredicate() {
    return hasScalarPredicate(0);
  }

  boolean hasScalarPredicate(int block) {
    return block >= 0 && block + 1 < blockCount && scalarPredicates[block] >= 0;
  }

  int scalarPredicate() {
    return scalarPredicate(0);
  }

  int scalarPredicate(int block) {
    return block >= 0 && block < blockCount ? scalarPredicates[block] : -1;
  }

  boolean hasExistencePredicate() {
    return hasExistencePredicate(0);
  }

  boolean hasExistencePredicate(int block) {
    return block >= 0 && block + 1 < blockCount && existencePredicates[block] != 0;
  }

  boolean existenceNegated() {
    return existenceNegated(0);
  }

  boolean existenceNegated(int block) {
    return block >= 0 && block < blockCount && existencePredicates[block] < 0;
  }

  boolean hasMembershipPredicate() {
    return hasMembershipPredicate(0);
  }

  boolean hasMembershipPredicate(int block) {
    return block >= 0 && block + 1 < blockCount && membershipPredicates[block] >= 0;
  }

  int membershipPredicate() {
    return membershipPredicate(0);
  }

  int membershipPredicate(int block) {
    return block >= 0 && block < blockCount ? membershipPredicates[block] : -1;
  }

  boolean membershipNegated() {
    return membershipNegated(0);
  }

  boolean membershipNegated(int block) {
    return block >= 0 && block < blockCount && blocks[block].membershipNegated;
  }

  Block scalarCommand() {
    return hasScalarPredicate() ? blocks[1] : null;
  }

  Block existenceCommand() {
    return hasExistencePredicate() ? blocks[1] : null;
  }

  Block membershipCommand() {
    return hasMembershipPredicate() ? blocks[1] : null;
  }

  static final class Block {
    private final Name[] columnNames = new Name[SqlCommand.MAXIMUM_COLUMNS];
    private final Name[] columnTables = new Name[SqlCommand.MAXIMUM_COLUMNS];
    private final Name[] columnOutputs = new Name[SqlCommand.MAXIMUM_COLUMNS];
    private final Name[] columnAliases = new Name[SqlCommand.MAXIMUM_COLUMNS];
    private final boolean[] nullProjections = new boolean[SqlCommand.MAXIMUM_COLUMNS];
    private final Name[] predicateColumns = new Name[SqlCommand.MAXIMUM_PREDICATES];
    private final Name[] predicateTables = new Name[SqlCommand.MAXIMUM_PREDICATES];
    private final Name[] predicateValueColumns = new Name[SqlCommand.MAXIMUM_PREDICATES];
    private final Name[] predicateValueTables = new Name[SqlCommand.MAXIMUM_PREDICATES];
    private final SqlComparison[] comparisons = new SqlComparison[SqlCommand.MAXIMUM_PREDICATES];
    private final int[] predicateTypes = new int[SqlCommand.MAXIMUM_PREDICATES];
    private final long[] predicateValues = new long[SqlCommand.MAXIMUM_PREDICATES];
    private final long[] predicateLowers = new long[SqlCommand.MAXIMUM_PREDICATES];
    private final long[] predicateUppers = new long[SqlCommand.MAXIMUM_PREDICATES];
    private final boolean[] nullPredicates = new boolean[SqlCommand.MAXIMUM_PREDICATES];
    private final boolean[] negatedNullPredicates = new boolean[SqlCommand.MAXIMUM_PREDICATES];
    private final boolean[] columnPredicates = new boolean[SqlCommand.MAXIMUM_PREDICATES];
    private final boolean[] disjunctionPredicates = new boolean[SqlCommand.MAXIMUM_PREDICATES];
    private final int[] membershipCounts = new int[SqlCommand.MAXIMUM_PREDICATES];
    private final boolean[] membershipNulls = new boolean[SqlCommand.MAXIMUM_PREDICATES];
    private final long[] membershipValues = new long[
        SqlCommand.MAXIMUM_PREDICATES * SqlCommand.MAXIMUM_LITERAL_MEMBERSHIP_VALUES];
    private final byte[] textBytes = new byte[SqlCommand.MAXIMUM_TEXT_BYTES];
    private SqlCommandType type;
    private final Name tableName = new Name();
    private final Name tableAlias = new Name();
    private final Name joinTableName = new Name();
    private final Name joinTableAlias = new Name();
    private final Name joinOuterColumnName = new Name();
    private final Name joinInnerColumnName = new Name();
    private final Name orderColumnName = new Name();
    private int columnCount;
    private int predicateCount;
    private int textLength;
    private long rowLimit;
    private boolean selectAll;
    private boolean ordered;
    private boolean descending;
    private boolean leftJoin;
    private boolean groupHaving;
    private SqlComparison groupHavingComparison;
    private long groupHavingValue;
    private boolean membershipNegated;

    Block() {
      for (int index = 0; index < SqlCommand.MAXIMUM_COLUMNS; index++) {
        columnNames[index] = new Name();
        columnTables[index] = new Name();
        columnOutputs[index] = new Name();
        columnAliases[index] = new Name();
        predicateColumns[index] = new Name();
        predicateTables[index] = new Name();
        predicateValueColumns[index] = new Name();
        predicateValueTables[index] = new Name();
      }
    }

    StatusCode capture(SqlCommand source) {
      if (source == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      for (int index = 0; index < SqlCommand.MAXIMUM_COLUMNS; index++) {
        columnNames[index].copyFrom("");
        columnTables[index].copyFrom("");
        columnOutputs[index].copyFrom("");
        columnAliases[index].copyFrom("");
        nullProjections[index] = false;
      }
      for (int index = 0; index < SqlCommand.MAXIMUM_PREDICATES; index++) {
        predicateColumns[index].copyFrom("");
        predicateTables[index].copyFrom("");
        predicateValueColumns[index].copyFrom("");
        predicateValueTables[index].copyFrom("");
        comparisons[index] = null;
        predicateTypes[index] = 0;
        predicateValues[index] = 0;
        predicateLowers[index] = 0;
        predicateUppers[index] = 0;
        nullPredicates[index] = false;
        negatedNullPredicates[index] = false;
        columnPredicates[index] = false;
        disjunctionPredicates[index] = false;
        membershipCounts[index] = 0;
        membershipNulls[index] = false;
      }
      type = source.type();
      tableName.copyFrom(source.tableName());
      tableAlias.copyFrom(source.tableAlias());
      joinTableName.copyFrom(source.joinTableName());
      joinTableAlias.copyFrom(source.joinTableAlias());
      joinOuterColumnName.copyFrom(source.joinOuterColumnName());
      joinInnerColumnName.copyFrom(source.joinInnerColumnName());
      orderColumnName.copyFrom(source.orderColumnName());
      columnCount = source.columnCount();
      predicateCount = source.predicateCount();
      rowLimit = source.rowLimit();
      selectAll = source.isSelectAll();
      ordered = source.isOrdered();
      descending = source.isDescendingOrder();
      leftJoin = source.isLeftJoin();
      groupHaving = source.hasGroupHaving();
      groupHavingComparison = source.groupHavingComparison();
      groupHavingValue = source.groupHavingValue();
      for (int index = 0; index < columnCount; index++) {
        columnNames[index].copyFrom(source.columnName(index));
        columnTables[index].copyFrom(source.columnTableName(index));
        columnOutputs[index].copyFrom(source.columnOutputName(index));
        columnAliases[index].copyFrom(source.columnAlias(index));
        nullProjections[index] = source.isNullProjection(index);
      }
      for (int index = 0; index < predicateCount; index++) {
        predicateColumns[index].copyFrom(source.predicateColumnName(index));
        predicateTables[index].copyFrom(source.predicateTableName(index));
        predicateValueColumns[index].copyFrom(source.predicateValueColumnName(index));
        predicateValueTables[index].copyFrom(source.predicateValueTableName(index));
        comparisons[index] = source.comparison(index);
        predicateTypes[index] = source.predicateTypeDescriptor(index);
        predicateValues[index] = source.predicateValue(index);
        predicateLowers[index] = source.predicateLowerInclusive(index);
        predicateUppers[index] = source.predicateUpperExclusive(index);
        nullPredicates[index] = source.isNullPredicate(index);
        negatedNullPredicates[index] = source.isNullPredicateNegated(index);
        columnPredicates[index] = source.isColumnPredicate(index);
        disjunctionPredicates[index] = source.predicateStartsDisjunction(index);
        membershipCounts[index] = source.literalMembershipCount(index);
        membershipNulls[index] = source.literalMembershipHasNull(index);
        int base = index * SqlCommand.MAXIMUM_LITERAL_MEMBERSHIP_VALUES;
        for (int value = 0; value < membershipCounts[index]; value++) {
          membershipValues[base + value] = source.literalMembershipValue(index, value);
        }
      }
      textLength = 0;
      for (int index = 0; index < predicateCount; index++) {
        textLength = Math.max(textLength, textEnd(source, predicateValues[index]));
        textLength = Math.max(textLength, textEnd(source, predicateLowers[index]));
        textLength = Math.max(textLength, textEnd(source, predicateUppers[index]));
        int base = index * SqlCommand.MAXIMUM_LITERAL_MEMBERSHIP_VALUES;
        for (int value = 0; value < membershipCounts[index]; value++) {
          textLength = Math.max(textLength, textEnd(source, membershipValues[base + value]));
        }
      }
      if (textLength > textBytes.length) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      for (int predicate = 0; predicate < predicateCount; predicate++) {
        copyText(source, predicateValues[predicate]);
        copyText(source, predicateLowers[predicate]);
        copyText(source, predicateUppers[predicate]);
        int base = predicate * SqlCommand.MAXIMUM_LITERAL_MEMBERSHIP_VALUES;
        for (int value = 0; value < membershipCounts[predicate]; value++) {
          copyText(source, membershipValues[base + value]);
        }
      }
      return StatusCode.OK;
    }

    private void copyText(SqlCommand source, long handle) {
      int length = source.textByteLength(handle);
      if (length < 0) {
        return;
      }
      int offset = (int) (handle >>> 32);
      for (int index = 0; index < length; index++) {
        textBytes[offset + index] = source.textByteAt(handle, index);
      }
    }

    private static int textEnd(SqlCommand source, long handle) {
      int length = source.textByteLength(handle);
      return length < 0 ? 0 : (int) (handle >>> 32) + length;
    }

    SqlCommandType type() { return type; }
    CharSequence tableName() { return tableName; }
    CharSequence tableAlias() { return tableAlias; }
    CharSequence joinTableName() { return joinTableName; }
    CharSequence joinTableAlias() { return joinTableAlias; }
    CharSequence joinOuterColumnName() { return joinOuterColumnName; }
    CharSequence joinInnerColumnName() { return joinInnerColumnName; }
    CharSequence orderColumnName() { return orderColumnName; }
    int columnCount() { return columnCount; }
    CharSequence firstColumnName() { return columnName(0); }
    CharSequence columnName(int index) { return columnNames[index]; }
    CharSequence columnTableName(int index) { return columnTables[index]; }
    CharSequence columnOutputName(int index) { return columnOutputs[index]; }
    CharSequence columnAlias(int index) { return columnAliases[index]; }
    boolean isNullProjection(int index) { return nullProjections[index]; }
    int predicateCount() { return predicateCount; }
    CharSequence predicateColumnName(int index) { return predicateColumns[index]; }
    CharSequence predicateTableName(int index) { return predicateTables[index]; }
    CharSequence predicateValueColumnName(int index) { return predicateValueColumns[index]; }
    CharSequence predicateValueTableName(int index) { return predicateValueTables[index]; }
    SqlComparison comparison(int index) { return comparisons[index]; }
    int predicateTypeDescriptor(int index) { return predicateTypes[index]; }
    long predicateValue(int index) { return predicateValues[index]; }
    long predicateLowerInclusive(int index) { return predicateLowers[index]; }
    long predicateUpperExclusive(int index) { return predicateUppers[index]; }
    boolean isNullPredicate(int index) { return nullPredicates[index]; }
    boolean isNullPredicateNegated(int index) { return negatedNullPredicates[index]; }
    boolean isColumnPredicate(int index) { return columnPredicates[index]; }
    boolean predicateStartsDisjunction(int index) { return disjunctionPredicates[index]; }
    boolean hasDisjunction() {
      for (int index = 0; index < predicateCount; index++) {
        if (disjunctionPredicates[index]) return true;
      }
      return false;
    }
    boolean isEqualityPredicate(int index) { return comparisons[index] == SqlComparison.EQUAL; }
    boolean isRangePredicate(int index) { return comparisons[index] == SqlComparison.HALF_OPEN_RANGE; }
    boolean isLiteralMembership(int index) {
      return comparisons[index] == SqlComparison.IN || comparisons[index] == SqlComparison.NOT_IN;
    }
    int literalMembershipCount(int index) { return membershipCounts[index]; }
    long literalMembershipValue(int index, int value) {
      return membershipValues[index * SqlCommand.MAXIMUM_LITERAL_MEMBERSHIP_VALUES + value];
    }
    boolean literalMembershipHasNull(int index) { return membershipNulls[index]; }
    boolean isSelectAll() { return selectAll; }
    boolean isOrdered() { return ordered; }
    boolean isDescendingOrder() { return descending; }
    boolean isLeftJoin() { return leftJoin; }
    boolean hasGroupHaving() { return groupHaving; }
    SqlComparison groupHavingComparison() { return groupHavingComparison; }
    long groupHavingValue() { return groupHavingValue; }
    long rowLimit() { return rowLimit; }
    int textByteLength(long handle) {
      int offset = (int) (handle >>> 32);
      int length = (int) handle;
      return offset >= 0 && length >= 0 && offset <= textLength - length ? length : -1;
    }
    byte textByteAt(long handle, int index) {
      int length = textByteLength(handle);
      return index >= 0 && index < length ? textBytes[(int) (handle >>> 32) + index] : 0;
    }
  }

  private static final class Name implements CharSequence {
    private final char[] value = new char[64];
    private int length;

    void copyFrom(CharSequence source) {
      length = Math.min(source == null ? 0 : source.length(), value.length);
      for (int index = 0; index < length; index++) {
        value[index] = source.charAt(index);
      }
    }

    @Override
    public int length() {
      return length;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length) {
        throw new IndexOutOfBoundsException(index);
      }
      return value[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      if (start < 0 || end < start || end > length) {
        throw new IndexOutOfBoundsException(start);
      }
      return new String(value, start, end - start);
    }

    @Override
    public String toString() {
      return new String(value, 0, length);
    }
  }
}
