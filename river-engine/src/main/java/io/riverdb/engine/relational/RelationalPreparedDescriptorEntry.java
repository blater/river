package io.riverdb.engine.relational;

import io.riverdb.engine.schema.catalog.CatalogPreparedTable;
import io.riverdb.tx.api.TransactionState;

/** Fixed session slot for one durable private descriptor build and its overlay identity. */
final class RelationalPreparedDescriptorEntry {
  final CatalogPreparedTable prepared = new CatalogPreparedTable();
  private final char[] name = new char[TableSchema.MAXIMUM_NAME_LENGTH];
  private int nameLength;
  private int mutationStart;
  private int hiddenMutationStart = -1;
  private boolean visible;
  private TransactionState resolution = TransactionState.ACTIVE;

  void begin(CharSequence value, int mutations) {
    nameLength = value.length();
    for (int index = 0; index < nameLength; index++) name[index] = value.charAt(index);
    mutationStart = mutations;
    hiddenMutationStart = -1;
    visible = false;
    resolution = TransactionState.ACTIVE;
  }

  void publishOverlay() { visible = true; }

  void rollbackTo(int mutations) {
    if (mutations <= mutationStart) visible = false;
    if (hiddenMutationStart >= 0 && mutations <= hiddenMutationStart) {
      hiddenMutationStart = -1;
    }
  }

  void hide(int mutations) { hiddenMutationStart = mutations; }

  boolean matches(CharSequence value) {
    if (!visible || hiddenMutationStart >= 0
        || value == null || value.length() != nameLength) return false;
    for (int index = 0; index < nameLength; index++) {
      if (value.charAt(index) != name[index]) return false;
    }
    return true;
  }

  boolean owns(long objectId) {
    return visible && hiddenMutationStart < 0 && prepared.objectId() == objectId;
  }

  boolean replacesPublished(CharSequence value) {
    return matches(value) && prepared.replacesPublished();
  }

  boolean isVisible() { return visible && hiddenMutationStart < 0; }

  boolean authorizes(io.riverdb.engine.schema.cache.SchemaPin pin) {
    return visible && hiddenMutationStart < 0 && prepared.authorizes(pin);
  }

  void resolve(TransactionState outer) {
    resolution = outer == TransactionState.COMMITTED && isVisible()
        ? TransactionState.COMMITTED : outer == TransactionState.INDETERMINATE
            ? TransactionState.INDETERMINATE : TransactionState.ABORTED;
  }

  TransactionState resolution() { return resolution; }

  void clear() {
    for (int index = 0; index < nameLength; index++) name[index] = 0;
    nameLength = 0;
    mutationStart = 0;
    hiddenMutationStart = -1;
    visible = false;
    resolution = TransactionState.ACTIVE;
  }
}
