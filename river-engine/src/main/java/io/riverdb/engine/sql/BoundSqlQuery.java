package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlQuery;

/** Statement-lifetime query syntax snapshot consumed after binding. */
final class BoundSqlQuery {
  static final int MAXIMUM_BLOCKS = SqlQuery.MAXIMUM_QUERY_BLOCKS;
  static final int MAXIMUM_PREDICATES = SqlCommand.MAXIMUM_PREDICATES;
  private final Block[] blocks = new Block[MAXIMUM_BLOCKS];
  private final int[] scalarPredicates = new int[MAXIMUM_BLOCKS];
  private final int[] existencePredicates = new int[MAXIMUM_BLOCKS];
  private final int[] membershipPredicates = new int[MAXIMUM_BLOCKS];
  private final SqlNestedTopology topology = new SqlNestedTopology();
  private int blockCount;
  private int sourcePlanDepth;
  private long executableGeneration;
  private long nextGeneration;
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
    for (int index = 0; index < blockCount; index++) {
      blocks[index].resetCaptured();
    }
    blockCount = 0;
    sourcePlanDepth = 0;
    executableGeneration = 0;
    explain = false;
    analyze = false;
    topology.reset();
    for (int index = 0; index < blocks.length; index++) {
      scalarPredicates[index] = -1;
      existencePredicates[index] = 0;
      membershipPredicates[index] = -1;
    }
  }

  void beginBinding(TableDefinition rootTable) {
    executableGeneration = 0;
    topology.reset();
    for (int index = 0; index < blockCount; index++) {
      blocks[index].resetBinding();
    }
    blocks[0].bindRootTable(rootTable);
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

  SqlNestedTopology topology() { return topology; }

  StatusCode capture(SqlCommand root, SqlQuery query) {
    reset();
    if (root == null || query == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    explain = query.isExplain();
    analyze = query.isAnalyze();
    int parserBlockCount = query.blockCount();
    sourcePlanDepth = query.sourcePlanDepth();
    boolean nestedTopology = hasNestedTopology(query, parserBlockCount);
    blockCount = nestedTopology ? Math.max(1, parserBlockCount) : 1;
    StatusCode status = blocks[0].capture(root, nestedTopology);
    if (nestedTopology && status.isOk()) {
      status = captureNestedBlocks(query, parserBlockCount);
    }
    // The compiled root can differ from query block zero after view/derived
    // expansion; it is the authoritative executable root.
    if (status.isOk()) {
      blocks[0].blockIndex = 0;
    }
    return status.isOk() ? blocks[0].capture(root, nestedTopology) : status;
  }

  private StatusCode captureNestedBlocks(SqlQuery query, int parserBlockCount) {
    for (int index = 0; index < parserBlockCount; index++) {
      StatusCode status = blocks[index].capture(query.block(index), true);
      if (!status.isOk()) return status;
      blocks[index].blockIndex = index;
      scalarPredicates[index] = query.scalarPredicate(index);
      existencePredicates[index] = query.hasExistencePredicate(index)
          ? query.existenceNegated(index) ? -1 : 1 : 0;
      membershipPredicates[index] = query.hasMembershipPredicate(index)
          ? query.membershipPredicate(index) : -1;
      blocks[index].membershipNegated = query.hasMembershipPredicate(index)
          && query.membershipNegated(index);
    }
    return StatusCode.OK;
  }

  private static boolean hasNestedTopology(SqlQuery query, int count) {
    for (int index = 0; index < count; index++) {
      if (query.hasScalarPredicate(index) || query.hasExistencePredicate(index)
          || query.hasMembershipPredicate(index)) return true;
    }
    return false;
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

  int sourcePlanDepth() {
    return sourcePlanDepth;
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
    private final SqlBoundName[] columnNames = new SqlBoundName[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlBoundName[] columnTables = new SqlBoundName[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlBoundName[] columnOutputs = new SqlBoundName[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlBoundName[] columnAliases = new SqlBoundName[SqlCommand.MAXIMUM_COLUMNS];
    private final boolean[] nullProjections = new boolean[SqlCommand.MAXIMUM_COLUMNS];
    private final SqlNestedPredicatePlan predicates = new SqlNestedPredicatePlan();
    private SqlCommandType type;
    private final SqlBoundName tableName = new SqlBoundName();
    private final SqlBoundName tableAlias = new SqlBoundName();
    private final SqlBoundName joinTableName = new SqlBoundName();
    private final SqlBoundName joinTableAlias = new SqlBoundName();
    private final SqlBoundName orderColumnName = new SqlBoundName();
    private int columnCount;
    private long rowLimit;
    private boolean selectAll;
    private boolean ordered;
    private boolean descending;
    private boolean leftJoin;
    private boolean membershipNegated;
    private TableDefinition table;
    private boolean ownsTable;
    private int projection = -1;
    private int projectionType;
    private long boundGeneration;
    private int blockIndex;
    private boolean correlated;

    Block() {
      for (int index = 0; index < SqlCommand.MAXIMUM_COLUMNS; index++) {
        columnNames[index] = new SqlBoundName();
        columnTables[index] = new SqlBoundName();
        columnOutputs[index] = new SqlBoundName();
        columnAliases[index] = new SqlBoundName();
      }
    }

    StatusCode capture(SqlCommand source, boolean captureNestedPredicates) {
      if (source == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      predicates.resetCaptured();
      for (int index = 0; index < SqlCommand.MAXIMUM_COLUMNS; index++) {
        columnNames[index].copyFrom("");
        columnTables[index].copyFrom("");
        columnOutputs[index].copyFrom("");
        columnAliases[index].copyFrom("");
        nullProjections[index] = false;
      }
      type = source.type();
      tableName.copyFrom(source.tableName());
      tableAlias.copyFrom(source.tableAlias());
      joinTableName.copyFrom(source.joinTableName());
      joinTableAlias.copyFrom(source.joinTableAlias());
      orderColumnName.copyFrom(source.orderColumnName());
      columnCount = source.columnCount();
      rowLimit = source.rowLimit();
      selectAll = source.isSelectAll();
      ordered = source.isOrdered();
      descending = source.isDescendingOrder();
      leftJoin = source.isLeftJoin();
      for (int index = 0; index < columnCount; index++) {
        columnNames[index].copyFrom(source.columnName(index));
        columnTables[index].copyFrom(source.columnTableName(index));
        columnOutputs[index].copyFrom(source.columnOutputName(index));
        columnAliases[index].copyFrom(source.columnAlias(index));
        nullProjections[index] = source.isNullProjection(index);
      }
      return captureNestedPredicates ? predicates.capture(source) : StatusCode.OK;
    }

    private void resetCaptured() {
      predicates.resetCaptured();
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
      predicates.resetBinding();
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

    void setPredicate(int index, int column, int valueColumn, int valueScope) {
      predicates.setResolved(index, column, valueColumn, valueScope);
      correlated |= valueScope >= 0 && valueScope < blockIndex;
    }

    void publishBinding(long generation) { boundGeneration = generation; }
    boolean isBound(long generation) { return generation != 0 && boundGeneration == generation; }
    TableDefinition table() { return table; }
    int projection() { return projection; }
    int projectionType() { return projectionType; }
    SqlNestedPredicatePlan predicates() { return predicates; }
    int predicateCount() { return predicates.count(); }
    io.riverdb.sql.SqlComparison comparison(int index) {
      return predicates.comparison(index);
    }
    boolean isNullPredicate(int index) { return predicates.isNullTest(index); }
    boolean isNullPredicateNegated(int index) { return predicates.isNullTestNegated(index); }
    boolean isColumnPredicate(int index) { return predicates.isColumnValue(index); }
    int blockIndex() { return blockIndex; }
    boolean isCorrelated() { return correlated; }

    SqlCommandType type() { return type; }
    CharSequence tableName() { return tableName; }
    CharSequence tableAlias() { return tableAlias; }
    CharSequence joinTableName() { return joinTableName; }
    CharSequence joinTableAlias() { return joinTableAlias; }
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
    boolean isLeftJoin() { return leftJoin; }
    long rowLimit() { return rowLimit; }
  }

}
