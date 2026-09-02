package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import java.nio.ByteBuffer;

/** Matches a self-referenced parent key against the statement candidate row. */
final class RelationalForeignCandidateMatch {
  private final RelationalTupleKeyEncoder target = new RelationalTupleKeyEncoder();

  boolean matches(
      TableDescriptor table, KeyDescriptor foreign, SqlValueBuffer values,
      RelationalTupleKeyEncoder source) {
    KeyDescriptor key = physicalKey(table, foreign.referencedKeyId());
    if (key == null) return false;
    StatusCode status = target.encodeUser(key, values);
    return status.isOk() && !target.containsNull() && equal(source, target);
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
