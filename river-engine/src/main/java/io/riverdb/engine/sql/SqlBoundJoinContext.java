package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableStatistics;
import io.riverdb.sql.SqlJoinChain;

/** Reusable catalog, predicate, access, and strategy state for one JOIN block. */
final class SqlBoundJoinContext extends SqlBoundAccess {
  private final TableDefinition[] tables =
      new TableDefinition[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final boolean[] ownedTables =
      new boolean[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final TableStatistics[] statistics =
      new TableStatistics[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final SqlBoundBooleanPredicateProgram[] onBooleans =
      new SqlBoundBooleanPredicateProgram[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final byte[] accessOuterRoles =
      new byte[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final int[] accessOuterColumns =
      new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final int[] accessInnerColumns =
      new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final byte[] strategies =
      new byte[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final byte[] strategyOuterRoles =
      new byte[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final int[] strategyOuterColumns =
      new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final int[] strategyInnerColumns =
      new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] estimatedRows =
      new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private int roleCount;
  private int queryBlock = -1;
  private boolean estimatesAvailable;

  void beginRoles(int roles, TableDefinition borrowedRoot) {
    queryBlock = -1;
    roleCount = roles;
    for (int role = 0; role < roles; role++) {
      if (role == 0 && borrowedRoot != null) {
        if (ownedTables[role] && tables[role] != null) tables[role].reset();
        tables[role] = borrowedRoot;
        ownedTables[role] = false;
      } else if (tables[role] == null || !ownedTables[role]) {
        tables[role] = new TableDefinition();
        ownedTables[role] = true;
      }
      if (statistics[role] == null) statistics[role] = new TableStatistics();
    }
  }

  void borrowRoles(int block, BoundSqlQuery.Block schemas) {
    reset();
    queryBlock = block;
    roleCount = schemas == null ? 0 : schemas.roleCount();
    for (int role = 0; role < roleCount; role++) {
      tables[role] = schemas.table(role);
      ownedTables[role] = false;
      if (statistics[role] == null) statistics[role] = new TableStatistics();
    }
  }

  void usePackedScopes(int block) { queryBlock = block; }

  int localRole(int scope) {
    if (queryBlock < 0) return scope;
    return SqlNestedRowProvider.block(scope) == queryBlock
        ? SqlNestedRowProvider.role(scope) : -1;
  }

  TableDefinition table(int role) {
    return role < 0 || role >= roleCount ? null : tables[role];
  }

  TableStatistics statistics(int role) {
    return role < 0 || role >= roleCount ? null : statistics[role];
  }

  SqlBoundBooleanPredicateProgram onBoolean(int stage) {
    if (stage < 0 || stage >= onBooleans.length) return null;
    if (onBooleans[stage] == null) {
      onBooleans[stage] = new SqlBoundBooleanPredicateProgram();
    }
    return onBooleans[stage];
  }

  boolean hasOnBoolean(int stage) {
    return stage >= 0 && stage < onBooleans.length
        && onBooleans[stage] != null && onBooleans[stage].available();
  }

  void reset() {
    for (int role = 0; role < roleCount; role++) {
      if (ownedTables[role] && tables[role] != null) tables[role].reset();
      if (statistics[role] != null) statistics[role].reset();
      tables[role] = null;
      ownedTables[role] = false;
    }
    roleCount = 0;
    queryBlock = -1;
    resetOnBooleans();
    resetJoinAccess();
    resetStrategies();
    resetEstimates();
  }

  void resetOnBooleans() {
    for (SqlBoundBooleanPredicateProgram program : onBooleans) {
      if (program != null) program.reset();
    }
  }

  void resetJoinAccess() {
    resetRootAccess();
    for (int stage = 0; stage < accessOuterRoles.length; stage++) {
      accessOuterRoles[stage] = -1;
      accessOuterColumns[stage] = -1;
      accessInnerColumns[stage] = -1;
    }
  }

  void setAccess(int stage, int outerRole, int outerColumn, int innerColumn) {
    accessOuterRoles[stage] = (byte) outerRole;
    accessOuterColumns[stage] = outerColumn;
    accessInnerColumns[stage] = innerColumn;
  }

  int accessOuterRole(int stage) { return accessOuterRoles[stage]; }
  int accessOuterColumn(int stage) { return accessOuterColumns[stage]; }
  int accessInnerColumn(int stage) { return accessInnerColumns[stage]; }

  void resetStrategies() {
    for (int stage = 0; stage < strategies.length; stage++) clearStrategy(stage);
  }

  void clearStrategy(int stage) {
    strategies[stage] = SqlJoinStrategy.NESTED_LOOP;
    strategyOuterRoles[stage] = -1;
    strategyOuterColumns[stage] = -1;
    strategyInnerColumns[stage] = -1;
  }

  void setStrategy(
      int stage,
      int strategy,
      int outerRole,
      int outerColumn,
      int innerColumn) {
    strategies[stage] = (byte) strategy;
    strategyOuterRoles[stage] = (byte) outerRole;
    strategyOuterColumns[stage] = outerColumn;
    strategyInnerColumns[stage] = innerColumn;
  }

  int strategy(int stage) { return Byte.toUnsignedInt(strategies[stage]); }
  int strategyOuterRole(int stage) { return strategyOuterRoles[stage]; }
  int strategyOuterColumn(int stage) { return strategyOuterColumns[stage]; }
  int strategyInnerColumn(int stage) { return strategyInnerColumns[stage]; }

  void resetEstimates() {
    for (int stage = 0; stage < estimatedRows.length; stage++) {
      estimatedRows[stage] = 0;
    }
    estimatesAvailable = false;
  }

  void setEstimatedRows(int stage, long rows) {
    estimatedRows[stage] = rows;
    estimatesAvailable = true;
  }

  boolean estimatesAvailable() { return estimatesAvailable; }
  long estimatedRows(int stage) { return estimatedRows[stage]; }

  int physicalStrategyStage() {
    for (int stage = 0; stage < strategies.length; stage++) {
      if (Byte.toUnsignedInt(strategies[stage]) != SqlJoinStrategy.NESTED_LOOP) {
        return stage;
      }
    }
    return -1;
  }

  boolean hasPhysicalStrategy() { return physicalStrategyStage() >= 0; }
}
