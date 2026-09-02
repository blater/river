package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlQuery;

/** Statement-lifetime query syntax snapshot consumed after binding. */
final class BoundSqlQuery {
  static final int MAXIMUM_BLOCKS = SqlQuery.MAXIMUM_QUERY_BLOCKS;
  static final int MAXIMUM_PREDICATES = SqlCommand.MAXIMUM_PREDICATES;
  private final SqlBoundQueryBlocks blocks;
  private final SqlBoundQueryTopology topology;
  private int blockCount;
  private int sourceBlockCount;
  private int edgeCount;
  private int sourcePlanDepth;
  private long executableGeneration;
  private long nextGeneration;
  private boolean explain;
  private boolean analyze;

  BoundSqlQuery() {
    this(new SqlSessionShapeBudget(null));
  }

  BoundSqlQuery(SqlSessionShapeBudget shapeBudget) {
    blocks = new SqlBoundQueryBlocks(shapeBudget);
    topology = new SqlBoundQueryTopology(shapeBudget);
  }

  void reset() {
    for (int index = 0; index < blockCount; index++) {
      blocks.get(index).resetCaptured();
    }
    blockCount = 0;
    sourceBlockCount = 0;
    sourcePlanDepth = 0;
    executableGeneration = 0;
    explain = false;
    analyze = false;
    topology.reset(edgeCount);
    edgeCount = 0;
  }

  void beginBinding(TableDefinition rootTable) {
    beginBinding(rootTable, 0);
  }

  void beginBinding(TableDefinition rootTable, int rootBlock) {
    executableGeneration = 0;
    for (int index = 0; index < blockCount; index++) {
      blocks.get(index).resetBinding();
    }
    if (rootBlock >= 0 && rootBlock < blockCount) {
      blocks.get(rootBlock).bindRootTable(rootTable);
    }
  }

  void publishBinding() {
    long generation = nextGeneration == Long.MAX_VALUE ? 1 : nextGeneration + 1;
    nextGeneration = generation;
    for (int index = 0; index < blockCount; index++) {
      blocks.get(index).publishBinding(generation);
    }
    executableGeneration = generation;
  }

  boolean isExecutable() {
    return executableGeneration != 0;
  }

  long executableGeneration() {
    return executableGeneration;
  }

