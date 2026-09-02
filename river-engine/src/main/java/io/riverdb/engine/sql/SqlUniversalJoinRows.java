package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;

/** Retained role sources and rows for descriptor/mixed universal joins. */
class SqlUniversalJoinRows {
  private final RelationalSession session;
  private final StatusDetail detail = new StatusDetail(128);
  private SqlUniversalJoinRole[] roles = new SqlUniversalJoinRole[0];
  private boolean[] nulls = new boolean[0];
  private int roleCount;
  private int candidateRole = -1;
  private SqlBlockRow candidateRow;
  private long candidateKey;
  private long candidatePublicKey;
  private boolean matched;

  SqlUniversalJoinRows(RelationalSession relationalSession) {
    session = relationalSession;
  }

  StatusCode resolve(SqlCommand command, SqlBoundJoinContext context) {
    StatusCode status = reset(context);
    SqlJoinChain joins = command == null ? null : command.joinChain();
    if (!status.isOk() || joins == null) return status.isOk()
        ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    roleCount = joins.roleCount();
    status = reserve(roleCount);
    if (status.isOk()) status = context.beginRoles(roleCount, null);
    for (int role = 0; status.isOk() && role < roleCount; role++) {
      detail.reset();
      status = roles[role].resolve(joins.tableName(role), context.table(role), detail);
      if (status.isOk() && roles[role].descriptor()) matched = true;
      if (status.isOk()) {
        status = session.resolveStatistics(
            context.table(role), context.statistics(role));
        if (status == StatusCode.CONFLICT) status = StatusCode.OK;
      }
    }
    if (!status.isOk() || !matched) {
      StatusCode cleanup = reset(context);
      return status.isOk() ? cleanup : status;
    }
    return StatusCode.OK;
  }

  StatusCode resolveBound(SqlCommand command, SqlBoundJoinContext context) {
    StatusCode status = reset(null);
    SqlJoinChain joins = command == null ? null : command.joinChain();
    if (!status.isOk() || joins == null || context == null) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    roleCount = joins.roleCount();
    status = reserve(roleCount);
    for (int role = 0; status.isOk() && role < roleCount; role++) {
      detail.reset();
      status = roles[role].resolve(
          joins.tableName(role), context.table(role), detail);
      if (status.isOk() && roles[role].descriptor()) matched = true;
      if (status.isOk()) {
        status = session.resolveStatistics(
            context.table(role), context.statistics(role));
        if (status == StatusCode.CONFLICT) status = StatusCode.OK;
      }
    }
    if (!status.isOk() || !matched) {
      StatusCode cleanup = reset(null);
      return !status.isOk() ? status
          : cleanup.isOk() ? StatusCode.CONFLICT : cleanup;
    }
    return StatusCode.OK;
  }

  private StatusCode reserve(int required) {
    int capacity = BoundedArrayGrowth.capacity(
        roles.length, required, SqlJoinChain.MAXIMUM_JOIN_ROLES, 2);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == roles.length) return StatusCode.OK;
    try {
      SqlUniversalJoinRole[] nextRoles = new SqlUniversalJoinRole[capacity];
      boolean[] nextNulls = new boolean[capacity];
      System.arraycopy(roles, 0, nextRoles, 0, roles.length);
      for (int role = roles.length; role < capacity; role++) {
        nextRoles[role] = new SqlUniversalJoinRole(session);
      }
      roles = nextRoles;
      nulls = nextNulls;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void configureAccess(
      SqlCommand command, SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where) {
    for (int role = 0; role < roleCount; role++) {
      roles[role].configure(command, role, context, where);
    }
  }

  StatusCode open(int role) {
    clearCandidate(role);
    nulls[role] = false;
    return roles[role].open(this);
  }

  StatusCode openFullScan(int role) {
    clearCandidate(role);
    nulls[role] = false;
    return roles[role].openFullScan();
  }

  StatusCode next(int role) {
    nulls[role] = false;
    return roles[role].next();
  }

  StatusCode closeScan(int role) {
    clearCandidate(role);
    return roles[role].closeScan();
  }

  StatusCode closeScans() {
    StatusCode first = StatusCode.OK;
    for (int role = roleCount - 1; role >= 0; role--) {
      StatusCode status = roles[role].closeScan();
      if (first.isOk() && !status.isOk()) first = status;
    }
    return first;
  }

  /** Borrows a hash-owned decoded row until the next candidate or stage reset. */
  void borrowCandidate(
      int role, SqlBlockRow row, long internalKey, long publicKey) {
    candidateRole = role;
    candidateRow = row;
    candidateKey = internalKey;
    candidatePublicKey = publicKey;
    nulls[role] = false;
  }

  void clearCandidate(int role) {
    if (candidateRole != role) return;
    candidateRole = -1;
    candidateRow = null;
    candidateKey = 0;
    candidatePublicKey = 0;
  }

  void setNull(int role) {
    clearCandidate(role);
    nulls[role] = true;
  }
  boolean nullRole(int role) { return nulls[role]; }
  long key(int role) {
    return nulls[role] ? 0 : role == candidateRole ? candidateKey : roles[role].key();
  }
  long publicKey(int role) {
    return nulls[role] ? 0
        : role == candidateRole ? candidatePublicKey : roles[role].publicKey();
  }
  SqlBlockRow row(int role) {
    return nulls[role] ? null : role == candidateRole ? candidateRow : roles[role].row();
  }
  io.riverdb.engine.relational.TableDefinition table(int role) {
    return roles[role].table();
  }
  boolean matched() { return matched; }
  boolean indexed(int role) { return roles[role].indexed(); }
  boolean exact(int role) { return roles[role].exact(); }
  boolean unique(int role) { return roles[role].unique(); }
  int accessColumn(int role) { return roles[role].accessColumn(); }

  StatusCode reset(SqlBoundJoinContext context) {
    StatusCode first = StatusCode.OK;
    for (int role = roleCount - 1; role >= 0; role--) {
      StatusCode status = roles[role].reset();
      if (first.isOk() && !status.isOk()) first = status;
      nulls[role] = false;
    }
    if (first.isOk()) {
      roleCount = 0;
      candidateRole = -1;
      candidateRow = null;
      candidateKey = 0;
      candidatePublicKey = 0;
      matched = false;
      if (context != null) context.reset();
    }
    return first;
  }
}
