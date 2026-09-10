package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedLogicalRowIdReservation;
import io.riverdb.tx.api.TransactionState;

/** Owns one-pass descriptor INSERT admission and staging for a statement. */
public final class RelationalDescriptorBatchInsert {
  private final RelationalSession owner;
  private final IndexedTransactionSession session;
  private final RelationalDescriptorRowBuffer rowBuffer = new RelationalDescriptorRowBuffer();
  private final IndexedLogicalRowIdReservation reserved =
      new IndexedLogicalRowIdReservation();
  private final RelationalDescriptorTupleMutations tupleMutations =
      new RelationalDescriptorTupleMutations();
  private final RelationalDescriptorCheckValidation checks =
      new RelationalDescriptorCheckValidation();

  RelationalDescriptorBatchInsert(
      RelationalSession relationalSession,
      IndexedTransactionSession indexedSession) {
    owner = relationalSession;
    session = indexedSession;
  }

  public StatusCode begin(
      RelationalDescriptorInsertBatch batch, SchemaPin pin, int rowCount) {
    if (!active() || batch == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = RelationalDescriptorPin.validTable(owner, pin);
    if (table == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = batch.begin(table, rowCount, session.transaction().transactionId());
    if (status.isOk()) status = owner.reserveDescriptorLogicalRowId(
        table.tableId(), rowCount, reserved);
    if (status.isOk()) batch.reserve(reserved.firstLogicalRowId());
    else batch.reset();
    return status;
  }

  public StatusCode insert(
      RelationalDescriptorInsertBatch batch, SchemaPin pin,
      SqlValueBuffer values, RelationalRowIdentityResult result) {
    if (!active() || batch == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = RelationalDescriptorPin.validTable(owner, pin, values);
    if (table == null || !batch.canAdmit(table, session.transaction().transactionId())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int row = batch.nextRow();
    long logicalRowId = batch.logicalRowId(row);
    result.reset();
    StatusCode status = rowBuffer.reserve(table.encodedMaximumRowBytes());
    if (status.isOk()) status = rowBuffer.encode(table, logicalRowId, values);
    if (status.isOk()) status = checks.validate(table, values);
    if (status.isOk()) status = tupleMutations.planInsert(table, values, logicalRowId);
    if (status.isOk()) status = tupleMutations.preflightSingleRow(
        session, table, rowBuffer.length());
    if (status.isOk()) status = tupleMutations.validateInsert(
        session, table, logicalRowId);
    if (status.isOk() && batch.rowCount() == 1 && table.foreignKeyCount() > 0) {
      status = tupleMutations.validateForeign(session, table, values);
    }
    if (status.isOk()) status = session.insert(
        RelationalDescriptorKeyspace.baseRows(table.tableId()), logicalRowId,
        rowBuffer.bytes());
    if (status.isOk()) status = tupleMutations.stage(session, table, logicalRowId);
    if (status.isOk()) {
      status = batch.admit(table);
      if (status.isOk()) result.set(logicalRowId);
    }
    return status;
  }

  public StatusCode validateForeignKeys(
      RelationalDescriptorInsertBatch batch, SchemaPin pin, int row,
      SqlValueBuffer values) {
    if (!active() || batch == null || values == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = RelationalDescriptorPin.validTable(owner, pin, values);
    if (table == null || !batch.belongsTo(session.transaction().transactionId())
        || !batch.rowAdmitted(table, row)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = owner.descriptorRows().fetchByLogicalRowId(
        pin, batch.logicalRowId(row), values);
    return status.isOk() ? tupleMutations.validateForeign(session, table, values) : status;
  }

  private boolean active() {
    return session != null && session.transaction().state() == TransactionState.ACTIVE;
  }
}
