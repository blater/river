package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Caller-owned bounded query-block chain used while compiling nested SELECTs. */
public final class SqlQuery {
  public static final int MAXIMUM_QUERY_BLOCKS = 32;

  private final SqlCommand[] blocks = new SqlCommand[MAXIMUM_QUERY_BLOCKS];
  private final int[] scalarPredicates = new int[MAXIMUM_QUERY_BLOCKS];
  private final int[] existencePredicates = new int[MAXIMUM_QUERY_BLOCKS];
  private final int[] membershipPredicates = new int[MAXIMUM_QUERY_BLOCKS];
  private final SqlDerivedQueryCompiler derivedCompiler = new SqlDerivedQueryCompiler(this);
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
    if (destination == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    destination.reset();
    return derivedCompiler.compile(destination);
  }

  public StatusCode compileView(
      SqlCommand outer,
      SqlCommand view,
      SqlCommand destination) {
    reset();
    if (destination == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (outer == null || view == null || destination == view) {
      destination.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    SqlCommand outerBlock = nextBlock();
    SqlCommand viewBlock = nextBlock();
    if (outerBlock == null || viewBlock == null) {
      destination.reset();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    outerBlock.copyQueryFrom(outer);
    viewBlock.copyQueryFrom(view);
    destination.reset();
    if (outerBlock.isSelectAll()) {
      StatusCode expansion = outerBlock.expandSelectAllFrom(viewBlock);
      if (!expansion.isOk()) {
        return expansion;
      }
    }
    return compileDerived(destination);
  }

  StatusCode compileScalarPredicate(SqlCommand destination, int predicate) {
    if (destination == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    destination.reset();
    if (blockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    scalarPredicates[0] = predicate;
    return compileNestedPredicates(destination);
  }

  StatusCode compileExistencePredicate(SqlCommand destination, boolean negated) {
    if (destination == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    destination.reset();
    if (blockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    existencePredicates[0] = negated ? -1 : 1;
    return compileNestedPredicates(destination);
  }

  StatusCode compileMembershipPredicate(
      SqlCommand destination,
      int predicate,
      boolean negated) {
    if (destination == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    destination.reset();
    if (blockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    setMembershipPredicate(0, predicate, negated);
    return compileNestedPredicates(destination);
  }

  private StatusCode compileNestedPredicates(SqlCommand destination) {
    for (int index = 0; index < blockCount; index++) {
      if (!validNestedBlock(index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    destination.copyQueryFrom(blocks[0]);
    return destination.finish();
  }

  private boolean validNestedBlock(int index) {
    SqlCommand block = blocks[index];
    if ((block.type() != SqlCommandType.SCAN && block.type() != SqlCommandType.SELECT)
        || block.hasDisjunction()
        || index > 0 && (block.isSelectAll() || block.columnCount() != 1)) {
      return false;
    }
    return index + 1 == blockCount || validNestedEdge(index, block);
  }

  private boolean validNestedEdge(int index, SqlCommand block) {
    int scalar = scalarPredicate(index);
    int membership = membershipPredicate(index);
    int edgeCount = (scalar >= 0 ? 1 : 0)
        + (existencePredicates[index] != 0 ? 1 : 0)
        + (membership >= 0 ? 1 : 0);
    return edgeCount == 1
        && validNestedPredicate(block, scalar)
        && validNestedPredicate(block, membership);
  }

  private static boolean validNestedPredicate(SqlCommand block, int predicate) {
    return predicate < 0
        || predicate < block.predicateCount() && block.isEqualityPredicate(predicate);
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
