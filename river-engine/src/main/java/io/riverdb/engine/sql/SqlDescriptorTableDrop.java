package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;

/** Removes one descriptor table name transactionally after dependency validation. */
final class SqlDescriptorTableDrop extends SqlDescriptorDdl {
  private final SchemaPin current = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);

  @Override
  StatusCode executeBody(RelationalSession session, SqlCommand command) {
    StatusCode status = session.resolveDescriptor(
        command.tableName(), current, detail);
    if (status == StatusCode.CONFLICT) legacyTable = true;
    if (status.isOk()) status = session.checkViewReferences(current.tableId());
    if (status.isOk()) status = session.dropDescriptorTable(
        command.tableName(), current, detail);
    return status;
  }

  @Override
  StatusCode release(StatusCode status) {
    if (!current.isActive()) return status;
    StatusCode released = current.release();
    return status.isOk() ? released : status;
  }
}
