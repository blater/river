package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.SequenceValueResult;
import io.riverdb.sql.SqlCommand;

/** Publishes one NEXT VALUE result without exposing partial success. */
final class SqlSequenceCommandExecution {
  private SqlSequenceCommandExecution() { }

  static StatusCode execute(
      RelationalDatabase database,
      RelationalSession session,
      SqlTransactionState transactions,
      SqlCommand command,
      SequenceValueResult sequenceValue,
      SqlExecutionResult result) {
    StatusCode status = database.nextSequenceValue(command.sequenceName(), sequenceValue);
    if (status.isOk()) {
      status = result.setScalar(sequenceValue.value(), sequenceValue.commitSequence());
    }
    if (status.isOk() && transactions.isExplicit()) {
      result.setTransaction(true, session.visibleCommitSequence());
    }
    return status;
  }
}
