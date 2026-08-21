package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;

/** Resolves one column against the nearest visible lexical query-block scope. */
final class SqlNestedColumnResolver {
  private int block = -1;
  private int role = -1;
  private int column = -1;

  StatusCode resolve(
      BoundSqlQuery query,
      int currentBlock,
      CharSequence qualifier,
      CharSequence name) {
    clear();
    if (query == null || qualifier == null || name == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int scope = currentBlock;
    while (scope >= 0) {
      BoundSqlQuery.Block candidate = query.block(scope);
      StatusCode status = qualifier.length() == 0
          ? unqualified(candidate, scope, name)
          : qualified(candidate, scope, qualifier, name);
      if (status != StatusCode.CONFLICT) return status;
      scope = query.blockParent(scope);
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode unqualified(
      BoundSqlQuery.Block candidate, int scope, CharSequence name) {
    int matches = 0;
    for (int candidateRole = 0;
        candidate != null && candidateRole < candidate.roleCount();
        candidateRole++) {
      TableDefinition table = candidate.table(candidateRole);
      int resolved = table == null ? -1 : table.findColumn(name);
      if (resolved < 0) continue;
      matches++;
      block = scope;
      role = candidateRole;
      column = resolved;
    }
    if (matches == 0) return StatusCode.CONFLICT;
    return matches == 1 ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode qualified(
      BoundSqlQuery.Block candidate,
      int scope,
      CharSequence qualifier,
      CharSequence name) {
    int matchingRoles = 0;
    int matchingRole = -1;
    for (int candidateRole = 0;
        candidate != null && candidateRole < candidate.roleCount();
        candidateRole++) {
      if (!SqlBindingNames.same(qualifier, candidate.roleTableName(candidateRole))
          && (candidate.roleAlias(candidateRole).length() == 0
              || !SqlBindingNames.same(qualifier, candidate.roleAlias(candidateRole)))) {
        continue;
      }
      matchingRoles++;
      matchingRole = candidateRole;
    }
    if (matchingRoles == 0) return StatusCode.CONFLICT;
    if (matchingRoles > 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDefinition table = candidate.table(matchingRole);
    int resolved = table == null ? -1 : table.findColumn(name);
    if (resolved < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    block = scope;
    role = matchingRole;
    column = resolved;
    return StatusCode.OK;
  }

  private void clear() {
    block = -1;
    role = -1;
    column = -1;
  }

  int block() { return block; }
  int role() { return role; }
  int column() { return column; }
}
