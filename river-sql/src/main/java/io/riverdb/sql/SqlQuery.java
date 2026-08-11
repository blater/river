package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Caller-owned bounded query-block chain used while compiling nested SELECTs. */
public final class SqlQuery {
  public static final int MAXIMUM_QUERY_BLOCKS = 32;

  private final SqlCommand[] blocks = new SqlCommand[MAXIMUM_QUERY_BLOCKS];
  private int blockCount;

  public SqlQuery() {
    for (int index = 0; index < blocks.length; index++) {
      blocks[index] = new SqlCommand();
    }
  }

  public void reset() {
    for (int index = 0; index < blockCount; index++) {
      blocks[index].reset();
    }
    blockCount = 0;
  }

  SqlCommand nextBlock() {
    if (blockCount >= blocks.length) {
      return null;
    }
    SqlCommand block = blocks[blockCount++];
    block.reset();
    return block;
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
    }
    for (int blockIndex = blockCount - 1; blockIndex >= 0; blockIndex--) {
      SqlCommand block = blocks[blockIndex];
      for (int predicate = 0; predicate < block.predicateCount(); predicate++) {
        SqlIdentifier column = destination.writableNextPredicateColumnName();
        if (column == null) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        column.copyFrom(block.predicateColumnName(predicate));
        destination.appendPredicate(
            block.predicateValue(predicate),
            block.predicateLowerInclusive(predicate),
            block.predicateUpperExclusive(predicate),
            block.isEqualityPredicate(predicate));
      }
    }
    if (root.isOrdered()) {
      destination.writableOrderColumnName().copyFrom(root.orderColumnName());
    }
    destination.setRowLimit(root.rowLimit());
    destination.setScan(0, 0, false);
    return StatusCode.OK;
  }

  private static StatusCode validateOuterBlock(
      SqlCommand outer,
      SqlCommand inner) {
    for (int index = 0; index < outer.columnCount(); index++) {
      if (!validQualifier(outer.columnTableName(index), outer.tableName())
          || !outputContains(inner, outer.columnName(index))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    for (int index = 0; index < outer.predicateCount(); index++) {
      if (!validQualifier(outer.predicateTableName(index), outer.tableName())
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
      if (!validQualifier(base.columnTableName(index), base.tableName())) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    for (int index = 0; index < base.predicateCount(); index++) {
      if (!validQualifier(base.predicateTableName(index), base.tableName())) {
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
      CharSequence expected) {
    return qualifier.length() == 0 || sameName(qualifier, expected);
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
}
