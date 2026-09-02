package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Command-owned bounded left-associative JOIN syntax. */
public final class SqlJoinChain {
  final SqlJoinAllocator allocator;
  public static final int MAXIMUM_JOIN_ROLES = SqlShapeLimits.MAX_JOIN_ROLES;
  public static final int MAXIMUM_JOIN_STAGES = MAXIMUM_JOIN_ROLES - 1;
  public static final int SOURCE_RELATION = 1;
  public static final int INNER = 1;
  public static final int LEFT = 2;

  SqlIdentifier[] tableNames = new SqlIdentifier[8];
  SqlIdentifier[] aliases = new SqlIdentifier[8];
  int[] sourceKinds = new int[8];
  int[] rightRoles = new int[8];
  int[] joinKinds = new int[8];
  SqlBooleanPredicateProgram[] onPrograms = new SqlBooleanPredicateProgram[8];
  int roleCount;
  int stageCount;

  public SqlJoinChain() { this(SqlJoinAllocator.STANDARD); }

  SqlJoinChain(SqlJoinAllocator joinAllocator) {
    allocator = joinAllocator;
    for (int role = 0; role < tableNames.length; role++) {
      tableNames[role] = allocator.identifier();
      aliases[role] = allocator.identifier();
    }
  }

  void begin(CharSequence tableName, CharSequence alias) {
    reset();
    tableNames[0].copyFrom(tableName);
    aliases[0].copyFrom(alias);
    sourceKinds[0] = SOURCE_RELATION;
    roleCount = 1;
  }

  int appendStage(boolean left) {
    if (roleCount < 1 || roleCount >= MAXIMUM_JOIN_ROLES
        || !SqlJoinCapacity.ensureStage(this, roleCount + 1)) return -1;
    int stage = stageCount++;
    int role = roleCount++;
    rightRoles[stage] = role;
    joinKinds[stage] = left ? LEFT : INNER;
    sourceKinds[role] = SOURCE_RELATION;
    return stage;
  }

  SqlIdentifier writableTableName(int role) {
    return validRole(role) ? tableNames[role] : null;
  }

  SqlIdentifier writableAlias(int role) {
    return validRole(role) ? aliases[role] : null;
  }

  SqlBooleanPredicateProgram writableOnPredicates(int stage) {
    return validStage(stage) ? onPrograms[stage] : null;
  }

  public StatusCode copyFrom(SqlJoinChain source) {
    return SqlJoinChainCopy.copy(this, source);
  }

  public void reset() {
    for (int role = 0; role < roleCount; role++) {
      tableNames[role].reset();
      aliases[role].reset();
      sourceKinds[role] = 0;
    }
    for (int stage = 0; stage < stageCount; stage++) {
      rightRoles[stage] = 0;
      joinKinds[stage] = 0;
      if (onPrograms[stage] != null) onPrograms[stage].reset();
    }
    roleCount = 0;
    stageCount = 0;
  }

  void clearPredicates() {
    for (int stage = 0; stage < stageCount; stage++) {
      if (onPrograms[stage] != null) onPrograms[stage].reset();
    }
  }

  StatusCode validateStage(int stage) {
    int visible = rightRole(stage) + 1;
    return validNamespaces(visible)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private boolean validNamespaces(int visible) {
    for (int right = 0; right < visible; right++) {
      for (int left = 0; left < right; left++) {
        boolean sameTable = same(tableNames[left], tableNames[right]);
        if (sameTable && (aliases[left].length() == 0 || aliases[right].length() == 0
            || same(aliases[left], aliases[right]))) return false;
        if (aliases[left].length() > 0 && aliases[right].length() > 0
            && same(aliases[left], aliases[right])) return false;
        if (aliases[left].length() > 0 && same(aliases[left], tableNames[right])
            || aliases[right].length() > 0 && same(aliases[right], tableNames[left])) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean same(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) return false;
    }
    return true;
  }

  public int roleCount() { return roleCount; }
  public int stageCount() { return stageCount; }
  public SqlIdentifier tableName(int role) {
    return validRole(role) ? tableNames[role] : null;
  }
  public SqlIdentifier alias(int role) { return validRole(role) ? aliases[role] : null; }
  public int sourceKind(int role) {
    return validRole(role) ? sourceKinds[role] : 0;
  }
  public int rightRole(int stage) {
    return validStage(stage) ? rightRoles[stage] : -1;
  }
  public int joinKind(int stage) {
    return validStage(stage) ? joinKinds[stage] : 0;
  }
  public boolean isLeft(int stage) { return joinKind(stage) == LEFT; }
  public SqlBooleanPredicateProgram onPredicates(int stage) {
    return validStage(stage) ? onPrograms[stage] : null;
  }

  private boolean validRole(int role) { return role >= 0 && role < roleCount; }
  private boolean validStage(int stage) { return stage >= 0 && stage < stageCount; }
}
