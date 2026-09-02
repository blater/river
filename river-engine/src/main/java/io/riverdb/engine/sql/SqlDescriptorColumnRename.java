package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalDescriptorRenameChange;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;
import io.riverdb.tx.api.IsolationLevel;

/** Publishes an ordinal-preserving descriptor successor for ALTER TABLE RENAME COLUMN. */
final class SqlDescriptorColumnRename {
  private final RelationalDescriptorRenameChange change =
      new RelationalDescriptorRenameChange();
  private final TableDescriptor.Result proposal = new TableDescriptor.Result();
  private final SchemaPin current = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);
  private boolean legacyTable;

  StatusCode execute(
      RelationalSession session,
      SqlTransactionState transactions,
      SqlAtomicStatementLifecycle atomic,
      SqlCommand command,
      SqlExecutionResult result) {
    legacyTable = false;
    StatusCode status = atomic.begin(IsolationLevel.SERIALIZABLE);
    boolean began = status.isOk();
    boolean implicit = began && atomic.implicit();
    if (status.isOk()) status = session.resolveDescriptor(
        command.tableName(), current, detail);
    if (status == StatusCode.CONFLICT) legacyTable = true;
    if (status.isOk()) status = session.checkViewReferences(current.tableId());
    if (status.isOk()) status = change.column(
        current.descriptor(), command.firstColumnName(), command.secondColumnName(),
        proposal, detail);
    if (status.isOk()) status = session.prepareDescriptorSuccessor(
        command.tableName(), current, proposal.value(), detail);
    status = release(status);
    if (began) status = atomic.finish(status);
    return publish(status, implicit, transactions, result);
  }

  boolean legacyTable() { return legacyTable; }

  private StatusCode release(StatusCode status) {
    if (!current.isActive()) return status;
    StatusCode released = current.release();
    return status.isOk() ? released : status;
  }

  private static StatusCode publish(
      StatusCode status,
      boolean implicit,
      SqlTransactionState transactions,
      SqlExecutionResult result) {
    if (!status.isOk()) return status;
    long commit = implicit ? transactions.commitSequence() : 0;
    result.setUpdate(0, commit);
    result.setTransaction(transactions.isExplicit(), commit);
    return StatusCode.OK;
  }
}
