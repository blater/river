package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Caller-owned bounded query-block chain used while compiling nested SELECTs. */
public final class SqlQuery {
  public static final int MAXIMUM_QUERY_BLOCKS = 32;

  private final SqlCommand[] blocks = new SqlCommand[MAXIMUM_QUERY_BLOCKS];
  private final int[] scalarPredicates = new int[MAXIMUM_QUERY_BLOCKS];
  private final int[] existencePredicates = new int[MAXIMUM_QUERY_BLOCKS];
  private final int[] membershipPredicates = new int[MAXIMUM_QUERY_BLOCKS];
  private int blockCount;
  private boolean explain;
  private boolean analyze;

  public SqlQuery() {
    for (int index = 0; index < blocks.length; index++) {
      blocks[index] = new SqlCommand();
      scalarPredicates[index] = -1;
    }
  }

  public void reset() {
    for (int index = 0; index < blockCount; index++) {
      blocks[index].reset();
      scalarPredicates[index] = -1;
      existencePredicates[index] = 0;
      membershipPredicates[index] = 0;
    }
    blockCount = 0;
    explain = false;
    analyze = false;
  }

  void setExplain(boolean analyzeQuery) {
    explain = true;
    analyze = analyzeQuery;
  }

  public boolean isExplain() {
    return explain;
  }

  public boolean isAnalyze() {
    return analyze;
  }

  SqlCommand nextBlock() {
    if (blockCount >= blocks.length) {
      return null;
    }
    SqlCommand block = blocks[blockCount++];
    block.reset();
    scalarPredicates[blockCount - 1] = -1;
    existencePredicates[blockCount - 1] = 0;
    membershipPredicates[blockCount - 1] = 0;
    return block;
  }

  void setScalarPredicate(int block, int predicate) {
    scalarPredicates[block] = predicate;
  }

  void setExistencePredicate(int block, boolean negated) {
    existencePredicates[block] = negated ? -1 : 1;
  }

  void setMembershipPredicate(int block, int predicate, boolean negated) {
    membershipPredicates[block] = negated ? -predicate - 1 : predicate + 1;
  }

