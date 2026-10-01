package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Renames one published descriptor table transactionally without changing its identity. */
final class SqlDescriptorTableRename extends SqlDescriptorDdl {
  private final SchemaPin current = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);

  @Override
  StatusCode executeBody(RelationalSession session, SqlCommand command) {
    StatusCode status = session.resolveDescriptor(command.tableName(), current, detail);
    if (status == StatusCode.CONFLICT) legacyTable = true;
    if (!status.isOk()) return status;
    status = session.checkViewReferences(current.tableId());
    if (!status.isOk()) return status;
    return session.renameDescriptorTable(
        command.tableName(), command.renamedTableName(), current, detail);
  }

  @Override
  StatusCode release(StatusCode status) {
    if (!current.isActive()) return status;
    StatusCode released = current.release();
    return status.isOk() ? released : status;
  }
}
