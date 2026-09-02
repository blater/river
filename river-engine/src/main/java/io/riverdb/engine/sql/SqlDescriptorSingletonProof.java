package io.riverdb.engine.sql;

import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlComparison;

/** Proves an equality predicate can match at most one row, independent of access choice. */
final class SqlDescriptorSingletonProof {
  private SqlDescriptorSingletonProof() { }

  static boolean exact(
      TableDescriptor table, SqlDescriptorPredicateBindings bindings) {
    if (fullyBound(table.primaryKey(), bindings)) return true;
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      if (fullyBound(table.secondaryKeyAt(index), bindings)) return true;
    }
    return false;
  }

  private static boolean fullyBound(
      KeyDescriptor key, SqlDescriptorPredicateBindings bindings) {
    if (key == null || !key.isUnique()) return false;
    for (int part = 0; part < key.partCount(); part++) {
      if (bindings.find(key.columnOrdinalAt(part), SqlComparison.EQUAL) < 0) return false;
    }
    return true;
  }
}
