package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleIndexState;
import io.riverdb.engine.table.IndexedTupleProbeResult;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;

/** Allocation-free referenced-key validation for descriptor foreign keys. */
final class RelationalDescriptorForeignValidation {
  private final RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
  private final RelationalForeignCandidateMatch candidate =
      new RelationalForeignCandidateMatch();
  private final RelationalForeignTupleProtection protection =
      new RelationalForeignTupleProtection(candidate);
  private final RelationalForeignKeyDelta delta = new RelationalForeignKeyDelta();
  private final IndexedTupleIndexState referencedState = new IndexedTupleIndexState();
  private final IndexedTupleProbeResult referencedProbe = new IndexedTupleProbeResult();

  StatusCode validate(
      IndexedTransactionSession session, TableDescriptor table, SqlValueBuffer values) {
    return validate(session, table, values, null);
  }

  StatusCode validateUpdate(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer before, SqlValueBuffer after) {
    StatusCode status = delta.prepare(table, before, after);
    if (status.isOk()) status = validate(session, table, after, delta);
    delta.reset();
    return status;
  }

  private StatusCode validate(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer values, RelationalForeignKeyDelta changes) {
    StatusCode protectedKeys = protection.protect(session, table, values, changes);
    if (!protectedKeys.isOk()) return protectedKeys;
    for (int index = 0; index < table.foreignKeyCount(); index++) {
      if (changes != null && !changes.changedAt(index)) continue;
      KeyDescriptor foreign = table.foreignKeyAt(index);
      StatusCode status = encoder.encodeUser(foreign, values);
      if (!status.isOk()) return status;
      if (encoder.containsNull()) continue;
      if (candidate.matches(table, foreign, values, encoder)) continue;
      status = session.readTupleIndexState(foreign.referencedKeyId(), referencedState);
      if (!status.isOk()) return status == StatusCode.CONFLICT
          ? StatusCode.CORRUPTION : status;
      if (!validReference(foreign)) return StatusCode.CORRUPTION;
      status = session.resolveTupleUniqueCurrent(
          referencedState.ownerObjectId(), foreign.referencedKeyId(),
          foreign.referencedKeyId(), foreign.shape(), encoder.bytes(), 0,
          encoder.length(), referencedProbe);
      if (!status.isOk()) return status;
      if (!referencedProbe.found()) return StatusCode.FOREIGN_KEY_VIOLATION;
    }
    return StatusCode.OK;
  }

  private boolean validReference(KeyDescriptor foreign) {
    if (referencedState.state() != TupleIndexRootRecordCodec.STATE_READY
        || referencedState.rootPageId() <= 0
        || referencedState.keyId() != foreign.referencedKeyId()
        || referencedState.schemaId() != foreign.referencedKeyId()
        || referencedState.ownerObjectId() <= 0
        || referencedState.descriptorCount() != foreign.partCount()) return false;
    for (int part = 0; part < foreign.partCount(); part++) {
      if (referencedState.descriptorAt(part) != foreign.typeDescriptorAt(part)) return false;
    }
    return true;
  }
}
