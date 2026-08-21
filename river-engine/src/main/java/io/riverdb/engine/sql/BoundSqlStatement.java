package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlJoinChain;
import io.riverdb.sql.SqlQuery;

/** Reusable catalog-resolved state borrowed by one statement execution. */
final class BoundSqlStatement {
  static final int NULL_PROJECTION = Integer.MIN_VALUE;

  final SqlCommand command = new SqlCommand();
  final SqlQuery query = new SqlQuery();
  final BoundSqlQuery executableQuery = new BoundSqlQuery();
  final SqlBoundProjectionPrograms projectionPrograms =
      new SqlBoundProjectionPrograms();
  final SqlBoundAggregateSet aggregates = new SqlBoundAggregateSet();
  final SqlBoundBooleanPredicateProgram whereBoolean =
      new SqlBoundBooleanPredicateProgram();
  private SqlBoundBooleanPredicateProgram[] onBooleans;
  final SqlBoundBooleanPredicateProgram havingBoolean =
      new SqlBoundBooleanPredicateProgram();
  private SqlBoundBlockPlans blockPlans;
  final TableDefinition table = new TableDefinition();
  final TableDefinition joinTable = new TableDefinition();
  private TableDefinition[] additionalJoinTables;
  private int joinRoleCount;
  final int[] insertSourceByColumn = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] updatedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] updateResultTypeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] projectedColumns = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] projectedTypeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  private final byte[] joinAccessOuterRoles =
      new byte[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final int[] joinAccessOuterColumns =
      new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final int[] joinAccessInnerColumns =
      new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];

  int predicateColumn;
  int predicateCount;
  int accessPredicate;
  int pointTextColumn;
  long accessValue;
  long accessLowerInclusive;
  long accessUpperExclusive;
  SqlComparison accessComparison;
  int updatedColumnCount;
  int projectedColumnCount;
  int groupColumn;
  int groupAggregateColumn;
  int distinctColumn;
  int joinOuterColumn;
  int joinInnerColumn;
  int orderColumn;
  int sortKeyProjection;

  void reset() {
    command.reset();
    query.reset();
    executableQuery.reset();
    projectionPrograms.reset();
    aggregates.reset();
    whereBoolean.reset();
    resetOnBoolean();
    havingBoolean.reset();
    if (blockPlans != null) blockPlans.reset();
    table.reset();
    joinTable.reset();
    resetJoinRoles();
    predicateColumn = -1;
    predicateCount = 0;
    accessPredicate = -1;
    pointTextColumn = -1;
    accessValue = 0;
    accessLowerInclusive = 0;
    accessUpperExclusive = 0;
    accessComparison = null;
    updatedColumnCount = 0;
    projectedColumnCount = 0;
    groupColumn = -1;
    groupAggregateColumn = -1;
    distinctColumn = -1;
    joinOuterColumn = -1;
    joinInnerColumn = -1;
    resetJoinAccess();
    orderColumn = -1;
    sortKeyProjection = -1;
  }

  SqlBoundBlockPlans blockPlans() {
    if (blockPlans == null) blockPlans = new SqlBoundBlockPlans();
    return blockPlans;
  }

  SqlBoundBooleanPredicateProgram onBoolean() { return onBoolean(0); }

  SqlBoundBooleanPredicateProgram onBoolean(int stage) {
    if (stage < 0 || stage >= SqlJoinChain.MAXIMUM_JOIN_STAGES) return null;
    if (onBooleans == null) {
      onBooleans = new SqlBoundBooleanPredicateProgram[
          SqlJoinChain.MAXIMUM_JOIN_STAGES];
    }
    if (onBooleans[stage] == null) {
      onBooleans[stage] = new SqlBoundBooleanPredicateProgram();
    }
    return onBooleans[stage];
  }

  boolean hasOnBoolean() {
    return hasOnBoolean(0);
  }

  boolean hasOnBoolean(int stage) {
    return onBooleans != null && stage >= 0 && stage < onBooleans.length
        && onBooleans[stage] != null && onBooleans[stage].available();
  }

  void resetOnBoolean() {
    if (onBooleans == null) return;
    for (SqlBoundBooleanPredicateProgram program : onBooleans) {
      if (program != null) program.reset();
    }
  }

  void beginJoinRoles(int roles) {
    joinRoleCount = roles;
    if (roles <= 2 || additionalJoinTables != null) return;
    additionalJoinTables = new TableDefinition[
        SqlJoinChain.MAXIMUM_JOIN_ROLES - 2];
    for (int role = 2; role < SqlJoinChain.MAXIMUM_JOIN_ROLES; role++) {
      additionalJoinTables[role - 2] = new TableDefinition();
    }
  }

  TableDefinition joinRole(int role) {
    if (role < 0 || role >= joinRoleCount) return null;
    if (role == 0) return table;
    if (role == 1) return joinTable;
    return additionalJoinTables == null ? null : additionalJoinTables[role - 2];
  }

  private void resetJoinRoles() {
    if (additionalJoinTables != null) {
      for (TableDefinition definition : additionalJoinTables) definition.reset();
    }
    joinRoleCount = 0;
  }

  void resetJoinAccess() {
    for (int stage = 0; stage < joinAccessOuterRoles.length; stage++) {
      joinAccessOuterRoles[stage] = -1;
      joinAccessOuterColumns[stage] = -1;
      joinAccessInnerColumns[stage] = -1;
    }
    joinOuterColumn = -1;
    joinInnerColumn = -1;
  }

  void setJoinAccess(int stage, int outerRole, int outerColumn, int innerColumn) {
    joinAccessOuterRoles[stage] = (byte) outerRole;
    joinAccessOuterColumns[stage] = outerColumn;
    joinAccessInnerColumns[stage] = innerColumn;
    if (stage == 0) {
      joinOuterColumn = outerColumn;
      joinInnerColumn = innerColumn;
    }
  }

  int joinAccessOuterRole(int stage) { return joinAccessOuterRoles[stage]; }
  int joinAccessOuterColumn(int stage) { return joinAccessOuterColumns[stage]; }
  int joinAccessInnerColumn(int stage) { return joinAccessInnerColumns[stage]; }

  boolean hasBlockPlans() {
    return blockPlans != null && blockPlans.count() > 0;
  }
}
