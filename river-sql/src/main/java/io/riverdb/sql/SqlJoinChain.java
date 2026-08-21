package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Command-owned bounded left-associative JOIN syntax. */
public final class SqlJoinChain {
  public static final int MAXIMUM_JOIN_ROLES = 8;
  public static final int MAXIMUM_JOIN_STAGES = MAXIMUM_JOIN_ROLES - 1;
  public static final int SOURCE_RELATION = 1;
  public static final int INNER = 1;
  public static final int LEFT = 2;

  private final SqlIdentifier[] tableNames = new SqlIdentifier[MAXIMUM_JOIN_ROLES];
  private final SqlIdentifier[] aliases = new SqlIdentifier[MAXIMUM_JOIN_ROLES];
  private final byte[] sourceKinds = new byte[MAXIMUM_JOIN_ROLES];
  private final byte[] rightRoles = new byte[MAXIMUM_JOIN_STAGES];
  private final byte[] joinKinds = new byte[MAXIMUM_JOIN_STAGES];
  private final SqlBooleanPredicateProgram[] onPrograms =
      new SqlBooleanPredicateProgram[MAXIMUM_JOIN_STAGES];
  private int roleCount;
  private int stageCount;

  public SqlJoinChain() {
    for (int role = 0; role < tableNames.length; role++) {
      tableNames[role] = new SqlIdentifier();
      aliases[role] = new SqlIdentifier();
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
    if (roleCount < 1 || roleCount >= MAXIMUM_JOIN_ROLES) return -1;
    int stage = stageCount++;
    int role = roleCount++;
    rightRoles[stage] = (byte) role;
    joinKinds[stage] = (byte) (left ? LEFT : INNER);
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
    if (!validStage(stage)) return null;
    if (onPrograms[stage] == null) {
      onPrograms[stage] = new SqlBooleanPredicateProgram();
    }
    return onPrograms[stage];
  }

  public void copyFrom(SqlJoinChain source) {
    reset();
    roleCount = source.roleCount;
    stageCount = source.stageCount;
    for (int role = 0; role < roleCount; role++) {
      tableNames[role].copyFrom(source.tableNames[role]);
      aliases[role].copyFrom(source.aliases[role]);
      sourceKinds[role] = source.sourceKinds[role];
    }
    for (int stage = 0; stage < stageCount; stage++) {
      rightRoles[stage] = source.rightRoles[stage];
      joinKinds[stage] = source.joinKinds[stage];
      if (source.onPrograms[stage] != null && source.onPrograms[stage].isAvailable()) {
        writableOnPredicates(stage).copyFrom(source.onPrograms[stage]);
      }
    }
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
    return validRole(role) ? Byte.toUnsignedInt(sourceKinds[role]) : 0;
  }
  public int rightRole(int stage) {
    return validStage(stage) ? Byte.toUnsignedInt(rightRoles[stage]) : -1;
  }
  public int joinKind(int stage) {
    return validStage(stage) ? Byte.toUnsignedInt(joinKinds[stage]) : 0;
  }
  public boolean isLeft(int stage) { return joinKind(stage) == LEFT; }
  public SqlBooleanPredicateProgram onPredicates(int stage) {
    return validStage(stage) ? onPrograms[stage] : null;
  }

  private boolean validRole(int role) { return role >= 0 && role < roleCount; }
  private boolean validStage(int stage) { return stage >= 0 && stage < stageCount; }
}
