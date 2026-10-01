package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.tx.api.IsolationLevel;

/** Completes descriptor DDL after command-owned pins are released, preserving primary failure. */
abstract class SqlDescriptorDdl {
  protected boolean legacyTable;

  final StatusCode execute(
      RelationalSession session, SqlTransactionState transactions,
      SqlAtomicStatementLifecycle atomic, SqlCommand command, SqlExecutionResult result) {
    legacyTable = false;
    StatusCode status = atomic.begin(IsolationLevel.SERIALIZABLE);
    boolean began = status.isOk();
    boolean implicit = began && atomic.implicit();
    if (began) status = executeBody(session, command);
    status = release(status);
    if (began) status = atomic.finish(status);
    if (!status.isOk()) return status;
    long commit = implicit ? transactions.commitSequence() : 0;
    result.setUpdate(0, commit);
    result.setTransaction(transactions.isExplicit(), commit);
    return StatusCode.OK;
  }

  final boolean legacyTable() { return legacyTable; }

  abstract StatusCode executeBody(RelationalSession session, SqlCommand command);
  abstract StatusCode release(StatusCode status);
}
