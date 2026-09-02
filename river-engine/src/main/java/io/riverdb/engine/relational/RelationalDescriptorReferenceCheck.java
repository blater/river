package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleProbeResult;
import java.nio.ByteBuffer;

/** Checks one referencing descriptor against a parent row change. */
final class RelationalDescriptorReferenceCheck {
  private final IndexedTransactionSession session;
  private final RelationalTupleKeyEncoder beforeKey = new RelationalTupleKeyEncoder();
  private final RelationalTupleKeyEncoder afterKey = new RelationalTupleKeyEncoder();
  private final IndexedTupleProbeResult probe = new IndexedTupleProbeResult();

  RelationalDescriptorReferenceCheck(IndexedTransactionSession indexedSession) {
    session = indexedSession;
  }

  StatusCode check(
      TableDescriptor parent, SqlValueBuffer before, SqlValueBuffer after,
      TableDescriptor child, long changedRowId) {
    for (int index = 0; index < child.foreignKeyCount(); index++) {
      KeyDescriptor foreign = child.foreignKeyAt(index);
      KeyDescriptor target = physicalKey(parent, foreign.referencedKeyId());
      if (target == null) continue;
      StatusCode status = changed(target, before, after);
      if (status == StatusCode.CONFLICT) continue;
      if (!status.isOk()) return status;
      KeyDescriptor support = supportingKey(child, foreign);
      if (support == null || !sameShape(target, support)) return StatusCode.CORRUPTION;
      if (child.tableId() == parent.tableId() && after != null) {
        status = afterKey.encodeUser(foreign, after);
        if (!status.isOk()) return status;
        if (!afterKey.containsNull() && equal(beforeKey, afterKey)) {
          return StatusCode.FOREIGN_KEY_VIOLATION;
        }
      }
      status = session.protectTupleKeyForWrite(
          target.keyId(), beforeKey.bytes(), 0, beforeKey.length());
      if (status.isOk()) status = child.tableId() == parent.tableId()
          ? session.resolveTupleAnyPrefixCurrentExcept(
              child.tableId(), support.keyId(), support.keyId(), support.shape(),
              beforeKey.bytes(), 0, beforeKey.length(), changedRowId, probe)
          : session.resolveTupleAnyPrefixCurrent(
              child.tableId(), support.keyId(), support.keyId(), support.shape(),
              beforeKey.bytes(), 0, beforeKey.length(), probe);
      if (!status.isOk()) return status;
      if (probe.found()) return StatusCode.FOREIGN_KEY_VIOLATION;
    }
    return StatusCode.OK;
  }

  boolean references(TableDescriptor parent, TableDescriptor child) {
    for (int index = 0; index < child.foreignKeyCount(); index++) {
      if (physicalKey(parent, child.foreignKeyAt(index).referencedKeyId()) != null) {
        return true;
      }
    }
    return false;
  }

  private StatusCode changed(
      KeyDescriptor target, SqlValueBuffer before, SqlValueBuffer after) {
    StatusCode status = beforeKey.encodeUser(target, before);
    if (!status.isOk()) return status;
    if (beforeKey.containsNull()) return StatusCode.CONFLICT;
    if (after == null) return StatusCode.OK;
    status = afterKey.encodeUser(target, after);
    if (!status.isOk()) return status;
    return equal(beforeKey, afterKey) ? StatusCode.CONFLICT : StatusCode.OK;
  }

  private static KeyDescriptor physicalKey(TableDescriptor table, long keyId) {
    if (table.primaryKey() != null && table.primaryKey().keyId() == keyId) {
      return table.primaryKey();
    }
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      if (table.secondaryKeyAt(index).keyId() == keyId) return table.secondaryKeyAt(index);
    }
    return null;
  }

  private static KeyDescriptor supportingKey(
      TableDescriptor child, KeyDescriptor foreign) {
    for (int index = 0; index < child.secondaryKeyCount(); index++) {
      KeyDescriptor candidate = child.secondaryKeyAt(index);
      if (sameColumns(candidate, foreign)) return candidate;
    }
    return null;
  }

  private static boolean sameColumns(KeyDescriptor left, KeyDescriptor right) {
    if (left.partCount() != right.partCount()) return false;
    for (int part = 0; part < left.partCount(); part++) {
      if (left.columnOrdinalAt(part) != right.columnOrdinalAt(part)) return false;
    }
    return true;
  }

  private static boolean sameShape(KeyDescriptor left, KeyDescriptor right) {
    if (left.partCount() != right.partCount()) return false;
    for (int part = 0; part < left.partCount(); part++) {
      if (left.typeDescriptorAt(part) != right.typeDescriptorAt(part)) return false;
    }
    return true;
  }

  private static boolean equal(
      RelationalTupleKeyEncoder left, RelationalTupleKeyEncoder right) {
    if (left.length() != right.length()) return false;
    ByteBuffer leftBytes = left.bytes();
    ByteBuffer rightBytes = right.bytes();
    for (int index = 0; index < left.length(); index++) {
      if (leftBytes.get(index) != rightBytes.get(index)) return false;
    }
    return true;
  }
}
