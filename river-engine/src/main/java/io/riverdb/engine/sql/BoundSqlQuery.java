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
  private final Block[] blocks = new Block[MAXIMUM_BLOCKS];
  private final byte[] edgeKinds = new byte[SqlQuery.MAXIMUM_EDGES];
  private final byte[] edgeParents = new byte[SqlQuery.MAXIMUM_EDGES];
  private final byte[] edgeLeaves = new byte[SqlQuery.MAXIMUM_EDGES];
  private final byte[] edgeChildren = new byte[SqlQuery.MAXIMUM_EDGES];
  private final boolean[] edgeNegated = new boolean[SqlQuery.MAXIMUM_EDGES];
  private final SqlComparison[] edgeComparisons = new SqlComparison[SqlQuery.MAXIMUM_EDGES];
  private final byte[] blockDepths = new byte[MAXIMUM_BLOCKS];
  private int blockCount;
  private int sourceBlockCount;
  private int edgeCount;
  private int sourcePlanDepth;
  private long executableGeneration;
  private long nextGeneration;
  private boolean explain;
  private boolean analyze;

  BoundSqlQuery() {
    for (int index = 0; index < blocks.length; index++) {
      blocks[index] = new Block();
    }
  }

  void reset() {
    for (int index = 0; index < blockCount; index++) {
      blocks[index].resetCaptured();
      blockDepths[index] = 0;
    }
    blockCount = 0;
    sourceBlockCount = 0;
    sourcePlanDepth = 0;
    executableGeneration = 0;
    explain = false;
    analyze = false;
    for (int edge = 0; edge < edgeCount; edge++) {
      edgeKinds[edge] = 0;
      edgeParents[edge] = 0;
      edgeLeaves[edge] = 0;
      edgeChildren[edge] = 0;
      edgeNegated[edge] = false;
      edgeComparisons[edge] = null;
    }
    edgeCount = 0;
  }

  void beginBinding(TableDefinition rootTable) {
    beginBinding(rootTable, 0);
  }

  void beginBinding(TableDefinition rootTable, int rootBlock) {
    executableGeneration = 0;
    for (int index = 0; index < blockCount; index++) {
      blocks[index].resetBinding();
    }
    if (rootBlock >= 0 && rootBlock < blockCount) {
      blocks[rootBlock].bindRootTable(rootTable);
    }
  }

  void publishBinding() {
    long generation = nextGeneration == Long.MAX_VALUE ? 1 : nextGeneration + 1;
    nextGeneration = generation;
    for (int index = 0; index < blockCount; index++) {
      blocks[index].publishBinding(generation);
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
    blockCount = nestedTopology ? Math.max(1, parserBlockCount) : 1;
    sourceBlockCount = nestedTopology ? query.sourceBlockCount() : 1;
    captureEdges(query);
    StatusCode status = blocks[0].capture(root);
    if (nestedTopology && status.isOk()) {
      status = captureNestedBlocks(query, parserBlockCount);
    }
    // The compiled root can differ from query block zero after view/derived
    // expansion; it is the authoritative executable root.
    if (status.isOk()) {
      blocks[0].blockIndex = 0;
    }
    return status.isOk() ? blocks[0].capture(root) : status;
  }

  private StatusCode captureNestedBlocks(SqlQuery query, int parserBlockCount) {
    for (int index = 0; index < parserBlockCount; index++) {
      StatusCode status = blocks[index].capture(query.block(index));
      if (!status.isOk()) return status;
      blocks[index].blockIndex = index;
      blockDepths[index] = (byte) query.blockDepth(index);
    }
    return StatusCode.OK;
  }

  private void captureEdges(SqlQuery query) {
    edgeCount = query.edgeCount();
    for (int edge = 0; edge < edgeCount; edge++) {
      edgeKinds[edge] = (byte) query.edgeKind(edge);
      edgeParents[edge] = (byte) query.edgeParent(edge);
      edgeLeaves[edge] = (byte) query.edgeLeaf(edge);
      edgeChildren[edge] = (byte) query.edgeChild(edge);
      edgeNegated[edge] = query.block(query.edgeParent(edge))
          .wherePredicates().leafNegated(query.edgeLeaf(edge));
      edgeComparisons[edge] = query.block(query.edgeParent(edge))
          .wherePredicates().comparison(query.edgeLeaf(edge));
    }
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

  int sourceBlockCount() { return sourceBlockCount; }
  int edgeCount() { return edgeCount; }
  int edgeKind(int edge) { return validEdge(edge) ? Byte.toUnsignedInt(edgeKinds[edge]) : 0; }
  int edgeParent(int edge) { return validEdge(edge) ? Byte.toUnsignedInt(edgeParents[edge]) : -1; }
  int edgeLeaf(int edge) { return validEdge(edge) ? edgeLeaves[edge] : -1; }
  int edgeChild(int edge) { return validEdge(edge) ? edgeChildren[edge] : -1; }
  SqlComparison edgeComparison(int edge) {
    return validEdge(edge) ? edgeComparisons[edge] : null;
  }
  boolean edgeNegated(int edge) {
    return validEdge(edge) && edgeNegated[edge];
  }
  int blockParent(int block) {
    for (int edge = 0; edge < edgeCount; edge++) {
      if (Byte.toUnsignedInt(edgeChildren[edge]) == block) {
        return Byte.toUnsignedInt(edgeParents[edge]);
      }
    }
    return -1;
  }
  int blockDepth(int block) {
    return block >= 0 && block < blockCount ? Byte.toUnsignedInt(blockDepths[block]) : 0;
  }
  void markCorrelated(int block, int scope) {
    if (block >= 0 && block < blockCount) blocks[block].markCorrelated(scope);
  }

  int sourcePlanDepth() {
    return sourcePlanDepth;
  }

  int planDepth() {
    int nested = 0;
    for (int block = 0; block < blockCount; block++) {
      nested = Math.max(nested, Byte.toUnsignedInt(blockDepths[block]));
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
    private final SqlBoundName[] columnNames = new SqlBoundName[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlBoundName[] columnTables = new SqlBoundName[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlBoundName[] columnOutputs = new SqlBoundName[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlBoundName[] columnAliases = new SqlBoundName[SqlCommand.MAXIMUM_COLUMNS];
    private final boolean[] nullProjections = new boolean[SqlCommand.MAXIMUM_COLUMNS];
    private SqlCommandType type;
    private final SqlBoundName tableName = new SqlBoundName();
    private final SqlBoundName tableAlias = new SqlBoundName();
    private SqlJoinChain joinChain;
    private final SqlBoundName orderColumnName = new SqlBoundName();
    private int columnCount;
    private long rowLimit;
    private boolean selectAll;
    private boolean ordered;
    private boolean descending;
    private TableDefinition table;
    private boolean ownsTable;
    private int projection = -1;
    private int projectionType;
    private long boundGeneration;
    private int blockIndex;
    private boolean correlated;
    private int correlationScope = -1;

    Block() {
      for (int index = 0; index < SqlCommand.MAXIMUM_COLUMNS; index++) {
        columnNames[index] = new SqlBoundName();
        columnTables[index] = new SqlBoundName();
        columnOutputs[index] = new SqlBoundName();
        columnAliases[index] = new SqlBoundName();
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
      type = source.type();
      boolean joined = source.joinChain() != null;
      if (joined) {
        tableName.copyFrom("");
        tableAlias.copyFrom("");
      } else {
        tableName.copyFrom(source.tableName());
        tableAlias.copyFrom(source.tableAlias());
      }
      if (source.joinChain() != null) {
        if (joinChain == null) joinChain = new SqlJoinChain();
        joinChain.copyFrom(source.joinChain());
      } else if (joinChain != null) joinChain.reset();
      orderColumnName.copyFrom(source.orderColumnName());
      columnCount = source.columnCount();
      rowLimit = source.rowLimit();
      selectAll = source.isSelectAll();
      ordered = source.isOrdered();
      descending = source.isDescendingOrder();
      for (int index = 0; index < columnCount; index++) {
        columnNames[index].copyFrom(source.columnName(index));
        columnTables[index].copyFrom(source.columnTableName(index));
        columnOutputs[index].copyFrom(source.columnOutputName(index));
        columnAliases[index].copyFrom(source.columnAlias(index));
        nullProjections[index] = source.isNullProjection(index);
      }
      return StatusCode.OK;
    }

    private void resetCaptured() {
      if (joinChain != null) joinChain.reset();
      resetBinding();
    }

    void resetBinding() {
      if (ownsTable && table != null) {
        table.reset();
      }
      if (!ownsTable) {
        table = null;
      }
      projection = -1;
      projectionType = 0;
      boundGeneration = 0;
      correlated = false;
      correlationScope = -1;
    }

    void bindRootTable(TableDefinition rootTable) {
      table = rootTable;
      ownsTable = false;
    }

    TableDefinition writableTable() {
      if (table == null || !ownsTable) {
        table = new TableDefinition();
        ownsTable = true;
      }
      table.reset();
      return table;
    }

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
    TableDefinition table() { return table; }
    int projection() { return projection; }
    int projectionType() { return projectionType; }
    int blockIndex() { return blockIndex; }
    boolean isCorrelated() { return correlated; }
    int correlationScope() { return correlationScope; }

    SqlCommandType type() { return type; }
    CharSequence tableName() {
      return joinChain() == null ? tableName : joinChain.tableName(0);
    }
    CharSequence tableAlias() {
      return joinChain() == null ? tableAlias : joinChain.alias(0);
    }
    SqlJoinChain joinChain() {
      return joinChain != null && joinChain.stageCount() > 0 ? joinChain : null;
    }
    CharSequence orderColumnName() { return orderColumnName; }
    int columnCount() { return columnCount; }
    CharSequence firstColumnName() { return columnName(0); }
    CharSequence columnName(int index) { return columnNames[index]; }
    CharSequence columnTableName(int index) { return columnTables[index]; }
    CharSequence columnOutputName(int index) { return columnOutputs[index]; }
    CharSequence columnAlias(int index) { return columnAliases[index]; }
    boolean isNullProjection(int index) { return nullProjections[index]; }
    boolean isSelectAll() { return selectAll; }
    boolean isOrdered() { return ordered; }
    boolean isDescendingOrder() { return descending; }
    long rowLimit() { return rowLimit; }
  }

}
