package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleIndexState;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.base.error.StatusDetail;

/** Validates durable foreign references and their local supporting roots. */
final class RelationalDescriptorForeignKeyStorageValidation {
  private final IndexedTransactionSession session;
  private final RelationalDatabaseServices services;
  private final IndexedTupleIndexState state = new IndexedTupleIndexState();
  private final SchemaPin referenced = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);

  RelationalDescriptorForeignKeyStorageValidation(
      IndexedTransactionSession indexedSession,
      RelationalDatabaseServices databaseServices) {
    session = indexedSession;
    services = databaseServices;
  }

  StatusCode validate(TableDescriptor table) {
    for (int index = 0; index < table.foreignKeyCount(); index++) {
      KeyDescriptor foreign = table.foreignKeyAt(index);
      KeyDescriptor support = support(table, foreign);
      if (support == null) return StatusCode.CORRUPTION;
      StatusCode status = validateRoot(
          foreign.referencedKeyId(), 0, foreign);
      if (status.isOk()) status = validateReferencedKey(foreign);
      if (status.isOk()) status = validateRoot(
          support.keyId(), table.tableId(), support);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode validateReferencedKey(KeyDescriptor foreign) {
    detail.reset();
    StatusCode status = services.descriptors().open(state.ownerObjectId(), referenced, detail);
    if (status == StatusCode.CONFLICT) return StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    KeyDescriptor target = physicalKey(referenced.descriptor(), foreign.referencedKeyId());
    boolean valid = target != null && target.isUnique() && sameShape(target, foreign);
    StatusCode released = referenced.release();
    if (!valid) return StatusCode.CORRUPTION;
    return released;
  }

  private StatusCode validateRoot(
      long keyId, long expectedOwner, KeyDescriptor key) {
    StatusCode status = session.readTupleIndexState(keyId, state);
    if (!status.isOk()) return status == StatusCode.CONFLICT
        ? StatusCode.CORRUPTION : status;
    if (state.state() != TupleIndexRootRecordCodec.STATE_READY
        || state.rootPageId() <= 0 || state.keyId() != keyId
        || state.schemaId() != keyId || state.ownerObjectId() <= 0
        || expectedOwner != 0 && state.ownerObjectId() != expectedOwner
        || state.descriptorCount() != key.partCount()) return StatusCode.CORRUPTION;
    for (int part = 0; part < key.partCount(); part++) {
      if (state.descriptorAt(part) != key.typeDescriptorAt(part)) {
        return StatusCode.CORRUPTION;
      }
    }
    return StatusCode.OK;
  }

  private static KeyDescriptor support(
      TableDescriptor table, KeyDescriptor foreign) {
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      KeyDescriptor candidate = table.secondaryKeyAt(index);
      if (candidate.partCount() != foreign.partCount()) continue;
      int part = 0;
      while (part < foreign.partCount()
          && candidate.columnOrdinalAt(part) == foreign.columnOrdinalAt(part)) part++;
      if (part == foreign.partCount()) return candidate;
    }
    return null;
  }

  private static KeyDescriptor physicalKey(TableDescriptor table, long keyId) {
    if (table.primaryKey() != null && table.primaryKey().keyId() == keyId) {
      return table.primaryKey();
    }
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      KeyDescriptor key = table.secondaryKeyAt(index);
      if (key.keyId() == keyId) return key;
    }
    return null;
  }

  private static boolean sameShape(KeyDescriptor left, KeyDescriptor right) {
    if (left.partCount() != right.partCount()) return false;
    for (int part = 0; part < left.partCount(); part++) {
      if (left.typeDescriptorAt(part) != right.typeDescriptorAt(part)) return false;
    }
    return true;
  }
}
