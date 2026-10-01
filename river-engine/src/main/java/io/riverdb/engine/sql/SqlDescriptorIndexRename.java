package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalDescriptorRenameChange;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Renames one ordinary descriptor secondary index without rebuilding its storage. */
final class SqlDescriptorIndexRename extends SqlDescriptorDdl {
  private final RelationalDescriptorRenameChange change =
      new RelationalDescriptorRenameChange();
  private final SqlDescriptorIndexOwnerResolver owner =
      new SqlDescriptorIndexOwnerResolver();
  private final TableDescriptor.Result proposal = new TableDescriptor.Result();
  private final StatusDetail detail = new StatusDetail(128);

  @Override
  StatusCode executeBody(RelationalSession session, SqlCommand command) {
    StatusCode status = owner.resolve(
        session, command.indexName(), command.renamedIndexName(), detail);
    if (status == StatusCode.CONFLICT && owner.legacyIndex()) legacyTable = true;
    if (status.isOk()) status = change.index(
        owner.owner().descriptor(), command.indexName(), command.renamedIndexName(),
        proposal, detail);
    if (status.isOk()) status = session.prepareDescriptorSuccessor(
        owner.tableName(), owner.owner(), proposal.value(), detail);
    return status;
  }

  @Override
  StatusCode release(StatusCode status) {
    StatusCode released = owner.release();
    return status.isOk() ? released : status;
  }
}
