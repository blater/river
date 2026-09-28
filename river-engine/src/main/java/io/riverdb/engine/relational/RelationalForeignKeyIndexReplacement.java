package io.riverdb.engine.relational;

import io.riverdb.engine.schema.ForeignKeySupport;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Retires automatic FK support only when a new index covers every former key part. */
final class RelationalForeignKeyIndexReplacement {
  private RelationalForeignKeyIndexReplacement() { }

  static int count(TableDescriptor table, KeyDescriptor added) {
    int removed = 0;
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      if (superseded(table, table.secondaryKeyAt(index), added)) removed++;
    }
    return removed;
  }

  static void copyRetained(
      TableDescriptor table, KeyDescriptor added, KeyDescriptor[] destination) {
    int next = 0;
    for (int index = 0; index < table.secondaryKeyCount(); index++) {
      KeyDescriptor candidate = table.secondaryKeyAt(index);
      if (!superseded(table, candidate, added)) destination[next++] = candidate;
    }
  }

  private static boolean superseded(
      TableDescriptor table, KeyDescriptor existing, KeyDescriptor added) {
    String name = existing.name();
    if (existing.kind() != KeyDescriptor.KIND_SECONDARY || existing.isUnique()
        || name == null || !name.startsWith("_river_fk_")
        || !ForeignKeySupport.supports(added, existing)) return false;
    for (int index = 0; index < table.foreignKeyCount(); index++) {
      if (ForeignKeySupport.supports(existing, table.foreignKeyAt(index))) return true;
    }
    return false;
  }
}
