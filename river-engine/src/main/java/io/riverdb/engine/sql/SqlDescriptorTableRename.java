package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;
import io.riverdb.tx.api.IsolationLevel;

/** Renames one published descriptor table transactionally without changing its identity. */
final class SqlDescriptorTableRename {
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
    if (status.isOk()) status = session.renameDescriptorTable(
        command.tableName(), command.renamedTableName(), current, detail);
    status = release(status);
    if (began) status = atomic.finish(status);
    if (!status.isOk()) return status;
    long commit = implicit ? transactions.commitSequence() : 0;
    result.setUpdate(0, commit);
    result.setTransaction(transactions.isExplicit(), commit);
    return StatusCode.OK;
  }

  boolean legacyTable() { return legacyTable; }

  private StatusCode release(StatusCode status) {
    if (!current.isActive()) return status;
    StatusCode released = current.release();
    return status.isOk() ? released : status;
  }
}
