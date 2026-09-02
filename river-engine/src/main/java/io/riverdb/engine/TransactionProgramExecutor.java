package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.engine.sql.SqlExecutionResult;
import io.riverdb.engine.sql.SqlSession;

/** Owns one reusable, allocation-stable transaction-program execution workspace. */
final class TransactionProgramExecutor {
  private final SqlSession session;
  private final TransactionProgramValues values;
  private final SqlExecutionResult execution = new SqlExecutionResult();
  private final TransactionProgramSteps steps;

  TransactionProgramExecutor(SqlSession sqlSession) {
    session = sqlSession;
    values = new TransactionProgramValues(session);
    steps = new TransactionProgramSteps(session, values, execution);
  }

  StatusCode execute(
      RetainedTransactionProgram retained,
      IsolationLevel isolationLevel,
      TransactionProgramArguments arguments,
      TransactionProgramResult result) {
    if (retained == null || isolationLevel == null || arguments == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TransactionProgram program = retained.program();
    result.reset();
    values.reset();
    StatusCode status = values.validateArguments(program, arguments);
    if (!status.isOk()) return rejected(status, result);
    if (!session.matchesCatalogGeneration(retained.catalogGeneration())) {
      return rejected(StatusCode.PROGRAM_STALE, result);
    }
    status = session.beginProgram(isolationLevel, execution);
    if (!status.isOk()) return rejected(status, result);
    int step = 0;
    while (status.isOk() && step < program.stepCount()) {
      int target = values.guardTarget(program, arguments, step);
      if (target == Integer.MIN_VALUE) status = values.status();
      else if (target >= 0) step = target;
      else {
        int next = steps.execute(program, arguments, step, retained.plan(step), result);
        if (next == Integer.MIN_VALUE) status = steps.status();
        else step = next;
      }
    }
    if (!status.isOk()) return fail(step, status, result);
    status = result.admitCommit();
    if (!status.isOk()) return fail(step, status, result);
    status = session.commitProgram(execution);
    if (status.isOk()) result.complete(execution.commitSequence());
    return status.isOk() ? StatusCode.OK : fail(step, status, result);
  }

  StatusCode close() { return values.close(); }

  private StatusCode fail(int step, StatusCode primary, TransactionProgramResult result) {
    StatusCode rollback = steps.closeOpenScan();
    if (rollback.isOk() && session.programTransactionActive()) {
      rollback = session.abortProgram(execution);
    }
    boolean fenced = session.programTransactionActive();
    result.reset();
    result.fail(step >= 0 ? step : -1, primary, rollback, fenced);
    return rollback.isOk() ? primary : rollback;
  }

  private static StatusCode rejected(StatusCode status, TransactionProgramResult result) {
    result.fail(-1, status, StatusCode.OK, false);
    return status;
  }
}
