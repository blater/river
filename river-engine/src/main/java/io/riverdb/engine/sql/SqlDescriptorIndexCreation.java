package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalDescriptorIndexChange;
import io.riverdb.engine.relational.RelationalIndexBackfillPlan;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.tx.api.IsolationLevel;

/** Builds and publishes one bounded catalog-v2 secondary tuple index. */
final class SqlDescriptorIndexCreation {
  private final RelationalDescriptorIndexChange change =
      new RelationalDescriptorIndexChange();
  private final TableDescriptor.Result proposal = new TableDescriptor.Result();
  private final RelationalIndexBackfillPlan backfill = new RelationalIndexBackfillPlan();
  private final SchemaPin current = new SchemaPin();
  private final SchemaPin measured = new SchemaPin();
  private final SchemaPin staged = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);
  private final int[] ordinals = new int[KeyDescriptor.MAXIMUM_PARTS];
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
    if (status.isOk()) status = executeBody(session, command);
    status = release(status);
    if (began) status = atomic.finish(status);
    return publishResult(status, implicit, transactions, result);
  }

  private StatusCode executeBody(RelationalSession session, SqlCommand command) {
    StatusCode status = session.resolveDescriptor(command.tableName(), current, detail);
    if (status == StatusCode.CONFLICT) legacyTable = true;
    if (!status.isOk()) return status;
    status = bind(command, current.descriptor());
    if (!status.isOk()) return status;
    status = change.add(
        current.descriptor(), command.indexName(),
        command.type() == SqlCommandType.CREATE_UNIQUE_INDEX,
        ordinals, 0, command.columnCount(), proposal, detail);
    if (!status.isOk()) return status;
    status = session.prepareDescriptorSuccessorBuild(
        command.tableName(), current, proposal.value(), detail);
    if (!status.isOk()) return status;
    int index = proposal.value().secondaryKeyCount() - 1;
    status = backfill(session, command, index);
    return status.isOk()
        ? session.stagePreparedDescriptorSuccessor(command.tableName(), detail) : status;
  }

  boolean legacyTable() { return legacyTable; }

  private StatusCode backfill(
      RelationalSession session, SqlCommand command, int index) {
    StatusCode status = session.resolveDescriptor(command.tableName(), measured, detail);
    if (!status.isOk()) return status;
    status = session.descriptorRows().indexBackfill().measure(measured, index, backfill);
    if (!status.isOk()) return status;
    status = session.resolveDescriptor(command.tableName(), staged, detail);
    return status.isOk()
        ? session.descriptorRows().indexBackfill().stage(staged, index, backfill) : status;
  }

  private StatusCode bind(SqlCommand command, TableDescriptor table) {
    if (table == null || command.columnCount() <= 0
        || command.columnCount() > ordinals.length) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int part = 0; part < command.columnCount(); part++) {
      int column = table.findColumn(command.columnName(part));
      if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      ordinals[part] = column;
    }
    return StatusCode.OK;
  }

  private StatusCode release(StatusCode status) {
    StatusCode released = release(staged);
    if (status.isOk()) status = released;
    released = release(measured);
    if (status.isOk()) status = released;
    released = release(current);
    return status.isOk() ? released : status;
  }

  private static StatusCode release(SchemaPin pin) {
    return pin.isActive() ? pin.release() : StatusCode.OK;
  }

  private static StatusCode publishResult(
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
