package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.tx.api.IsolationLevel;

/** Publishes a wide catalog-v2 table and its transactional exact-name entry. */
final class SqlDescriptorTableCreation {
  private final SqlDescriptorTableBuilder builder = new SqlDescriptorTableBuilder();
  private final SqlDescriptorForeignKeyBuilder foreignKeys =
      new SqlDescriptorForeignKeyBuilder();
  private final StatusDetail detail = new StatusDetail(128);

  StatusCode execute(
      RelationalSession session,
      SqlTransactionState transactions,
      SqlAtomicStatementLifecycle atomic,
      SqlCommand command,
      SqlExecutionResult result) {
    StatusCode status = builder.build(command, detail);
    if (!status.isOk()) return status;
    status = atomic.begin(IsolationLevel.SERIALIZABLE);
    boolean began = status.isOk();
    boolean implicit = began && atomic.implicit();
    TableDescriptor descriptor = builder.descriptor();
    if (status.isOk()) status = foreignKeys.build(
        session, command, descriptor, detail);
    if (status.isOk()) descriptor = foreignKeys.descriptor();
    if (status.isOk()) status = session.prepareDescriptorTable(
        command.tableName(), descriptor, detail);
    if (began) status = atomic.finish(status);
    if (status.isOk()) {
      long commit = implicit ? transactions.commitSequence() : 0;
      result.setUpdate(0, commit);
      result.setTransaction(transactions.isExplicit(), commit);
    }
    return status;
  }
}
