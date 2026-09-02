package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalDescriptorRenameChange;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.tx.api.IsolationLevel;

/** Renames one ordinary descriptor secondary index without rebuilding its storage. */
final class SqlDescriptorIndexRename {
  private final RelationalDescriptorRenameChange change =
      new RelationalDescriptorRenameChange();
  private final SqlDescriptorIndexOwnerResolver owner =
      new SqlDescriptorIndexOwnerResolver();
  private final TableDescriptor.Result proposal = new TableDescriptor.Result();
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
    if (status.isOk()) status = owner.resolve(
        session, command.indexName(), command.renamedIndexName(), detail);
    if (status == StatusCode.CONFLICT && owner.legacyIndex()) legacyTable = true;
    if (status.isOk()) status = change.index(
        owner.owner().descriptor(), command.indexName(), command.renamedIndexName(),
        proposal, detail);
    if (status.isOk()) status = session.prepareDescriptorSuccessor(
        owner.tableName(), owner.owner(), proposal.value(), detail);
    status = release(status);
    if (began) status = atomic.finish(status);
    return publish(status, implicit, transactions, result);
  }

  boolean legacyTable() { return legacyTable; }

  private StatusCode release(StatusCode status) {
    StatusCode released = owner.release();
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
