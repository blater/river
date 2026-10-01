package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalDescriptorRenameChange;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Publishes an ordinal-preserving descriptor successor for ALTER TABLE RENAME COLUMN. */
final class SqlDescriptorColumnRename extends SqlDescriptorDdl {
  private final RelationalDescriptorRenameChange change =
      new RelationalDescriptorRenameChange();
  private final TableDescriptor.Result proposal = new TableDescriptor.Result();
  private final SchemaPin current = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);

  @Override
  StatusCode executeBody(RelationalSession session, SqlCommand command) {
    StatusCode status = session.resolveDescriptor(command.tableName(), current, detail);
    if (status == StatusCode.CONFLICT) legacyTable = true;
    if (!status.isOk()) return status;
    status = session.checkViewReferences(current.tableId());
    if (!status.isOk()) return status;
    status = change.column(
        current.descriptor(), command.firstColumnName(), command.secondColumnName(),
        proposal, detail);
    if (!status.isOk()) return status;
    return session.prepareDescriptorSuccessor(
        command.tableName(), current, proposal.value(), detail);
  }

  @Override
  StatusCode release(StatusCode status) {
    if (!current.isActive()) return status;
    StatusCode released = current.release();
    return status.isOk() ? released : status;
  }
}
