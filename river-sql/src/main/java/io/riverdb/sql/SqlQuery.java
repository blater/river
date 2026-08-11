package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Caller-owned bounded query-block chain used while compiling nested SELECTs. */
public final class SqlQuery {
  public static final int MAXIMUM_QUERY_BLOCKS = 32;

  private final SqlCommand[] blocks = new SqlCommand[MAXIMUM_QUERY_BLOCKS];
  private final int[] scalarPredicates = new int[MAXIMUM_QUERY_BLOCKS];
  private final int[] existencePredicates = new int[MAXIMUM_QUERY_BLOCKS];
  private int blockCount;
  private int membershipPredicate;

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
    }
    blockCount = 0;
    membershipPredicate = 0;
  }

  SqlCommand nextBlock() {
    if (blockCount >= blocks.length) {
      return null;
    }
    SqlCommand block = blocks[blockCount++];
    block.reset();
    scalarPredicates[blockCount - 1] = -1;
    existencePredicates[blockCount - 1] = 0;
    return block;
  }

  void setScalarPredicate(int block, int predicate) {
    scalarPredicates[block] = predicate;
  }

  void setExistencePredicate(int block, boolean negated) {
    existencePredicates[block] = negated ? -1 : 1;
  }

  StatusCode compileDerived(SqlCommand destination) {
    if (destination == null || blockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < blockCount; index++) {
      SqlCommand block = blocks[index];
      if (block.type() != SqlCommandType.SCAN
          && block.type() != SqlCommandType.SELECT
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
      column.copyFrom(root.columnName(index));
      if (root.isNullProjection(index)) {
        destination.markLastProjectionNull();
      }
    }
    for (int blockIndex = blockCount - 1; blockIndex >= 0; blockIndex--) {
      SqlCommand block = blocks[blockIndex];
      for (int predicate = 0; predicate < block.predicateCount(); predicate++) {
        SqlIdentifier column = destination.writableNextPredicateColumnName();
        if (column == null) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        column.copyFrom(block.predicateColumnName(predicate));
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
          destination.appendPredicate(
              block.predicateValue(predicate),
              block.predicateLowerInclusive(predicate),
              block.predicateUpperExclusive(predicate),
              block.isEqualityPredicate(predicate));
        }
      }
    }
    if (root.isOrdered()) {
      destination.writableOrderColumnName().copyFrom(root.orderColumnName());
    }
    destination.setRowLimit(root.rowLimit());
    destination.setScan(0, 0, false);
    return StatusCode.OK;
  }

  StatusCode compileScalarPredicate(SqlCommand destination, int predicate) {
    if (destination == null
        || blockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    scalarPredicates[0] = predicate;
    for (int index = 0; index < blockCount; index++) {
      SqlCommand block = blocks[index];
      if (block.type() != SqlCommandType.SCAN
          && block.type() != SqlCommandType.SELECT
          || index > 0 && (block.isSelectAll() || block.columnCount() != 1)
          || index + 1 < blockCount
              && (scalarPredicates[index] < 0
                  || scalarPredicates[index] >= block.predicateCount()
                  || !block.isEqualityPredicate(scalarPredicates[index]))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    destination.copyQueryFrom(blocks[0]);
    return StatusCode.OK;
  }

  StatusCode compileExistencePredicate(SqlCommand destination, boolean negated) {
    if (destination == null
        || blockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    existencePredicates[0] = negated ? -1 : 1;
    for (int index = 0; index < blockCount; index++) {
      SqlCommand block = blocks[index];
      if (block.type() != SqlCommandType.SCAN
          && block.type() != SqlCommandType.SELECT
          || index > 0 && (block.isSelectAll() || block.columnCount() != 1)
          || index + 1 < blockCount && existencePredicates[index] == 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    destination.copyQueryFrom(blocks[0]);
    return StatusCode.OK;
  }

  StatusCode compileMembershipPredicate(
      SqlCommand destination,
      int predicate,
      boolean negated) {
    if (destination == null
        || blockCount != 2
        || predicate < 0
        || predicate >= blocks[0].predicateCount()
        || !blocks[0].isEqualityPredicate(predicate)
        || blocks[0].type() != SqlCommandType.SCAN
            && blocks[0].type() != SqlCommandType.SELECT) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    SqlCommand nested = blocks[1];
    if (nested.type() != SqlCommandType.SCAN
        && nested.type() != SqlCommandType.SELECT
        || nested.isSelectAll()
        || nested.columnCount() != 1) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    destination.copyQueryFrom(blocks[0]);
    membershipPredicate = negated ? -predicate - 1 : predicate + 1;
    return StatusCode.OK;
  }

  private static StatusCode validateOuterBlock(
      SqlCommand outer,
      SqlCommand inner) {
    for (int index = 0; index < outer.columnCount(); index++) {
      if (!validQualifier(outer.columnTableName(index), outer)
          || !outputContains(inner, outer.columnName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    for (int index = 0; index < outer.predicateCount(); index++) {
      if (!validQualifier(outer.predicateTableName(index), outer)
          || !outputContains(inner, outer.predicateColumnName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    if (outer.isOrdered() && !outputContains(inner, outer.orderColumnName())) {
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
    for (int index = 0; index < command.columnCount(); index++) {
      if (sameName(command.columnName(index), name)) {
        return true;
      }
    }
    return false;
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
    return blockCount > 1 && scalarPredicates[0] >= 0;
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
    return blockCount > 1 && existencePredicates[0] != 0;
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
    return membershipPredicate != 0;
  }

  public boolean membershipNegated() {
    return membershipPredicate < 0;
  }

  public int membershipPredicate() {
    return Math.abs(membershipPredicate) - 1;
  }

  public SqlCommand membershipCommand() {
    return hasMembershipPredicate() ? blocks[1] : null;
  }
}
