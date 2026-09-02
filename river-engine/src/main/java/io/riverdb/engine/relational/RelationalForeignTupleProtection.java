package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.btree.TupleKeyCodec;

/** Protects referenced parent tuples through the shared ordered tuple-lock path. */
final class RelationalForeignTupleProtection {
  private final RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
  private final RelationalTupleKeyEncoder floor = new RelationalTupleKeyEncoder();
  private final RelationalTupleKeyEncoder best = new RelationalTupleKeyEncoder();
  private final RelationalForeignCandidateMatch candidate;
  private int nextIndex;

  RelationalForeignTupleProtection(RelationalForeignCandidateMatch candidateMatch) {
    candidate = candidateMatch;
  }

  StatusCode protect(
      IndexedTransactionSession session, TableDescriptor table, SqlValueBuffer values) {
    return protect(session, table, values, null);
  }

  StatusCode protect(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer values, RelationalForeignKeyDelta changes) {
    long afterKeyId = 0;
    for (int protectedIds = 0; protectedIds < table.foreignKeyCount(); protectedIds++) {
      long keyId = nextKeyId(table, changes, afterKeyId);
      if (keyId <= 0) break;
      afterKeyId = keyId;
      StatusCode status = protectKey(session, table, values, changes, keyId);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode protectKey(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueBuffer values, RelationalForeignKeyDelta changes, long keyId) {
    int previous = -1;
    for (int count = 0; count < table.foreignKeyCount(); count++) {
      StatusCode status = selectNext(table, values, changes, keyId, previous);
      if (!status.isOk()) return status;
      if (nextIndex < 0) break;
      KeyDescriptor foreign = table.foreignKeyAt(nextIndex);
      status = encoder.encodeUser(foreign, values);
      if (status.isOk()) status = session.protectTupleKey(
          keyId, encoder.bytes(), 0, encoder.length());
      if (!status.isOk()) return status;
      previous = nextIndex;
    }
    return StatusCode.OK;
  }

  private StatusCode selectNext(
      TableDescriptor table, SqlValueBuffer values, RelationalForeignKeyDelta changes,
      long keyId, int previous) {
    nextIndex = -1;
    StatusCode status = previous < 0 ? StatusCode.OK
        : floor.encodeUser(table.foreignKeyAt(previous), values);
    if (!status.isOk()) return status;
    for (int index = 0; index < table.foreignKeyCount(); index++) {
      if (changes != null && !changes.changedAt(index)) continue;
      KeyDescriptor foreign = table.foreignKeyAt(index);
      if (foreign.referencedKeyId() != keyId) continue;
      status = encoder.encodeUser(foreign, values);
      if (!status.isOk()) return status;
      if (encoder.containsNull() || candidate.matches(table, foreign, values, encoder)) continue;
      int compared = previous < 0 ? 1 : compare(encoder, floor);
      if (compared < 0 || compared == 0 && index <= previous) continue;
      if (nextIndex >= 0) {
        status = best.encodeUser(table.foreignKeyAt(nextIndex), values);
        if (!status.isOk()) return status;
        compared = compare(encoder, best);
      }
      if (nextIndex < 0 || compared < 0
          || compared == 0 && index < nextIndex) nextIndex = index;
    }
    return StatusCode.OK;
  }

  private static int compare(
      RelationalTupleKeyEncoder left, RelationalTupleKeyEncoder right) {
    return TupleKeyCodec.compare(
        left.bytes(), 0, left.length(), right.bytes(), 0, right.length());
  }

  private static long nextKeyId(
      TableDescriptor table, RelationalForeignKeyDelta changes, long afterKeyId) {
    long found = Long.MAX_VALUE;
    for (int index = 0; index < table.foreignKeyCount(); index++) {
      if (changes != null && !changes.changedAt(index)) continue;
      long keyId = table.foreignKeyAt(index).referencedKeyId();
      if (keyId > afterKeyId && keyId < found) found = keyId;
    }
    return found == Long.MAX_VALUE ? 0 : found;
  }
}
