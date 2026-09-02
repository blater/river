package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogKeyspace;

/** One durable tuple-index identity with an operation-local root overlay. */
final class IndexedTupleRootState {
  private long keyId;
  private long schemaId;
  private int rootPageId;
  private int operationRootPageId;
  private boolean active;

  IndexedTupleRootState(long durableKeyId, long keySchemaId, int root) {
    configure(durableKeyId, keySchemaId, root);
  }

  StatusCode configure(long durableKeyId, long keySchemaId, int root) {
    if (!canConfigure(durableKeyId, keySchemaId, root)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    keyId = durableKeyId;
    schemaId = keySchemaId;
    rootPageId = root;
    operationRootPageId = 0;
    return StatusCode.OK;
  }

  boolean canConfigure(long durableKeyId, long keySchemaId, int root) {
    return !active && CatalogKeyspace.validKeyId(durableKeyId)
        && keySchemaId > 0 && root >= 0;
  }

  StatusCode begin() {
    if (active || !CatalogKeyspace.validKeyId(keyId)
        || schemaId <= 0 || rootPageId < 0) {
      return StatusCode.INVARIANT_BROKEN;
    }
    operationRootPageId = rootPageId;
    active = true;
    return StatusCode.OK;
  }

  StatusCode replace(int expected, int replacement) {
    if (!active || expected != operationRootPageId || replacement <= 0) {
      return StatusCode.CONFLICT;
    }
    operationRootPageId = replacement;
    return StatusCode.OK;
  }

  StatusCode publish() {
    if (!active || operationRootPageId <= 0) return StatusCode.INVARIANT_BROKEN;
    rootPageId = operationRootPageId;
    active = false;
    return StatusCode.OK;
  }

  void cancel() { active = false; operationRootPageId = 0; }
  long keyId() { return keyId; }
  long schemaId() { return schemaId; }
  int rootPageId() { return active ? operationRootPageId : rootPageId; }
  boolean active() { return active; }
}
