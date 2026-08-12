package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.sql.SqlIdentifier;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;

/** Owns reusable SQL transaction, statement, and savepoint state for one session. */
final class SqlTransactionState {
  private static final int MAXIMUM_USER_SAVEPOINTS = 3;

  private final RelationalSession session;
  private final TransactionOutcome outcome = new TransactionOutcome();
  private final IndexedSavepoint statementSavepoint = new IndexedSavepoint();
  private final IndexedSavepoint[] userSavepoints =
      new IndexedSavepoint[MAXIMUM_USER_SAVEPOINTS];
  private final char[][] userSavepointNames =
      new char[MAXIMUM_USER_SAVEPOINTS][SqlIdentifier.MAXIMUM_LENGTH];
  private final int[] userSavepointNameLengths =
      new int[MAXIMUM_USER_SAVEPOINTS];
  private boolean explicit;
  private boolean statementActive;
  private int userSavepointCount;

  SqlTransactionState(RelationalSession relational) {
    session = relational;
    for (int index = 0; index < userSavepoints.length; index++) {
      userSavepoints[index] = new IndexedSavepoint();
    }
  }

  boolean isExplicit() {
    return explicit;
  }

  StatusCode beginExplicit(IsolationLevel isolation) {
    if (explicit) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = session.begin(isolation);
    if (status.isOk()) {
      explicit = true;
    }
    return status;
  }

  StatusCode commitExplicit() {
    if (!explicit) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = session.commit(outcome);
    explicit = false;
    clearUserSavepointsFrom(0);
    return status;
  }

  StatusCode abortExplicit() {
    if (!explicit) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = session.abort(outcome);
    explicit = false;
    clearUserSavepointsFrom(0);
    return status;
  }

  StatusCode beginImplicit(IsolationLevel isolation) {
    return session.begin(isolation);
  }

  StatusCode commitImplicit() {
    return session.commit(outcome);
  }

  StatusCode abortImplicit() {
    return session.abort(outcome);
  }

  long commitSequence() {
    return outcome.commitSequence();
  }

  StatusCode beginStatement() {
    StatusCode status = session.beginStatement();
    if (status.isOk()) {
      statementActive = true;
    }
    return status;
  }

  StatusCode completeStatement() {
    if (!statementActive) {
      return StatusCode.OK;
    }
    StatusCode status = session.completeStatement();
    if (status.isOk()) {
      statementActive = false;
    }
    return status;
  }

  StatusCode createStatementSavepoint() {
    return session.createSavepoint(statementSavepoint);
  }

  boolean statementSavepointActive() {
    return statementSavepoint.isActive();
  }

  StatusCode rollbackStatementSavepoint() {
    return session.rollbackToSavepoint(statementSavepoint);
  }

  StatusCode releaseStatementSavepoint() {
    return session.releaseSavepoint(statementSavepoint);
  }

  StatusCode createUserSavepoint(CharSequence name) {
    if (!explicit || userSavepointCount >= userSavepoints.length) {
      return explicit ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.CONFLICT;
    }
    if (findUserSavepoint(name) >= 0) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = session.createSavepoint(userSavepoints[userSavepointCount]);
    if (status.isOk()) {
      rememberUserSavepoint(name, userSavepointCount++);
    }
    return status;
  }

  StatusCode rollbackToUserSavepoint(CharSequence name) {
    int savepoint = findUserSavepoint(name);
    if (!explicit || savepoint < 0) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = session.rollbackToSavepoint(userSavepoints[savepoint]);
    if (status.isOk()) {
      clearUserSavepointsFrom(savepoint + 1);
    }
    return status;
  }

  StatusCode releaseUserSavepoint(CharSequence name) {
    int savepoint = findUserSavepoint(name);
    if (!explicit || savepoint < 0) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = session.releaseSavepoint(userSavepoints[savepoint]);
    if (status.isOk()) {
      clearUserSavepointsFrom(savepoint);
    }
    return status;
  }

  private void rememberUserSavepoint(CharSequence name, int savepoint) {
    userSavepointNameLengths[savepoint] = name.length();
    for (int index = 0; index < name.length(); index++) {
      userSavepointNames[savepoint][index] = name.charAt(index);
    }
  }

  private int findUserSavepoint(CharSequence name) {
    for (int savepoint = userSavepointCount - 1; savepoint >= 0; savepoint--) {
      int length = userSavepointNameLengths[savepoint];
      if (length != name.length()) {
        continue;
      }
      boolean equal = true;
      for (int index = 0; index < length; index++) {
        if (name.charAt(index) != userSavepointNames[savepoint][index]) {
          equal = false;
          break;
        }
      }
      if (equal) {
        return savepoint;
      }
    }
    return -1;
  }

  private void clearUserSavepointsFrom(int first) {
    for (int savepoint = userSavepointCount - 1; savepoint >= first; savepoint--) {
      for (int index = 0; index < userSavepointNameLengths[savepoint]; index++) {
        userSavepointNames[savepoint][index] = 0;
      }
      userSavepointNameLengths[savepoint] = 0;
    }
    userSavepointCount = first;
  }
}
