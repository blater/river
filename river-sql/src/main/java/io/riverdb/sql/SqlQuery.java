package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Caller-owned bounded query-block graph used while compiling nested SELECTs. */
public final class SqlQuery {
  public static final int MAXIMUM_QUERY_BLOCKS = 32;
  public static final int MAXIMUM_EDGES = MAXIMUM_QUERY_BLOCKS - 1;

  public static final int SUBQUERY_SCALAR = 1;
  public static final int SUBQUERY_EXISTS = 2;
  public static final int SUBQUERY_MEMBERSHIP = 3;

  private final SqlCommand[] blocks = new SqlCommand[MAXIMUM_QUERY_BLOCKS];
  private final SqlSubqueryGraph graph = new SqlSubqueryGraph();
  private final SqlDerivedQueryCompiler derivedCompiler = new SqlDerivedQueryCompiler(this);
  private final SqlViewCompiler viewCompiler = new SqlViewCompiler(this, derivedCompiler);
  private int blockCount;
  private int sourceBlockCount;
  private int sourcePlanDepth;
  private boolean explain;
  private boolean analyze;
  private boolean blockPipeline;

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
    graph.reset();
    sourceBlockCount = 0;
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
    if (graph.root() < 0 || blockCount - 1 <= graph.root()) {
      sourceBlockCount = Math.max(sourceBlockCount, blockCount);
      sourcePlanDepth = Math.max(sourcePlanDepth, sourceBlockCount);
    }
    block.reset();
    return block;
  }

  void beginNestedGraph(int rootBlock) {
    graph.begin(rootBlock);
  }

  int addSubqueryEdge(int parent, int kind) {
    return graph.append(parent, kind, blockCount);
  }

  void setSubqueryEdgeLeaf(int edge, int leaf) {
    graph.setLeaf(edge, leaf);
  }

  void setSubqueryEdgeChild(int edge, int child) {
    graph.setChild(edge, child, blockCount);
  }


  StatusCode compileDerived(SqlCommand destination) {
    if (destination == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    destination.reset();
    if (graph.count() > 0) {
      StatusCode graphStatus = validateNestedGraph();
      if (!graphStatus.isOk()) return graphStatus;
      graphStatus = validNestedChildren();
      if (!graphStatus.isOk()) return graphStatus;
    }
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
    if (destination == null || sourceBlockCount < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
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

  void discardJoinChains() {
    for (int index = 0; index < blockCount; index++) {
      blocks[index].discardJoinChain();
    }
  }

  public StatusCode expandRootSelectAllFrom(int sourceBlock) {
    if (sourceBlockCount < 2 || sourceBlock <= 0 || sourceBlock >= sourceBlockCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return blocks[0].isSelectAll()
        ? blocks[0].expandSelectAllFrom(blocks[sourceBlock]) : StatusCode.OK;
  }

  private boolean hasCardinalityBlock() {
    if (graph.count() > 0 && sourceBlockCount > 1) return true;
    for (int index = 0; index < sourceBlockCount; index++) {
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

  StatusCode compileNestedGraph(SqlCommand destination) {
    if (destination == null || blockCount < 2 || graph.count() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode graphStatus = validateNestedGraph();
    if (!graphStatus.isOk()) return graphStatus;
    StatusCode children = validNestedChildren();
    if (!children.isOk()) return children;
    destination.reset();
    destination.copyQueryFrom(blocks[0]);
    return destination.finish();
  }

  private StatusCode validNestedChildren() {
    for (int block = sourceBlockCount; block < blockCount; block++) {
      SqlCommand command = blocks[block];
      if (command.type() != SqlCommandType.SCAN
          && command.type() != SqlCommandType.SELECT
          && command.type() != SqlCommandType.JOIN_SCAN
          || command.isSelectAll() || command.columnCount() != 1
          || command.isOrdered()) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
    }
    return StatusCode.OK;
  }

  StatusCode validateNestedGraph() {
    return graph.validate(this);
  }

  public int blockCount() {
    return blockCount;
  }

  public int sourceBlockCount() { return sourceBlockCount; }

  public int edgeCount() { return graph.count(); }

  public int edgeKind(int edge) {
    return graph.kind(edge);
  }

  public int edgeParent(int edge) {
    return graph.parent(edge);
  }

  public int edgeLeaf(int edge) {
    return graph.leaf(edge);
  }

  public int edgeChild(int edge) {
    return graph.child(edge);
  }

  public int blockParent(int block) {
    return graph.blockParent(block);
  }

  public int blockDepth(int block) {
    return graph.blockDepth(block);
  }

  public int nestedPlanDepth() { return graph.maximumDepth(); }

  public int sourcePlanDepth() {
    return Math.max(1, sourcePlanDepth);
  }

  public boolean hasNestedTopology() {
    return graph.count() > 0;
  }

  public SqlCommand block(int index) {
    return index >= 0 && index < blockCount ? blocks[index] : null;
  }

}
