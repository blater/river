package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Matches a self-referenced parent key against the statement candidate row. */
final class RelationalForeignCandidateMatch {
  private final RelationalTupleKeyEncoder target = new RelationalTupleKeyEncoder();

  boolean matches(
      TableDescriptor table, KeyDescriptor foreign, SqlValueAccess values,
      RelationalTupleKeyEncoder source) {
    KeyDescriptor key = table.physicalKey(foreign.referencedKeyId());
    if (key == null) return false;
    StatusCode status = target.encodeUser(key, values);
    return status.isOk() && !target.containsNull() && source.sameBytes(target);
  }
}
