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
  private final SqlViewCompiler viewCompiler = new SqlViewCompiler(this, derivedCompiler);
  private int blockCount;
  private int sourcePlanDepth;
  private boolean explain;
  private boolean analyze;
  private boolean blockPipeline;

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
    sourcePlanDepth = 0;
    explain = false;
    analyze = false;
    blockPipeline = false;
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
    sourcePlanDepth = Math.max(sourcePlanDepth, blockCount);
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
    if (hasCardinalityBlock()) {
      StatusCode status = derivedCompiler.compilePipeline(destination);
      blockPipeline = status.isOk();
      return status;
    }
    return derivedCompiler.compile(destination);
  }

  public boolean isBlockPipeline() { return blockPipeline; }

  void markBlockPipeline() { blockPipeline = true; }

  public StatusCode appendRootBlock(SqlCommand command) {
    if (command == null || blockCount != 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    SqlCommand block = nextBlock();
    return block == null ? StatusCode.QUERY_TOO_COMPLEX : block.copyBlockFrom(command);
  }

  public StatusCode compileBlockPipeline(SqlCommand destination) {
    if (destination == null || blockCount < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = derivedCompiler.compilePipeline(destination);
    blockPipeline = status.isOk();
    return status;
  }

  public StatusCode compileCombined(SqlCommand destination) {
    return compileDerived(destination);
  }

  public StatusCode validateAppendedPipeline(int firstBlock) {
    return derivedCompiler.validatePipeline(firstBlock);
  }

  void discardOnPredicates() {
    for (int index = 0; index < blockCount; index++) {
      blocks[index].discardOnPredicates();
    }
  }

  public StatusCode expandRootSelectAllFrom(int sourceBlock) {
    if (blockCount < 2 || sourceBlock <= 0 || sourceBlock >= blockCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return blocks[0].isSelectAll()
        ? blocks[0].expandSelectAllFrom(blocks[sourceBlock]) : StatusCode.OK;
  }

  private boolean hasCardinalityBlock() {
    for (int index = 0; index < blockCount; index++) {
      SqlCommandType type = blocks[index].type();
      if (type == SqlCommandType.JOIN_SCAN
          || type == SqlCommandType.DISTINCT_SCAN
          || type == SqlCommandType.COUNT
          || type == SqlCommandType.COUNT_VALUE
          || type == SqlCommandType.SUM
          || type == SqlCommandType.AVG
          || type == SqlCommandType.MIN
          || type == SqlCommandType.MAX
          || type == SqlCommandType.GROUP_COUNT
          || type == SqlCommandType.GROUP_COUNT_VALUE
          || type == SqlCommandType.GROUP_SUM
          || type == SqlCommandType.GROUP_AVG
          || type == SqlCommandType.GROUP_MIN
          || type == SqlCommandType.GROUP_MAX) return true;
    }
    return false;
  }

  public StatusCode compileView(
      SqlCommand outer,
      SqlCommand view,
      SqlCommand destination) {
    return viewCompiler.compile(
        outer,
        view,
        destination,
        Math.max(1, sourcePlanDepth),
        1,
        explain,
        analyze);
  }

  public StatusCode compileExpandedView(
      SqlCommand outer,
      SqlCommand view,
      SqlCommand destination,
      int outerSourceDepth,
      int viewSourceDepth,
      boolean retainedExplain,
      boolean retainedAnalyze) {
    return viewCompiler.compile(
        outer,
        view,
        destination,
        outerSourceDepth,
        viewSourceDepth,
        retainedExplain,
        retainedAnalyze);
  }

  void setSourceMetadata(int depth, boolean explained, boolean analyzed) {
    sourcePlanDepth = depth;
    explain = explained;
    analyze = analyzed;
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
      if (!validNestedPredicates(blocks[index])
          || SqlDerivedProjectionCompiler.hasComputedProjection(blocks[index])) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
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
        || predicate < block.wherePredicates().leafCount()
            && block.wherePredicates().leafTest(predicate)
                == SqlBooleanPredicateProgram.TEST_COMPARISON
            && block.wherePredicates().comparison(predicate) == SqlComparison.EQUAL;
  }

  private static boolean validNestedPredicates(SqlCommand block) {
    SqlBooleanPredicateProgram predicates = block.wherePredicates();
    for (int node = 0; node < predicates.booleanNodeCount(); node++) {
      int operator = predicates.booleanOperator(node);
      if (operator != SqlBooleanPredicateProgram.BOOLEAN_LEAF
          && operator != SqlBooleanPredicateProgram.BOOLEAN_AND) return false;
    }
    for (int leaf = 0; leaf < predicates.leafCount(); leaf++) {
      int test = predicates.leafTest(leaf);
      if (!rawNestedProgram(
          predicates, leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, true)) return false;
      if (test == SqlBooleanPredicateProgram.TEST_BETWEEN) {
        if (predicates.leafNegated(leaf)
            || !rawNestedLiteral(
                predicates, leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER)
            || !rawNestedLiteral(
                predicates, leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER)) return false;
        continue;
      }
      if (predicates.programNodeCount(
              leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER) != 0
          || predicates.programNodeCount(
              leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER) != 0) return false;
      if (test == SqlBooleanPredicateProgram.TEST_COMPARISON) {
        if (!rawNestedProgram(
            predicates, leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, false)) return false;
        boolean columnRight = predicates.programOperator(
            leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0)
            == SqlScalarExpression.COLUMN;
        if (columnRight && predicates.comparison(leaf) != SqlComparison.EQUAL) return false;
      } else if (test != SqlBooleanPredicateProgram.TEST_NULL
          && test != SqlBooleanPredicateProgram.TEST_TRUTH
          && test != SqlBooleanPredicateProgram.TEST_MEMBERSHIP) return false;
    }
    return true;
  }

  private static boolean rawNestedLiteral(
      SqlBooleanPredicateProgram predicates, int leaf, int program) {
    if (predicates.programNodeCount(leaf, program) != 1) return false;
    int operator = predicates.programOperator(leaf, program, 0);
    return operator == SqlScalarExpression.LITERAL || operator == SqlScalarExpression.NULL;
  }

  private static boolean rawNestedProgram(
      SqlBooleanPredicateProgram predicates,
      int leaf,
      int program,
      boolean required) {
    int count = predicates.programNodeCount(leaf, program);
    if (count == 0) return !required;
    if (count != 1) return false;
    int operator = predicates.programOperator(leaf, program, 0);
    return operator == SqlScalarExpression.COLUMN
        || !required && (operator == SqlScalarExpression.LITERAL
            || operator == SqlScalarExpression.NULL);
  }

  public int blockCount() {
    return blockCount;
  }

  public int sourcePlanDepth() {
    return Math.max(1, sourcePlanDepth);
  }

  public boolean hasNestedTopology() {
    for (int block = 0; block < blockCount; block++) {
      if (hasScalarPredicate(block)
          || hasExistencePredicate(block)
          || hasMembershipPredicate(block)) {
        return true;
      }
    }
    return false;
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
