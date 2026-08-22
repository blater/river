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
    BoundSqlQuery.Block current = query == null ? null : query.block(currentBlock);
    return resolve(
        query,
        currentBlock,
        current == null ? 0 : current.roleCount(),
        qualifier,
        name);
  }

  StatusCode resolve(
      BoundSqlQuery query,
      int currentBlock,
      int visibleLocalRoles,
      CharSequence qualifier,
      CharSequence name) {
    clear();
    if (query == null || qualifier == null || name == null
        || visibleLocalRoles < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return resolveVisible(
        query, currentBlock, visibleLocalRoles, qualifier, name);
  }

  private StatusCode resolveVisible(
      BoundSqlQuery query,
      int currentBlock,
      int visibleLocalRoles,
      CharSequence qualifier,
      CharSequence name) {
    int scope = currentBlock;
    while (scope >= 0) {
      BoundSqlQuery.Block candidate = query.block(scope);
      int visibleRoles = visibleRoles(
          candidate, scope == currentBlock, visibleLocalRoles);
      StatusCode status = resolveScope(
          candidate, scope, visibleRoles, qualifier, name);
      if (status != StatusCode.CONFLICT) return status;
      scope = query.blockParent(scope);
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode resolveScope(
      BoundSqlQuery.Block candidate,
      int scope,
      int visibleRoles,
      CharSequence qualifier,
      CharSequence name) {
    return qualifier.length() == 0
        ? unqualified(candidate, scope, visibleRoles, name)
        : qualified(candidate, scope, visibleRoles, qualifier, name);
  }

  private static int visibleRoles(
      BoundSqlQuery.Block candidate,
      boolean current,
      int visibleLocalRoles) {
    if (candidate == null) return 0;
    return current
        ? Math.min(visibleLocalRoles, candidate.roleCount())
        : candidate.roleCount();
  }

  private StatusCode unqualified(
      BoundSqlQuery.Block candidate,
      int scope,
      int visibleRoles,
      CharSequence name) {
    int matches = 0;
    for (int candidateRole = 0;
        candidate != null && candidateRole < visibleRoles;
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
      int visibleRoles,
      CharSequence qualifier,
      CharSequence name) {
    int matchingRoles = 0;
    int matchingRole = -1;
    for (int candidateRole = 0;
        candidate != null && candidateRole < visibleRoles;
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
