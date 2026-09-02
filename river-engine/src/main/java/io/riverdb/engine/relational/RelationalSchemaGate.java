package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Owns transaction admission, schema-change exclusion, and schema freshness. */
final class RelationalSchemaGate {
  private volatile long schemaVersion = 1;
  private long publishedSchemaAdmission;
  private RelationalSession schemaChangeOwner;
  private long schemaChangeAdmission;
  private long nextSchemaChangeAdmission = 1;
  private int activeTransactions;
  private int activeSequenceOperations;

  synchronized StatusCode enterTransaction(RelationalSession requester) {
    if (requester == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (schemaChangeOwner != null && schemaChangeOwner != requester) {
      return StatusCode.RETRY;
    }
    activeTransactions++;
    return StatusCode.OK;
  }

  synchronized void leaveTransaction() {
    activeTransactions--;
  }

  synchronized StatusCode beginSchemaChange(RelationalSession owner) {
    if (owner == null
        || schemaChangeOwner != null
        || activeTransactions != 1
        || activeSequenceOperations != 0) {
      return StatusCode.RETRY;
    }
    schemaChangeOwner = owner;
    schemaChangeAdmission = nextSchemaChangeAdmission++;
    if (schemaChangeAdmission == 0) {
      schemaChangeAdmission = nextSchemaChangeAdmission++;
    }
    return StatusCode.OK;
  }

  synchronized void completeSchemaChange(RelationalSession owner, boolean committed) {
    if (owner != null && schemaChangeOwner == owner) {
      if (committed) {
        schemaVersion++;
        publishedSchemaAdmission = schemaChangeAdmission;
      }
      schemaChangeOwner = null;
      schemaChangeAdmission = 0;
    }
  }

  synchronized StatusCode publishOwnedSchema(RelationalSession owner) {
    if (owner == null || schemaChangeOwner != owner) {
      return StatusCode.NOT_OWNER;
    }
    schemaVersion++;
    publishedSchemaAdmission = schemaChangeAdmission;
    return StatusCode.OK;
  }

  synchronized StatusCode enterSequenceOperation() {
    if (schemaChangeOwner != null) {
      return StatusCode.RETRY;
    }
    activeSequenceOperations++;
    return StatusCode.OK;
  }

  synchronized StatusCode enterIdentitySequenceOperation(TableDefinition table) {
    if (!owns(table)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (schemaChangeOwner != null) {
      return StatusCode.RETRY;
    }
    activeSequenceOperations++;
    return StatusCode.OK;
  }

  synchronized void leaveSequenceOperation() {
    activeSequenceOperations--;
  }

  synchronized StatusCode bindOwnedDefinition(
      RelationalSession owner,
      TableDefinition definition) {
    if (definition == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (owner == null || schemaChangeOwner != owner) {
      return StatusCode.NOT_OWNER;
    }
    definition.bindSchema(this, schemaVersion + 1, schemaChangeAdmission);
    return StatusCode.OK;
  }

  synchronized boolean owns(TableDefinition definition) {
    return definition != null
        && definition.matchesSchema(
            this,
            schemaVersion,
            publishedSchemaAdmission,
            schemaChangeAdmission);
  }

  long version() {
    return schemaVersion;
  }

  boolean matchesVersion(long expected) {
    return expected > 0 && schemaVersion == expected;
  }
}
