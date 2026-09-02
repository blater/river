package io.riverdb.engine.schema.catalog;

import io.riverdb.engine.schema.TableDescriptor;

/** Allocation-free agreement check for the schema hash repeated across key chunks. */
final class CatalogIndexSchemaHashState {
  private long value;
  private boolean accepted;

  boolean accept(long candidate) {
    if (accepted && value != candidate) return false;
    value = candidate;
    accepted = true;
    return true;
  }

  boolean matches(TableDescriptor table) {
    int count = CatalogIndexSchemaHash.indexCount(table);
    return count == 0 ? value == 0 : value == CatalogIndexSchemaHash.value(table);
  }

  void reset() {
    value = 0;
    accepted = false;
  }
}
