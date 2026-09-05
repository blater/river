package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.table.IndexedSavepoint;
import io.riverdb.sql.SqlIdentifier;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.util.Arrays;

/** Owns reusable SQL transaction, statement, and savepoint state for one session. */
final class SqlTransactionState {
  private static final long CONSERVATIVE_64_BIT_HEADER_BYTES = 32;
  private static final long CONSERVATIVE_64_BIT_REFERENCE_BYTES = 8;
  private static final long CONSERVATIVE_INDEXED_SAVEPOINT_BYTES = 64;
  /** One conservative array header plus four non-compressed 64-bit references. */
  private static final long LOWER_SAVEPOINT_STACK_BASE_RETAINED_BYTES =
      CONSERVATIVE_64_BIT_HEADER_BYTES + 4 * CONSERVATIVE_64_BIT_REFERENCE_BYTES;
  private static final long LOWER_SAVEPOINT_STACK_BYTES_PER_USER_SLOT =
      2 * CONSERVATIVE_64_BIT_REFERENCE_BYTES;
  /**
   * Conservative non-compressed 64-bit charge for the two SQL reference lanes,
   * int lane, savepoint, maximum identifier buffer, amortized SQL array
   * headers, and two lower-stack references for geometric statement slack.
   */
  private static final long SQL_USER_SAVEPOINT_RETAINED_BYTES =
      2 * CONSERVATIVE_64_BIT_REFERENCE_BYTES
          + Long.BYTES
          + CONSERVATIVE_INDEXED_SAVEPOINT_BYTES
          + CONSERVATIVE_64_BIT_HEADER_BYTES
          + 2L * SqlIdentifier.MAXIMUM_LENGTH
          + 3 * CONSERVATIVE_64_BIT_HEADER_BYTES / 4;
  private static final long USER_SAVEPOINT_RETAINED_BYTES =
      SQL_USER_SAVEPOINT_RETAINED_BYTES + LOWER_SAVEPOINT_STACK_BYTES_PER_USER_SLOT;

  private final RelationalSession session;
  private final SqlSessionShapeBudget budget;
  private final TransactionOutcome outcome = new TransactionOutcome();
  private final IndexedSavepoint statementSavepoint = new IndexedSavepoint();
  private IndexedSavepoint[] userSavepoints = new IndexedSavepoint[0];
  private char[][] userSavepointNames = new char[0][];
  private int[] userSavepointNameLengths = new int[0];
  private boolean explicit;
  private boolean statementActive;
  private boolean lowerSavepointStackReserved;
  private int userSavepointCount;

  SqlTransactionState(RelationalSession relational, SqlSessionShapeBudget shapeBudget) {
    session = relational;
    budget = shapeBudget;
  }

  boolean isExplicit() {
    return explicit;
  }

  boolean transactionHandleActive() {
    return session.transactionHandleActive();
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
    if (!session.transactionActive()) {
      explicit = false;
      statementActive = false;
      clearUserSavepointsFrom(0);
    }
    return status;
  }

  StatusCode abortExplicit() {
    if (!explicit) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = session.abort(outcome);
    if (!session.transactionActive()) {
      explicit = false;
      statementActive = false;
      clearUserSavepointsFrom(0);
    }
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
    StatusCode status = reserveLowerSavepointStack();
    return status.isOk() ? session.createSavepoint(statementSavepoint) : status;
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
    if (!explicit) return StatusCode.CONFLICT;
    if (findUserSavepoint(name) >= 0) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = reserveUserSavepoint();
    if (!status.isOk()) return status;
    status = reserveLowerSavepointStack();
    if (!status.isOk()) return status;
    status = session.createSavepoint(userSavepoints[userSavepointCount]);
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

  private StatusCode reserveUserSavepoint() {
    if (userSavepointCount < userSavepoints.length) return StatusCode.OK;
    if (userSavepointCount == Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    int capacity = BoundedArrayGrowth.capacity(
        userSavepoints.length, userSavepointCount + 1, Integer.MAX_VALUE, 4);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long charged = retainedUserSavepointGrowthBytes(userSavepoints.length, capacity);
    if (charged < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode admission = budget.reserve(charged);
    if (!admission.isOk()) return admission;
    try {
      IndexedSavepoint[] nextSavepoints = Arrays.copyOf(userSavepoints, capacity);
      char[][] nextNames = Arrays.copyOf(userSavepointNames, capacity);
      int[] nextNameLengths = Arrays.copyOf(userSavepointNameLengths, capacity);
      for (int index = userSavepoints.length; index < capacity; index++) {
        nextSavepoints[index] = new IndexedSavepoint();
        nextNames[index] = new char[SqlIdentifier.MAXIMUM_LENGTH];
      }
      userSavepoints = nextSavepoints;
      userSavepointNames = nextNames;
      userSavepointNameLengths = nextNameLengths;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode reserveLowerSavepointStack() {
    if (lowerSavepointStackReserved) return StatusCode.OK;
    StatusCode status = budget.reserve(LOWER_SAVEPOINT_STACK_BASE_RETAINED_BYTES);
    if (status.isOk()) lowerSavepointStackReserved = true;
    return status;
  }

  static long retainedUserSavepointGrowthBytes(long current, long target) {
    long slots = target - current;
    return current < 0 || target <= current
        || slots > Long.MAX_VALUE / USER_SAVEPOINT_RETAINED_BYTES
        ? -1 : slots * USER_SAVEPOINT_RETAINED_BYTES;
  }

  static long retainedLowerSavepointStackCoverageBytes(long userCapacity) {
    return userCapacity < 0
        || userCapacity > (Long.MAX_VALUE - LOWER_SAVEPOINT_STACK_BASE_RETAINED_BYTES)
            / LOWER_SAVEPOINT_STACK_BYTES_PER_USER_SLOT
        ? -1 : LOWER_SAVEPOINT_STACK_BASE_RETAINED_BYTES
            + userCapacity * LOWER_SAVEPOINT_STACK_BYTES_PER_USER_SLOT;
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
