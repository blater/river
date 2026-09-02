package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Copies one join chain without making partial roles visible. */
final class SqlJoinChainCopy {
  private SqlJoinChainCopy() {}

  static StatusCode copy(SqlJoinChain target, SqlJoinChain source) {
    target.reset();
    if (source == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!SqlJoinCapacity.ensure(target, source.roleCount)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    target.roleCount = source.roleCount;
    target.stageCount = source.stageCount;
    for (int role = 0; role < target.roleCount; role++) {
      target.tableNames[role].copyFrom(source.tableNames[role]);
      target.aliases[role].copyFrom(source.aliases[role]);
      target.sourceKinds[role] = source.sourceKinds[role];
    }
    for (int stage = 0; stage < target.stageCount; stage++) {
      target.rightRoles[stage] = source.rightRoles[stage];
      target.joinKinds[stage] = source.joinKinds[stage];
      StatusCode status = copyPredicates(target, source, stage);
      if (!status.isOk()) {
        target.reset();
        return status;
      }
    }
    return StatusCode.OK;
  }

  private static StatusCode copyPredicates(
      SqlJoinChain target, SqlJoinChain source, int stage) {
    SqlBooleanPredicateProgram sourcePredicates = source.onPrograms[stage];
    if (sourcePredicates == null || !sourcePredicates.isAvailable()) return StatusCode.OK;
    try {
      if (!SqlJoinCapacity.ensureStage(target, stage + 2)) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      return target.writableOnPredicates(stage).copyFrom(sourcePredicates);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
