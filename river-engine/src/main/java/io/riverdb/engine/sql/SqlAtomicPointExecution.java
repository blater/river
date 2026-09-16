package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.tx.api.IsolationLevel;

/** One atomic point command with transaction and temporal publication ordering. */
final class SqlAtomicPointExecution {
  private SqlAtomicPointExecution() { }

  static StatusCode execute(
      SqlAtomicStatementLifecycle atomic,
      SqlTemporalContext temporal,
      SqlPointCommandExecutor commands,
      SqlTransactionState transactions,
      RelationalSession session,
      SqlStreamingStatementLifecycle streaming,
      SqlExecutionResult result) {
    StatusCode status = atomic.begin(IsolationLevel.READ_COMMITTED);
    boolean began = status.isOk();
    boolean implicit = began && atomic.implicit();
    status = executeCommand(status, temporal, commands, result);
    boolean select = commands.isPointQuery();
    if (began) status = atomic.finish(status);
    finishTemporal(atomic, streaming, temporal);
    if (!status.isOk()) return status;
    return publishResult(
        commands, transactions, session, result, implicit, select);
  }

  private static StatusCode executeCommand(
      StatusCode status, SqlTemporalContext temporal,
      SqlPointCommandExecutor commands, SqlExecutionResult result) {
    if (!status.isOk()) return status;
    status = temporal.beginStatement();
    if (!status.isOk()) return status;
    return commands.execute(result);
  }

  private static StatusCode publishResult(
      SqlPointCommandExecutor commands,
      SqlTransactionState transactions,
      RelationalSession session,
      SqlExecutionResult result,
      boolean implicit,
      boolean select) {
    long commitSequence = implicit
        ? transactions.commitSequence() : session.visibleCommitSequence();
    if (select) result.setCommitSequence(commitSequence);
    else result.setUpdate(commands.affectedRows(), implicit ? commitSequence : 0);
    result.setTransaction(
        transactions.isExplicit(), select || implicit ? commitSequence : 0);
    return StatusCode.OK;
  }

  private static void finishTemporal(
      SqlAtomicStatementLifecycle atomic,
      SqlStreamingStatementLifecycle streaming,
      SqlTemporalContext temporal) {
    if (!atomic.isActive() && !streaming.isActive() && temporal.statementActive()) {
      temporal.finishStatement();
    }
  }
}
