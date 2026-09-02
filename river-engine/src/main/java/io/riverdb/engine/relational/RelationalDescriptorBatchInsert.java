package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedLogicalRowIdReservation;
import io.riverdb.tx.api.TransactionState;

/** Allocation-free two-pass admission and staging for one descriptor INSERT statement. */
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
    return table == null
        ? StatusCode.INVALID_EXTERNAL_INPUT : batch.begin(table, rowCount);
  }

  public StatusCode admit(
      RelationalDescriptorInsertBatch batch, SchemaPin pin, SqlValueBuffer values) {
    if (!active() || batch == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = RelationalDescriptorPin.validTable(owner, pin, values);
    if (table == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = rowBuffer.reserve(table.encodedMaximumRowBytes());
    if (status.isOk()) status = rowBuffer.encode(table, 1, values);
    if (status.isOk()) status = checks.validate(table, values);
    if (status.isOk()) status = tupleMutations.planInsert(table, values, 1);
    if (status.isOk()) status = tupleMutations.validateUniqueAdmission(table, batch);
    return status.isOk()
        ? batch.admit(table, rowBuffer.length(),
            tupleMutations.mutationCount(), tupleMutations.payloadBytes(),
            rowBuffer.contentFingerprint()) : status;
  }

  public StatusCode reserve(
      RelationalDescriptorInsertBatch batch, SchemaPin pin) {
    if (!active() || batch == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = RelationalDescriptorPin.validTable(owner, pin);
    if (table == null || !batch.admittedFor(table)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = tupleMutations.preflightWithRows(
        session, table, batch.mutationLengths(), 0, batch.mutationCount(),
        batch.tupleMutationCount(), batch.tuplePayloadBytes());
    if (status.isOk()) status = batch.validateUnique(session, table);
    if (status.isOk()) status = owner.reserveDescriptorLogicalRowId(
        table.tableId(), batch.rowCount(), reserved);
    if (status.isOk()) batch.reserve(reserved.firstLogicalRowId());
    return status;
  }

  public StatusCode insert(
      RelationalDescriptorInsertBatch batch,
      SchemaPin pin,
      int row,
      SqlValueBuffer values,
      RelationalRowIdentityResult result) {
    return insert(batch, pin, row, values, result, false);
  }

  public StatusCode insertDeferredForeignKeys(
      RelationalDescriptorInsertBatch batch,
      SchemaPin pin,
      int row,
      SqlValueBuffer values,
      RelationalRowIdentityResult result) {
    return insert(batch, pin, row, values, result, true);
  }

  public StatusCode validateForeignKeys(SchemaPin pin, SqlValueBuffer values) {
    if (!active()) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = RelationalDescriptorPin.validTable(owner, pin, values);
    return table == null ? StatusCode.INVALID_EXTERNAL_INPUT
        : tupleMutations.validateForeign(session, table, values);
  }

  private StatusCode insert(
      RelationalDescriptorInsertBatch batch,
      SchemaPin pin,
      int row,
      SqlValueBuffer values,
      RelationalRowIdentityResult result,
      boolean deferForeignKeys) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!active() || batch == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = RelationalDescriptorPin.validTable(owner, pin, values);
    if (table == null || !batch.mayInsert(table, row)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long logicalRowId = batch.logicalRowId(row);
    StatusCode status = rowBuffer.reserve(table.encodedMaximumRowBytes());
    if (status.isOk()) status = rowBuffer.encode(table, logicalRowId, values);
    if (status.isOk() && !batch.matchesEncodedRow(
        row, rowBuffer.length(), rowBuffer.contentFingerprint())) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) status = tupleMutations.planInsert(table, values, logicalRowId);
    if (status.isOk()) status = tupleMutations.validateInsert(
        session, table, values, logicalRowId, !deferForeignKeys);
    if (status.isOk()) status = session.insert(
        RelationalDescriptorKeyspace.baseRows(table.tableId()), logicalRowId,
        rowBuffer.bytes());
    if (status.isOk()) status = tupleMutations.stage(session, table, logicalRowId);
    if (status.isOk()) {
      batch.inserted();
      result.set(logicalRowId);
    }
    return status;
  }

  private boolean active() {
    return session != null && session.transaction().state() == TransactionState.ACTIVE;
  }
}
