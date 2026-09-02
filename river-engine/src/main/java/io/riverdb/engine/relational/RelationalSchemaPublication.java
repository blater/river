package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Publishes one owned schema transition and refreshes its reusable index descriptor. */
final class RelationalSchemaPublication {
  private RelationalSchemaPublication() {}

  static StatusCode publish(
      RelationalSchemaGate gate,
      RelationalSession owner,
      TableDefinition indexedTable,
      TableDefinition indexStorageTable) {
    StatusCode status = gate.publishOwnedSchema(owner);
    if (status.isOk()) {
      indexStorageTable.set(
          gate, indexedTable.uniqueValueIndexTableId(), 0, TableDefinition.INDEX_NONE);
    }
    return status;
  }
}