  StatusCode capture(SqlCommand root, SqlQuery query) {
    reset();
    if (root == null || query == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    explain = query.isExplain();
    analyze = query.isAnalyze();
    int parserBlockCount = query.blockCount();
    sourcePlanDepth = query.sourcePlanDepth();
    boolean nestedTopology = query.hasNestedTopology();
    boolean capturedPipeline = nestedTopology || query.isBlockPipeline();
    int capturedBlocks = capturedPipeline ? Math.max(1, parserBlockCount) : 1;
    StatusCode status = reserveBlocks(capturedBlocks);
    if (!status.isOk()) return status;
    status = topology.capture(query, capturedBlocks);
    if (!status.isOk()) return status;
    if (capturedPipeline) {
      status = captureNestedBlocks(query, parserBlockCount);
    }
    // The compiled root can differ from query block zero after view/derived
    // expansion; it is the authoritative executable root.
    if (status.isOk()) status = blocks.get(0).capture(root);
    if (!status.isOk()) return status;
    blocks.get(0).blockIndex = 0;
    blockCount = capturedBlocks;
    sourceBlockCount = capturedPipeline ? query.sourceBlockCount() : 1;
    edgeCount = query.edgeCount();
    return StatusCode.OK;
  }

  private StatusCode captureNestedBlocks(SqlQuery query, int parserBlockCount) {
    for (int index = 0; index < parserBlockCount; index++) {
      StatusCode status = blocks.get(index).capture(query.block(index));
      if (!status.isOk()) return status;
      blocks.get(index).blockIndex = index;
      topology.depth(index, query.blockDepth(index));
    }
    return StatusCode.OK;
  }

  private StatusCode reserveBlocks(int required) {
    return blocks.reserve(required);
  }

  Block root() {
    return blocks.get(0);
  }

  Block block(int index) {
    return index >= 0 && index < blockCount ? blocks.get(index) : null;
  }

  int blockCount() {
    return blockCount;
  }

  int sourceBlockCount() { return sourceBlockCount; }
  int edgeCount() { return edgeCount; }
  int edgeKind(int edge) { return validEdge(edge) ? topology.kind(edge) : 0; }
  int edgeParent(int edge) { return validEdge(edge) ? topology.parent(edge) : -1; }
  int edgeLeaf(int edge) { return validEdge(edge) ? topology.leaf(edge) : -1; }
  int edgeChild(int edge) { return validEdge(edge) ? topology.child(edge) : -1; }
  SqlComparison edgeComparison(int edge) {
    return validEdge(edge) ? topology.comparison(edge) : null;
  }
  boolean edgeNegated(int edge) {
    return validEdge(edge) && topology.negated(edge);
  }
  int blockParent(int block) {
    for (int edge = 0; edge < edgeCount; edge++) {
      if (topology.child(edge) == block) {
        return topology.parent(edge);
      }
    }
    return -1;
  }
  int blockDepth(int block) {
    return block >= 0 && block < blockCount ? topology.depth(block) : 0;
  }
  void markCorrelated(int block, int scope) {
    if (block >= 0 && block < blockCount) blocks.get(block).markCorrelated(scope);
  }

  int sourcePlanDepth() {
    return sourcePlanDepth;
  }

  int planDepth() {
    int nested = 0;
    for (int block = 0; block < blockCount; block++) {
      nested = Math.max(nested, topology.depth(block));
    }
    return sourcePlanDepth + Math.max(0, nested - 1);
  }

  boolean isExplain() {
    return explain;
  }

  boolean isAnalyze() {
    return analyze;
  }

  private boolean validEdge(int edge) { return edge >= 0 && edge < edgeCount; }

  static final class Block {
    private final SqlBoundQueryBlockSnapshot snapshot;
    private SqlCommandType type;
    private final SqlBoundName tableName = new SqlBoundName();
    private final SqlBoundName tableAlias = new SqlBoundName();
    private final SqlBoundName orderColumnName = new SqlBoundName();
    private int columnCount;
    private long rowLimit;
    private boolean selectAll;
    private boolean ordered;
    private boolean descending;
    private final SqlBoundRoleTables roleTables;
    private int projection = -1;
    private int projectionType;
    private long boundGeneration;
    private int blockIndex;
    private boolean correlated;
    private int correlationScope = -1;

    Block(SqlSessionShapeBudget budget) {
      snapshot = new SqlBoundQueryBlockSnapshot(budget);
      roleTables = new SqlBoundRoleTables(budget);
    }

    StatusCode capture(SqlCommand source) {
      if (source == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      SqlJoinChain joins = source.joinChain();
      int roles = joins == null || joins.stageCount() == 0 ? 1 : joins.roleCount();
      StatusCode status = roleTables.reserve(roles);
      if (status.isOk()) status = snapshot.capture(source);
      if (!status.isOk()) return status;
      boolean joined = source.joinChain() != null;
      type = source.type();
      if (joined) {
        tableName.copyFrom("");
        tableAlias.copyFrom("");
      } else {
        tableName.copyFrom(source.tableName());
        tableAlias.copyFrom(source.tableAlias());
      }
      orderColumnName.copyFrom(source.orderColumnName());
      columnCount = source.columnCount();
      rowLimit = source.rowLimit();
      selectAll = source.isSelectAll();
      ordered = source.isOrdered();
      descending = source.isDescendingOrder();
      return StatusCode.OK;
    }

    private void resetCaptured() {
      snapshot.reset();
      resetBinding();
    }

    void resetBinding() {
      roleTables.reset();
      projection = -1;
      projectionType = 0;
      boundGeneration = 0;
      correlated = false;
      correlationScope = -1;
    }

    void bindRootTable(TableDefinition rootTable) {
      bindRoleTable(0, rootTable);
    }

    TableDefinition writableTable(int role) {
      return roleTables.writable(role);
    }

    void bindRoleTable(int role, TableDefinition definition) {
      roleTables.bind(role, definition);
    }

    void markDescriptorRole(int role) { roleTables.markDescriptor(role); }
    boolean descriptorRole(int role) { return roleTables.descriptor(role, roleCount()); }

    void setProjection(int column, int descriptor) {
      projection = column;
      projectionType = descriptor;
    }

    void markCorrelated(int scope) {
      correlated = true;
      correlationScope = correlationScope < 0 ? scope : Math.min(correlationScope, scope);
    }

    void publishBinding(long generation) { boundGeneration = generation; }
    boolean isBound(long generation) { return generation != 0 && boundGeneration == generation; }
    TableDefinition table() { return table(0); }
    TableDefinition table(int role) {
      return roleTables.get(role, roleCount());
    }
    int roleCount() {
      SqlJoinChain join = joinChain();
      return join == null ? 1 : join.roleCount();
    }
    CharSequence roleTableName(int role) {
      SqlJoinChain join = joinChain();
      return join == null ? role == 0 ? tableName : ""
          : role >= 0 && role < join.roleCount() ? join.tableName(role) : "";
    }
    CharSequence roleAlias(int role) {
      SqlJoinChain join = joinChain();
      return join == null ? role == 0 ? tableAlias : ""
          : role >= 0 && role < join.roleCount() ? join.alias(role) : "";
    }
    int projection() { return projection; }
    int projectionType() { return projectionType; }
    int blockIndex() { return blockIndex; }
    boolean isCorrelated() { return correlated; }
    int correlationScope() { return correlationScope; }

    SqlCommandType type() { return type; }
    CharSequence tableName() {
      SqlJoinChain join = joinChain();
      return join == null ? tableName : join.tableName(0);
    }
    CharSequence tableAlias() {
      SqlJoinChain join = joinChain();
      return join == null ? tableAlias : join.alias(0);
    }
    SqlJoinChain joinChain() {
      SqlJoinChain join = snapshot.join();
      return join != null && join.stageCount() > 0 ? join : null;
    }
    CharSequence orderColumnName() { return orderColumnName; }
    int columnCount() { return columnCount; }
    CharSequence firstColumnName() { return columnName(0); }
    CharSequence columnName(int index) { return snapshot.name(index); }
    CharSequence columnTableName(int index) { return snapshot.table(index); }
    CharSequence columnOutputName(int index) { return snapshot.output(index); }
    CharSequence columnAlias(int index) { return snapshot.alias(index); }
    boolean isNullProjection(int index) { return snapshot.isNull(index); }
    boolean isSelectAll() { return selectAll; }
    boolean isOrdered() { return ordered; }
    boolean isDescendingOrder() { return descending; }
    long rowLimit() { return rowLimit; }
  }

}