  StatusCode compileDerived(SqlCommand destination) {
    if (destination == null || blockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < blockCount; index++) {
      SqlCommand block = blocks[index];
      if (block.type() != SqlCommandType.SCAN
          && block.type() != SqlCommandType.SELECT
          || block.hasDisjunction()
          || block.isSelectAll()
          || block.columnCount() <= 0
          || index > 0 && block.rowLimit() != Long.MAX_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (index + 1 < blockCount) {
        StatusCode visibility = validateOuterBlock(block, blocks[index + 1]);
        if (!visibility.isOk()) {
          return visibility;
        }
      } else {
        StatusCode qualifiers = validateBaseBlock(block);
        if (!qualifiers.isOk()) {
          return qualifiers;
        }
      }
    }
    destination.reset();
    SqlCommand root = blocks[0];
    SqlCommand base = blocks[blockCount - 1];
    destination.writableTableName().copyFrom(base.tableName());
    for (int index = 0; index < root.columnCount(); index++) {
      SqlIdentifier column = destination.writableNextColumnName();
      if (column == null) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      int resolvedNull = root.isNullProjection(index)
          ? 1 : copyResolvedColumn(0, root.columnName(index), column);
      if (resolvedNull < 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (resolvedNull > 0) {
        column.copyFrom(root.columnName(index));
        destination.markLastProjectionNull();
      }
      destination.writableColumnAlias(index).copyFrom(
          root.columnOutputName(index));
    }
    for (int blockIndex = blockCount - 1; blockIndex >= 0; blockIndex--) {
      SqlCommand block = blocks[blockIndex];
      for (int predicate = 0; predicate < block.predicateCount(); predicate++) {
        SqlIdentifier column = destination.writableNextPredicateColumnName();
        if (column == null) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        int resolvedNull = copyResolvedColumn(
            blockIndex, block.predicateColumnName(predicate), column);
        if (resolvedNull != 0) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        if (block.isNullPredicate(predicate)) {
          destination.appendNullPredicate(
              block.isNullPredicateNegated(predicate));
        } else if (block.isColumnPredicate(predicate)) {
          destination.writableNextPredicateValueTableName().copyFrom(
              block.predicateValueTableName(predicate));
          destination.writableNextPredicateValueColumnName().copyFrom(
              block.predicateValueColumnName(predicate));
          destination.appendColumnPredicate();
        } else {
          if (block.isRangePredicate(predicate)) {
            destination.appendPredicate(
                block.predicateValue(predicate),
                block.predicateLowerInclusive(predicate),
                block.predicateUpperExclusive(predicate),
                false);
          } else {
            destination.appendComparison(
                block.predicateValue(predicate),
                block.comparison(predicate));
          }
        }
      }
    }
    if (root.isOrdered()) {
      int projection = outputIndex(root, root.orderColumnName());
      CharSequence ordered = projection >= 0
          ? root.columnName(projection) : root.orderColumnName();
      int resolvedNull = copyResolvedColumn(
          0, ordered, destination.writableOrderColumnName());
      if (resolvedNull != 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      destination.setDescendingOrder(root.isDescendingOrder());
    }
    destination.setRowLimit(root.rowLimit());
    destination.setScan(0, 0, false);
    return StatusCode.OK;
  }

  public StatusCode compileView(
      SqlCommand outer,
      SqlCommand view,
      SqlCommand destination) {
    if (outer == null
        || view == null
        || destination == null
        || destination == outer
        || destination == view) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    reset();
    SqlCommand outerBlock = nextBlock();
    SqlCommand viewBlock = nextBlock();
    if (outerBlock == null || viewBlock == null) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    outerBlock.copyQueryFrom(outer);
    viewBlock.copyQueryFrom(view);
    return compileDerived(destination);
  }

  StatusCode compileScalarPredicate(SqlCommand destination, int predicate) {
    if (destination == null
        || blockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    scalarPredicates[0] = predicate;
    return compileNestedPredicates(destination);
  }

  StatusCode compileExistencePredicate(SqlCommand destination, boolean negated) {
    if (destination == null
        || blockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    existencePredicates[0] = negated ? -1 : 1;
    return compileNestedPredicates(destination);
  }

  StatusCode compileMembershipPredicate(
      SqlCommand destination,
      int predicate,
      boolean negated) {
    if (destination == null
        || blockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    setMembershipPredicate(0, predicate, negated);
    return compileNestedPredicates(destination);
  }

  private StatusCode compileNestedPredicates(SqlCommand destination) {
    for (int index = 0; index < blockCount; index++) {
      SqlCommand block = blocks[index];
      int scalar = scalarPredicate(index);
      int membership = membershipPredicate(index);
      int edgeCount = (scalar >= 0 ? 1 : 0)
          + (existencePredicates[index] != 0 ? 1 : 0)
          + (membership >= 0 ? 1 : 0);
      if (block.type() != SqlCommandType.SCAN
          && block.type() != SqlCommandType.SELECT
          || block.hasDisjunction()
          || index > 0 && (block.isSelectAll() || block.columnCount() != 1)
          || index + 1 < blockCount
              && (edgeCount != 1
                  || scalar >= 0
                      && (scalar >= block.predicateCount()
                          || !block.isEqualityPredicate(scalar))
                  || membership >= 0
                      && (membership >= block.predicateCount()
                          || !block.isEqualityPredicate(membership)))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    destination.copyQueryFrom(blocks[0]);
    return StatusCode.OK;
  }

  private static StatusCode validateOuterBlock(
      SqlCommand outer,
      SqlCommand inner) {
    for (int index = 0; index < outer.columnCount(); index++) {
      if (!validQualifier(outer.columnTableName(index), outer)
          || !outer.isNullProjection(index)
              && !outputContains(inner, outer.columnName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    for (int index = 0; index < outer.predicateCount(); index++) {
      if (!validQualifier(outer.predicateTableName(index), outer)
          || !outputContains(inner, outer.predicateColumnName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    if (outer.isOrdered()
        && !outputContains(inner, outer.orderColumnName())
        && outputIndex(outer, outer.orderColumnName()) < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  private static StatusCode validateBaseBlock(SqlCommand base) {
    for (int index = 0; index < base.columnCount(); index++) {
      if (!validQualifier(base.columnTableName(index), base)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    for (int index = 0; index < base.predicateCount(); index++) {
      if (!validQualifier(base.predicateTableName(index), base)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return StatusCode.OK;
  }

  private static boolean outputContains(SqlCommand command, CharSequence name) {
    return outputIndex(command, name) >= 0;
  }

  private static int outputIndex(SqlCommand command, CharSequence name) {
    int found = -1;
    for (int index = 0; index < command.columnCount(); index++) {
      if (sameName(command.columnOutputName(index), name)) {
        if (found >= 0) {
          return -2;
        }
        found = index;
      }
    }
    return found;
  }

  private int copyResolvedColumn(
      int blockIndex,
      CharSequence name,
      SqlIdentifier destination) {
    CharSequence resolved = name;
    for (int sourceIndex = blockIndex + 1;
        sourceIndex < blockCount;
        sourceIndex++) {
      SqlCommand source = blocks[sourceIndex];
      int projection = outputIndex(source, resolved);
      if (projection < 0) {
        return -1;
      }
      if (source.isNullProjection(projection)) {
        return 1;
      }
      resolved = source.columnName(projection);
    }
    destination.copyFrom(resolved);
    return 0;
  }

  private static boolean validQualifier(
      CharSequence qualifier,
      SqlCommand command) {
    return qualifier.length() == 0
        || sameName(qualifier, command.tableName())
        || command.tableAlias().length() > 0
            && sameName(qualifier, command.tableAlias());
  }

  private static boolean sameName(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) {
      return false;
    }
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) {
        return false;
      }
    }
    return true;
  }

  public int blockCount() {
    return blockCount;
  }

  public SqlCommand block(int index) {
    return index >= 0 && index < blockCount ? blocks[index] : null;
  }

  public boolean hasScalarPredicate() {
    return hasScalarPredicate(0);
  }

  public boolean hasScalarPredicate(int block) {
    return block >= 0 && block + 1 < blockCount && scalarPredicates[block] >= 0;
  }

  public int scalarPredicate() {
    return scalarPredicate(0);
  }

  public int scalarPredicate(int block) {
    return block >= 0 && block < blockCount ? scalarPredicates[block] : -1;
  }

  public SqlCommand scalarCommand() {
    return hasScalarPredicate() ? blocks[1] : null;
  }

  public StatusCode bindScalarValue(SqlCommand destination, long value) {
    return bindScalarValue(destination, 0, value);
  }

  public StatusCode bindScalarValue(
      SqlCommand destination,
      int block,
      long value) {
    int predicate = scalarPredicate(block);
    if (destination == null || predicate < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    destination.setPredicateValue(predicate, value);
    return StatusCode.OK;
  }

  public boolean hasExistencePredicate() {
    return hasExistencePredicate(0);
  }

  public boolean hasExistencePredicate(int block) {
    return block >= 0
        && block + 1 < blockCount
        && existencePredicates[block] != 0;
  }

  public boolean existenceNegated() {
    return existenceNegated(0);
  }

  public boolean existenceNegated(int block) {
    return block >= 0
        && block < blockCount
        && existencePredicates[block] < 0;
  }

  public SqlCommand existenceCommand() {
    return hasExistencePredicate() ? blocks[1] : null;
  }

  public boolean hasMembershipPredicate() {
    return hasMembershipPredicate(0);
  }

  public boolean hasMembershipPredicate(int block) {
    return block >= 0
        && block + 1 < blockCount
        && membershipPredicates[block] != 0;
  }

  public boolean membershipNegated() {
    return membershipNegated(0);
  }

  public boolean membershipNegated(int block) {
    return block >= 0
        && block < blockCount
        && membershipPredicates[block] < 0;
  }

  public int membershipPredicate() {
    return membershipPredicate(0);
  }

  public int membershipPredicate(int block) {
    return block >= 0 && block < blockCount
        && membershipPredicates[block] != 0
            ? Math.abs(membershipPredicates[block]) - 1 : -1;
  }

  public SqlCommand membershipCommand() {
    return hasMembershipPredicate() ? blocks[1] : null;
  }
}
